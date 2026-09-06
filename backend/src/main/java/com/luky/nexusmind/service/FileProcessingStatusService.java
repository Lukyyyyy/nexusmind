package com.luky.nexusmind.service;

import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.FileProcessingStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FileProcessingStatusService {

    private final FileProcessingStatusRepository repository;
    private final ProcessingStatusEventService eventService;

    public FileProcessingStatusService(FileProcessingStatusRepository repository,
                                       ProcessingStatusEventService eventService) {
        this.repository = repository;
        this.eventService = eventService;
    }

    @Transactional
    public FileProcessingStatus markQueued(FileProcessingTask task) {
        FileProcessingStatus status = getOrCreate(task);
        if (status.getId() != null && (status.getState() == ProcessingState.RUNNING
                || status.getState() == ProcessingState.PENDING)) {
            throw new IllegalStateException("文件已有待处理任务");
        }
        task.setAttemptId(UUID.randomUUID().toString());
        status.setAttemptId(task.getAttemptId());
        status.setLastSuccessfulStage(null);
        status.setParseEngine(task.getParseEngine() == null ? ParseEngine.AUTO : task.getParseEngine());
        status.setChunkSize(task.getChunkSize());
        status.setActualParseEngine(null);
        status.setCurrentStage(ProcessingStage.QUEUED);
        status.setState(ProcessingState.PENDING);
        status.setMessage("等待处理");
        status.setErrorMessage(null);
        status.setParsedChunkCount(0);
        status.setVectorizedCount(0);
        status.setEsDocumentCount(0L);
        status.setProcessingStartedAt(LocalDateTime.now());
        status.setAccumulatedProcessingDurationMillis(0L);
        status.setCompletedAt(null);
        return saveAndPublish(status);
    }

    /**
     * 将失败任务原子地认领为待处理，避免用户连续点击产生重复 Kafka 任务。
     * 与首次入队不同，重新处理会保留已经完成阶段的统计信息。
     */
    @Transactional
    public boolean claimRetry(FileProcessingTask task) {
        Optional<FileProcessingStatus> existing = repository.findByFileMd5AndUserIdForUpdate(
                task.getFileMd5(), task.getUserId());
        if (existing.isEmpty() || existing.get().getState() != ProcessingState.FAILED) {
            return false;
        }

        FileProcessingStatus status = existing.get();
        status.setAccumulatedProcessingDurationMillis(
                accumulatedDurationWithPreviousAttempt(status));
        status.setParseEngine(task.getParseEngine() == null ? status.getParseEngine() : task.getParseEngine());
        status.setChunkSize(task.getChunkSize() == null ? status.getChunkSize() : task.getChunkSize());
        task.setAttemptId(UUID.randomUUID().toString());
        status.setAttemptId(task.getAttemptId());
        // 保留数据库中的断点；消息中的 resumeFromStage 可能早已过时。
        status.setState(ProcessingState.PENDING);
        status.setMessage("等待重新处理");
        status.setErrorMessage(null);
        status.setProcessingStartedAt(LocalDateTime.now());
        status.setCompletedAt(null);
        saveAndPublish(status);
        return true;
    }

    private long accumulatedDurationWithPreviousAttempt(FileProcessingStatus status) {
        long accumulated = status.getAccumulatedProcessingDurationMillis() == null
                ? 0L
                : status.getAccumulatedProcessingDurationMillis();
        LocalDateTime attemptStartedAt = status.getProcessingStartedAt();
        LocalDateTime attemptEndedAt = status.getCompletedAt() != null
                ? status.getCompletedAt()
                : status.getUpdatedAt();
        if (attemptStartedAt == null || attemptEndedAt == null) {
            return accumulated;
        }
        return accumulated + Math.max(0L,
                Duration.between(attemptStartedAt, attemptEndedAt).toMillis());
    }

    @Transactional
    public void markRunning(FileProcessingTask task, ProcessingStage stage, String message) {
        FileProcessingStatus status = repository.findByFileMd5AndUserIdForUpdate(
                task.getFileMd5(), task.getUserId()).orElse(null);
        if (status == null) return;
        if (!acceptsUpdate(status, task)) return;
        applyRunning(status, stage, message);
        saveAndPublish(status);
    }

    @Transactional
    public void markRunning(String fileMd5, String userId, ProcessingStage stage, String message) {
        repository.findByFileMd5AndUserId(fileMd5, userId).ifPresent(status -> {
            applyRunning(status, stage, message);
            saveAndPublish(status);
        });
    }

    private void applyRunning(FileProcessingStatus status, ProcessingStage stage, String message) {
        if (status.getProcessingStartedAt() == null) {
            status.setProcessingStartedAt(LocalDateTime.now());
        }
        status.setCurrentStage(stage);
        status.setMessage(message);
        if (stage == ProcessingStage.PARSING) {
            status.setLastSuccessfulStage(null);
            status.setParsedChunkCount(0);
        }
        status.setState(ProcessingState.RUNNING);
        status.setErrorMessage(null);
    }

    @Transactional
    public void markParsed(FileProcessingTask task, int chunkCount) {
        FileProcessingStatus status = repository.findByFileMd5AndUserIdForUpdate(
                task.getFileMd5(), task.getUserId()).orElse(null);
        if (status == null) return;
        if (!acceptsUpdate(status, task)) return;
        status.setLastSuccessfulStage(ProcessingStage.CHUNKING);
        status.setCurrentStage(ProcessingStage.CHUNKING);
        status.setMessage("解析和切片完成");
        status.setState(ProcessingState.RUNNING);
        status.setParsedChunkCount(chunkCount);
        status.setErrorMessage(null);
        saveAndPublish(status);
    }

    @Transactional
    public void markActualParseEngine(String fileMd5, String userId, ParseEngine actualParseEngine) {
        repository.findByFileMd5AndUserId(fileMd5, userId).ifPresent(status -> {
            status.setActualParseEngine(actualParseEngine);
            saveAndPublish(status);
        });
    }

    @Transactional
    public void markCompleted(FileProcessingTask task, int vectorizedCount, long esDocumentCount) {
        FileProcessingStatus status = repository.findByFileMd5AndUserIdForUpdate(
                task.getFileMd5(), task.getUserId()).orElse(null);
        if (status == null) return;
        if (!acceptsUpdate(status, task)) return;
        status.setLastSuccessfulStage(ProcessingStage.COMPLETED);
        status.setCurrentStage(ProcessingStage.COMPLETED);
        status.setState(ProcessingState.SUCCEEDED);
        status.setVectorizedCount(vectorizedCount);
        status.setEsDocumentCount(esDocumentCount);
        status.setMessage("处理完成");
        status.setErrorMessage(null);
        status.setCompletedAt(LocalDateTime.now());
        saveAndPublish(status);
    }

    @Transactional
    public void markFailed(FileProcessingTask task, ProcessingStage stage, Exception exception) {
        FileProcessingStatus status = repository.findByFileMd5AndUserIdForUpdate(
                task.getFileMd5(), task.getUserId()).orElse(null);
        if (status == null) return;
        if (!acceptsUpdate(status, task) || status.getState() == ProcessingState.SUCCEEDED) return;
        status.setCurrentStage(stage == null ? status.getCurrentStage() : stage);
        status.setState(ProcessingState.FAILED);
        status.setMessage("处理失败");
        status.setErrorMessage(rootCauseMessage(exception));
        status.setCompletedAt(LocalDateTime.now());
        saveAndPublish(status);
    }

    @Transactional(readOnly = true)
    public Optional<FileProcessingStatus> findByFileMd5AndUserId(String fileMd5, String userId) {
        return repository.findByFileMd5AndUserId(fileMd5, userId);
    }

    @Transactional(readOnly = true)
    public Map<String, FileProcessingStatus> findLatestByFileMd5(Collection<String> fileMd5List, String userId) {
        List<FileProcessingStatus> statuses = repository.findByFileMd5InAndUserId(fileMd5List, userId);
        return statuses.stream()
                .collect(Collectors.toMap(FileProcessingStatus::getFileMd5, Function.identity(), (left, right) ->
                        left.getUpdatedAt() != null && right.getUpdatedAt() != null && left.getUpdatedAt().isAfter(right.getUpdatedAt())
                                ? left
                                : right
                ));
    }

    @Transactional
    public void delete(String fileMd5, String userId) {
        repository.deleteByFileMd5AndUserId(fileMd5, userId);
    }

    private FileProcessingStatus getOrCreate(FileProcessingTask task) {
        return repository.findByFileMd5AndUserIdForUpdate(task.getFileMd5(), task.getUserId())
                .orElseGet(() -> {
                    FileProcessingStatus status = new FileProcessingStatus();
                    status.setFileMd5(task.getFileMd5());
                    status.setFileName(task.getFileName());
                    status.setUserId(task.getUserId());
                    status.setParseEngine(task.getParseEngine() == null ? ParseEngine.AUTO : task.getParseEngine());
                    status.setChunkSize(task.getChunkSize());
                    status.setCreatedAt(LocalDateTime.now());
                    return status;
                });
    }

    private FileProcessingStatus saveAndPublish(FileProcessingStatus status) {
        FileProcessingStatus saved = repository.saveAndFlush(status);
        eventService.publish(saved);
        return saved;
    }

    /** 原子认领消息，避免检查版本后用户又发起新一轮重试。RUNNING 允许崩溃后的 Kafka 重投恢复。 */
    @Transactional
    public boolean beginAttempt(FileProcessingTask task) {
        var existing = repository.findByFileMd5AndUserIdForUpdate(task.getFileMd5(), task.getUserId());
        if (existing.isEmpty()) return false;
        var status = existing.get();
        if (!acceptsUpdate(status, task) || status.getState() == ProcessingState.SUCCEEDED
                || status.getState() == ProcessingState.FAILED) return false;
        status.setState(ProcessingState.RUNNING);
        saveAndPublish(status);
        return true;
    }

    private boolean acceptsUpdate(FileProcessingStatus status, FileProcessingTask task) {
        return Objects.equals(status.getAttemptId(), task.getAttemptId());
    }

    private String rootCauseMessage(Throwable exception) {
        Throwable root = exception;
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (root.getCause() != null && visited.add(root) && !visited.contains(root.getCause())) {
            root = root.getCause();
        }
        // 错误可展示给用户，但不保留 URL 的签名参数。
        String message = root.getClass().getSimpleName() + ": " + Objects.toString(root.getMessage(), "处理失败");
        return truncate(message.replaceAll("(https?://[^\\s?]+)\\?[^\\s]+", "$1?[已隐藏参数]"), 2000);
    }

    private String truncate(String message, int maxLength) {
        if (message == null || message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength);
    }
}
