package org.booklore.service.virtualbook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.FileProcessResult;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.VirtualBookEntity;
import org.booklore.model.entity.VirtualBookMirror;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.VirtualBookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.event.BookAddedEvent;
import org.booklore.service.fileprocessor.BookFileProcessorRegistry;
import org.booklore.service.metadata.MetadataRefreshService;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.booklore.util.FileUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a virtual book into a real library book: download a mirror, place the file in a library path,
 * run it through the regular file processor and apply the virtual book's metadata.
 * <p>
 * The download happens outside any transaction and before library monitoring is paused, so a slow mirror
 * doesn't hold a DB connection or leave a watched library unmonitored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookMaterializationService {

    private static final int MAX_FILE_NAME_LENGTH = 150;

    private final VirtualBookRepository virtualBookRepository;
    private final LibraryPathRepository libraryPathRepository;
    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final BookDownloaderService bookDownloaderService;
    private final BookFileProcessorRegistry processorRegistry;
    private final MetadataRefreshService metadataRefreshService;
    private final MonitoringRegistrationService monitoringRegistrationService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * Downloads the selected mirror into the given library path. If the selected mirror fails, other mirrors
     * with the same format are tried in order of quality score.
     */
    public Book materialize(Long id, Long mirrorId, Long libraryPathId) {
        VirtualBookEntity virtualBook = virtualBookRepository.findById(id)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Virtual book not found: " + id));

        List<VirtualBookMirror> candidates = orderMirrors(virtualBook, mirrorId);
        Path downloaded = downloadFirstAvailable(candidates);

        try {
            return importIntoLibrary(virtualBook, downloaded, libraryPathId);
        } finally {
            deleteQuietly(downloaded);
        }
    }

    private List<VirtualBookMirror> orderMirrors(VirtualBookEntity virtualBook, Long mirrorId) {
        VirtualBookMirror selected = virtualBook.getMirrors().stream()
                .filter(m -> m.getId().equals(mirrorId))
                .findFirst()
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Mirror " + mirrorId + " not found for virtual book " + virtualBook.getId()));

        List<VirtualBookMirror> ordered = new ArrayList<>();
        ordered.add(selected);
        virtualBook.getMirrors().stream()
                .filter(m -> !m.getId().equals(mirrorId) && m.getFormat() == selected.getFormat())
                .sorted(Comparator.comparingInt(VirtualBookMirror::getQualityScore).reversed())
                .forEach(ordered::add);
        return ordered;
    }

    private Path downloadFirstAvailable(List<VirtualBookMirror> mirrors) {
        List<String> failures = new ArrayList<>();
        for (VirtualBookMirror mirror : mirrors) {
            try {
                return bookDownloaderService.downloadToTemp(mirror);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiError.GENERIC_BAD_REQUEST.createException("Download interrupted");
            } catch (Exception e) {
                log.warn("Mirror {} ({}) failed: {}", mirror.getId(), mirror.getProvider(), e.getMessage());
                failures.add(mirror.getProvider() + ": " + e.getMessage());
            }
        }
        throw ApiError.GENERIC_BAD_REQUEST.createException("All mirrors failed: " + String.join("; ", failures));
    }

    private Book importIntoLibrary(VirtualBookEntity virtualBook, Path downloaded, Long libraryPathId) {
        LibraryPathEntity libraryPath = libraryPathRepository.findById(libraryPathId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Library path not found: " + libraryPathId));
        Long libraryId = libraryPath.getLibrary().getId();
        List<Path> libraryRoots = libraryRepository.findByIdWithPaths(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId))
                .getLibraryPaths().stream()
                .map(p -> Path.of(p.getPath()))
                .toList();

        String extension = extensionOf(downloaded);
        BookFileType fileType = BookFileType.fromExtension(extension)
                .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException(downloaded.getFileName()));
        Path target = Path.of(libraryPath.getPath()).resolve(buildFileName(virtualBook, extension));
        if (Files.exists(target)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("File already exists in library: " + target.getFileName());
        }

        boolean wasMonitored = monitoringRegistrationService.isLibraryMonitored(libraryId);
        if (wasMonitored) {
            monitoringRegistrationService.unregisterLibrary(libraryId);
        }

        try {
            Files.createDirectories(target.getParent());
            // Copy rather than move: the temp dir may be on a different filesystem (see BookDropService).
            Files.copy(downloaded, target, StandardCopyOption.REPLACE_EXISTING);

            return transactionTemplate.execute(status ->
                    processDownloadedFile(virtualBook.getId(), target, fileType, libraryId, libraryPathId));
        } catch (IOException | RuntimeException e) {
            deleteQuietly(target);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw ApiError.GENERIC_BAD_REQUEST.createException("Failed to place file in library: " + e.getMessage());
        } finally {
            if (wasMonitored) {
                libraryRoots.forEach(root -> monitoringRegistrationService.registerLibraryPaths(libraryId, root));
            }
        }
    }

    private Book processDownloadedFile(Long id, Path target, BookFileType fileType, Long libraryId, Long libraryPathId) {
        // Reload inside this transaction so the file processor works with managed entities.
        LibraryEntity library = libraryRepository.findByIdWithPaths(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        LibraryPathEntity libraryPath = libraryPathRepository.findById(libraryPathId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Library path not found: " + libraryPathId));
        VirtualBookEntity virtualBook = virtualBookRepository.findById(id)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Virtual book not found: " + id));

        LibraryFile libraryFile = LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(libraryPath)
                .fileSubPath(FileUtils.getRelativeSubPath(libraryPath.getPath(), target))
                .bookFileType(fileType)
                .fileName(target.getFileName().toString())
                .build();

        FileProcessResult result = processorRegistry.getProcessorOrThrow(fileType).processFile(libraryFile);

        // Same transaction as processFile, so the new book is visible here (see BookDropService.processMovedFile).
        Long bookId = result.getBook().getId();
        BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException("Book ID missing after import"));

        metadataRefreshService.updateBookMetadata(MetadataUpdateContext.builder()
                .bookEntity(bookEntity)
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder().metadata(toMetadata(virtualBook)).build())
                .updateThumbnail(false)
                .mergeCategories(false)
                .replaceMode(MetadataReplaceMode.REPLACE_WHEN_PROVIDED)
                .mergeMoods(true)
                .mergeTags(true)
                .build());

        virtualBook.setDownloaded(true);
        virtualBookRepository.save(virtualBook);

        eventPublisher.publishEvent(new BookAddedEvent(result.getBook()));
        notificationService.sendMessage(Topic.BOOK_ADD, result.getBook());

        log.info("Materialized virtual book {} as book {} in library '{}'", id, bookId, library.getName());
        return result.getBook();
    }

    private BookMetadata toMetadata(VirtualBookEntity virtualBook) {
        return BookMetadata.builder()
                .title(virtualBook.getTitle())
                .authors(splitAuthors(virtualBook.getAuthors()))
                .description(virtualBook.getSummary())
                .language(virtualBook.getLanguage())
                // No published date: issuedDate is when the provider released the file (e.g. Gutenberg's posting
                // date), not when the book was written. The date embedded in the file, if any, is kept.
                .build();
    }

    private static List<String> splitAuthors(String authors) {
        if (authors == null || authors.isBlank()) {
            return null;
        }
        // Split on ';' only: authors are often "Last, First", so commas belong to a single name.
        return Arrays.stream(authors.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String buildFileName(VirtualBookEntity book, String extension) {
        String safeTitle = book.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeTitle.length() > MAX_FILE_NAME_LENGTH) {
            safeTitle = safeTitle.substring(0, MAX_FILE_NAME_LENGTH).trim();
        }
        return safeTitle + " [vb-" + book.getId() + "]." + extension;
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", file, e.getMessage());
        }
    }
}
