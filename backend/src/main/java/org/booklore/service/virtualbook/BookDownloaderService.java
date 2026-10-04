package org.booklore.service.virtualbook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.VirtualBookMirror;
import org.booklore.model.enums.BookFileType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * Downloads a mirror to a temporary file. The caller owns the returned file and must delete it.
 * Placing the file into a library is handled by {@link BookMaterializationService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookDownloaderService {

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    public static final Set<BookFileType> DOWNLOADABLE_TYPES =
            EnumSet.of(BookFileType.EPUB, BookFileType.PDF, BookFileType.MOBI, BookFileType.AZW3, BookFileType.FB2);

    private final HttpClient httpClient;

    public Path downloadToTemp(VirtualBookMirror mirror) throws IOException, InterruptedException {
        BookFileType format = mirror.getFormat();
        if (!DOWNLOADABLE_TYPES.contains(format)) {
            throw new IllegalArgumentException("Unsupported download format: " + format);
        }

        Path temp = Files.createTempFile("grimmory-download-", "." + extensionFor(format));

        HttpRequest request = HttpRequest.newBuilder(URI.create(mirror.getDownloadUrl()))
                .header("User-Agent", "GrimmoryServer/1.0")
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Mirror returned HTTP " + response.statusCode() + " for " + mirror.getDownloadUrl());
            }
            if (Files.size(temp) == 0) {
                throw new IOException("Mirror returned an empty file for " + mirror.getDownloadUrl());
            }
            log.info("Downloaded mirror {} ({}) to {}", mirror.getId(), mirror.getProvider(), temp);
            return temp;
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    public static String extensionFor(BookFileType format) {
        return format.name().toLowerCase();
    }
}
