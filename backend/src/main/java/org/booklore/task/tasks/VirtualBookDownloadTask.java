package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.service.virtualbook.BookMaterializationService;
import org.booklore.task.TaskStatus;
import org.booklore.task.options.VirtualBookDownloadOptions;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class VirtualBookDownloadTask implements Task {

    private final BookMaterializationService bookMaterializationService;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_UPLOAD.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_UPLOAD);
        }
        VirtualBookDownloadOptions options = request.getOptionsAs(VirtualBookDownloadOptions.class);
        if (options == null || options.getVirtualBookRefId() == null || options.getMirrorId() == null || options.getLibraryPathId() == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("virtualBookRefId, mirrorId and libraryPathId are required");
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        VirtualBookDownloadOptions options = request.getOptionsAs(VirtualBookDownloadOptions.class);

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started. TaskId: {}, Options: {}", getTaskType(), request.getTaskId(), options);

        bookMaterializationService.materialize(options.getVirtualBookRefId(), options.getMirrorId(), options.getLibraryPathId());

        log.info("{}: Task completed. Duration: {} ms", getTaskType(), System.currentTimeMillis() - startTime);

        return TaskCreateResponse.builder()
                .taskId(request.getTaskId())
                .taskType(getTaskType())
                .status(TaskStatus.COMPLETED)
                .build();
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.DOWNLOAD_VIRTUAL_BOOK;
    }
}
