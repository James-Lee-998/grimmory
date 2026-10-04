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

    private Long virtualBookId;
    private Long mirrorId;
    private Long libraryPathId;
}
