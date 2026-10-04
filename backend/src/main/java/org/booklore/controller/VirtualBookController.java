package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.model.dto.VirtualBookDto;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.task.TaskService;
import org.booklore.service.virtualbook.VirtualBookService;
import org.booklore.task.options.VirtualBookDownloadOptions;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/virtual-books")
@Tag(name = "Virtual Books", description = "Endpoints for downloading virtual books from external mirrors")
public class VirtualBookController {

    private final TaskService taskService;
    private final VirtualBookService virtualBookService;

    public record DownloadRequest(Long mirrorId, Long libraryPathId) {
    }

    @Operation(
            summary = "List virtual books",
            description = "Page through virtual books, optionally filtered by a title or author keyword.",
            operationId = "virtualBookList"
    )
    @GetMapping
    @PreAuthorize("@securityUtil.canUpload() or @securityUtil.isAdmin()")
    public Page<VirtualBookDto> list(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 25, sort = "title") Pageable pageable) {
        return virtualBookService.list(search, pageable);
    }

    @Operation(
            summary = "Download virtual book",
            description = "Queue a download of the virtual book from the selected mirror into the given library path. "
                    + "Falls back to other mirrors with the same format if the selected one fails.",
            operationId = "virtualBookDownload"
    )
    @PostMapping("/{id}/download")
    @PreAuthorize("@securityUtil.canUpload() or @securityUtil.isAdmin()")
    public ResponseEntity<TaskCreateResponse> download(@PathVariable Long id, @RequestBody DownloadRequest request) {
        TaskCreateRequest taskRequest = TaskCreateRequest.builder()
                .taskType(TaskType.DOWNLOAD_VIRTUAL_BOOK)
                .options(VirtualBookDownloadOptions.builder()
                        .virtualBookRefId(id)
                        .mirrorId(request.mirrorId())
                        .libraryPathId(request.libraryPathId())
                        .build())
                .build();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(taskService.runAsUser(taskRequest));
    }
}
