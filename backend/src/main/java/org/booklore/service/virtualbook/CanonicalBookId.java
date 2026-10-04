package org.booklore.service.virtualbook;

import org.booklore.model.enums.Provider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Derives the canonical virtual book ID from title, authors and language, so every provider that carries the same
 * book links its mirrors to the same virtual book.
 * <p>
 * Matching is exact after normalisation: case, accents, apostrophes, punctuation, parenthetical name expansions
 * ("Mary E. (Mary Emily) Ropes"), name order ("Horton, Robert J." vs "Robert J. Horton") and a leading English
 * article are ignored. Spelling variants still produce different IDs. Callers pass the full title including any
 * subtitle, and bracketed title text is kept, because both often carry the volume or distinguish works (merging
 * ten volumes into one is worse than missing a duplicate). Language is part of the key so translations stay
 * separate. Books without an author are not merged across providers, otherwise every anonymous "Poems" would
 * collapse into one book.
 * <p>
 * Changing this algorithm changes every ID, so existing virtual books would need re-seeding.
 */
public final class CanonicalBookId {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)|\\[[^]]*]");
    private static final Pattern APOSTROPHES = Pattern.compile("['‘’ʼ]");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern LEADING_ARTICLE = Pattern.compile("^(the|a|an) ");

    private CanonicalBookId() {
    }

    public static long of(String title, String authors, String language, Provider provider, String externalId) {
        return hash(key(title, authors, language, provider, externalId));
    }

    static String key(String title, String authors, String language, Provider provider, String externalId) {
        String normalizedAuthors = normalizeAuthors(authors);
        if (normalizedAuthors.isEmpty()) {
            return "provider:" + provider + ":" + externalId;
        }
        // Titles keep bracketed text: it often carries the volume number, e.g. "(Vol. 3/10)".
        String normalizedTitle = LEADING_ARTICLE.matcher(normalize(title, false)).replaceFirst("");
        String normalizedLanguage = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        return "title:" + normalizedTitle + "|authors:" + normalizedAuthors + "|lang:" + normalizedLanguage;
    }

    static String normalizeAuthors(String authors) {
        if (authors == null) {
            return "";
        }
        return Arrays.stream(authors.split(";"))
                .map(CanonicalBookId::normalizeName)
                .filter(name -> !name.isEmpty())
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private static String normalizeName(String name) {
        return Arrays.stream(normalize(name, true).split(" "))
                .filter(token -> !token.isEmpty())
                .sorted()
                .collect(Collectors.joining(" "));
    }

    static String normalize(String value, boolean stripBracketed) {
        if (value == null) {
            return "";
        }
        String withoutParentheticals = stripBracketed ? PARENTHETICAL.matcher(value).replaceAll(" ") : value;
        String decomposed = Normalizer.normalize(withoutParentheticals, Normalizer.Form.NFKD);
        String withoutAccents = DIACRITICS.matcher(decomposed).replaceAll("");
        // Drop apostrophes rather than splitting on them, so "Hoyle's" and "Hoyles" match.
        String withoutApostrophes = APOSTROPHES.matcher(withoutAccents).replaceAll("");
        return NON_ALPHANUMERIC.matcher(withoutApostrophes.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    // 53 bits so the ID survives JSON round-trips through JavaScript numbers exactly. Collision odds stay negligible
    // (about 1 in 3 million at 80k books, 1 in 18,000 at a million).
    private static final long JS_SAFE_INTEGER_MASK = (1L << 53) - 1;

    private static long hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong() & JS_SAFE_INTEGER_MASK;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
