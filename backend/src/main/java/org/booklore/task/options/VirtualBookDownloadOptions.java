package org.booklore.task.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualBookDownloadOptions {

    /** The virtual_books.id of the book to download (not the provider's ID). */
    private Long virtualBookRefId;
    private Long mirrorId;
    private Long libraryPathId;
}
