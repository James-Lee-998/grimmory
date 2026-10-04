package org.booklore.service.translation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;

/**
 * Translates text through a LibreTranslate server (https://github.com/LibreTranslate/LibreTranslate).
 * The server URL and API key come from {@code app.translation}; the browser only ever talks to this backend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration LANGUAGE_CACHE_TTL = Duration.ofHours(1);

    private final AppProperties appProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile List<Language> cachedLanguages;
    private volatile Instant languagesFetchedAt = Instant.EPOCH;

    public record Language(String code, String name) {
    }

    /**
     * @param sourceLanguage the language the text was translated from
     * @param autoDetected   true when LibreTranslate detected the source; false when it came from the caller (book metadata)
     */
    public record Translation(String translatedText, String sourceLanguage, boolean autoDetected) {
    }

    // ISO 639-2/B codes that Locale.getISO3Language() does not return (it gives the /T form, e.g. fra, deu, zho).
    private static final Map<String, String> BIBLIOGRAPHIC_CODES = Map.ofEntries(
            Map.entry("fre", "fr"), Map.entry("ger", "de"), Map.entry("chi", "zh"), Map.entry("dut", "nl"),
            Map.entry("cze", "cs"), Map.entry("gre", "el"), Map.entry("per", "fa"), Map.entry("rum", "ro"),
            Map.entry("slo", "sk"), Map.entry("alb", "sq"), Map.entry("arm", "hy"), Map.entry("baq", "eu"),
            Map.entry("bur", "my"), Map.entry("geo", "ka"), Map.entry("ice", "is"), Map.entry("mac", "mk"),
            Map.entry("may", "ms"), Map.entry("tib", "bo"), Map.entry("wel", "cy"));
    private static final Map<String, String> LANGUAGE_CODES_BY_NAME_OR_ISO3 = buildLanguageCodeLookup();

    public boolean isEnabled() {
        String url = appProperties.getTranslation().getLibretranslateUrl();
        return url != null && !url.isBlank();
    }

    public List<Language> languages() {
        requireEnabled();
        List<Language> cached = cachedLanguages;
        if (cached != null && Instant.now().isBefore(languagesFetchedAt.plus(LANGUAGE_CACHE_TTL))) {
            return cached;
        }

        JsonNode body = send(HttpRequest.newBuilder(endpoint("/languages")).timeout(REQUEST_TIMEOUT).GET().build());
        List<Language> languages = new ArrayList<>();
        for (JsonNode node : body) {
            languages.add(new Language(node.path("code").asString(), node.path("name").asString()));
        }
        cachedLanguages = List.copyOf(languages);
        languagesFetchedAt = Instant.now();
        return cachedLanguages;
    }

    public Translation translate(String text, String source, String target) {
        requireEnabled();
        if (text == null || text.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Nothing to translate");
        }
        if (target == null || target.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("A target language is required");
        }
        int maxLength = appProperties.getTranslation().getMaxTextLength();
        if (text.length() > maxLength) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Selection is too long to translate (max " + maxLength + " characters)");
        }

        String resolvedSource = resolveSource(source, target);

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("q", text);
        payload.put("source", resolvedSource);
        payload.put("target", target);
        payload.put("format", "text");
        String apiKey = appProperties.getTranslation().getLibretranslateApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            payload.put("api_key", apiKey);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint("/translate"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        JsonNode body = send(request);

        String translated = body.path("translatedText").asString("");
        if ("auto".equals(resolvedSource)) {
            return new Translation(translated, body.path("detectedLanguage").path("language").asString(null), true);
        }
        return new Translation(translated, resolvedSource, false);
    }

    /**
     * Maps a book's metadata language ("en", "en-US", "eng", "fre", "English", "zh-Hant", ...) to a code this
     * LibreTranslate server supports. Falls back to "auto" when there is no match, and also when the book's language
     * equals the target: the selection is then most likely a foreign-language passage, so let LibreTranslate detect it.
     */
    String resolveSource(String requested, String target) {
        String code = toLanguageCode(requested);
        if (code == null || code.equals(target)) {
            return "auto";
        }
        Set<String> supported = new HashSet<>();
        languages().forEach(language -> supported.add(language.code()));
        // Chinese codes changed between LibreTranslate versions: older ones use zh/zt, 1.9+ use zh-Hans/zh-Hant.
        List<String> candidates = switch (code) {
            case "zh" -> List.of("zh", "zh-Hans");
            case "zt" -> List.of("zt", "zh-Hant");
            default -> List.of(code);
        };
        return candidates.stream().filter(supported::contains).findFirst().orElse("auto");
    }

    static String toLanguageCode(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String value = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        // LibreTranslate uses "zh" for Simplified and "zt" for Traditional Chinese.
        if (value.startsWith("zh") || value.equals("chi") || value.equals("zho") || value.contains("chinese") || value.equals("mandarin")) {
            boolean traditional = value.contains("hant") || value.endsWith("-tw") || value.endsWith("-hk") || value.endsWith("-mo")
                    || value.contains("traditional");
            return traditional ? "zt" : "zh";
        }
        String primary = value.split("-")[0];
        if (primary.length() == 2) {
            return primary;
        }
        return LANGUAGE_CODES_BY_NAME_OR_ISO3.get(primary.length() == 3 ? primary : value);
    }

    private static Map<String, String> buildLanguageCodeLookup() {
        Map<String, String> lookup = new HashMap<>(BIBLIOGRAPHIC_CODES);
        for (String code : Locale.getISOLanguages()) {
            Locale locale = Locale.of(code);
            try {
                lookup.putIfAbsent(locale.getISO3Language(), code);
            } catch (MissingResourceException ignored) {
                // no three-letter code for this language
            }
            lookup.putIfAbsent(locale.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT), code);
        }
        return Map.copyOf(lookup);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
            if (response.statusCode() / 100 != 2) {
                // LibreTranslate reports problems as {"error": "..."}, e.g. an unsupported language pair.
                String error = body.path("error").asString("HTTP " + response.statusCode());
                throw ApiError.TRANSLATION_FAILED.createException(error);
            }
            return body;
        } catch (IOException e) {
            log.warn("LibreTranslate request to {} failed: {}", request.uri(), e.getMessage());
            throw ApiError.TRANSLATION_FAILED.createException("translation server is unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiError.TRANSLATION_FAILED.createException("request interrupted");
        }
    }

    private URI endpoint(String path) {
        String base = appProperties.getTranslation().getLibretranslateUrl().trim();
        return URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path);
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw ApiError.TRANSLATION_NOT_CONFIGURED.createException();
        }
    }
}
