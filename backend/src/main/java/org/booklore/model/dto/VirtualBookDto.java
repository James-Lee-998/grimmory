package org.booklore.model.dto;

import org.booklore.model.entity.VirtualBookEntity;
import org.booklore.model.entity.VirtualBookMirror;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.Provider;

import java.util.Comparator;
import java.util.List;

public record VirtualBookDto(
        Long virtualBookId,
        String title,
        String authors,
        String language,
        String issuedDate,
        String summary,
        boolean downloaded,
        List<Mirror> mirrors
) {

    public record Mirror(Long id, Provider provider, BookFileType format, int qualityScore) {

        static Mirror from(VirtualBookMirror mirror) {
            return new Mirror(mirror.getId(), mirror.getProvider(), mirror.getFormat(), mirror.getQualityScore());
        }
    }

    public static VirtualBookDto from(VirtualBookEntity entity) {
        return new VirtualBookDto(
                entity.getVirtualBookId(),
                entity.getTitle(),
                entity.getAuthors(),
                entity.getLanguage(),
                entity.getIssuedDate(),
                entity.getSummary(),
                entity.isDownloaded(),
                entity.getMirrors().stream()
                        .sorted(Comparator.comparingInt(VirtualBookMirror::getQualityScore).reversed())
                        .map(Mirror::from)
                        .toList()
        );
    }
}
