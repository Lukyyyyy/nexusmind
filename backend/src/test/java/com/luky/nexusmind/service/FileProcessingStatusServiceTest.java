package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileProcessingStatus;
import com.luky.nexusmind.model.FileProcessingTask;
import com.luky.nexusmind.model.ProcessingState;
import com.luky.nexusmind.model.ProcessingStage;
import com.luky.nexusmind.repository.FileProcessingStatusRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileProcessingStatusServiceTest {

    @Test
    void retryAccumulatesPreviousAttemptButExcludesIdleTime() {
        LocalDateTime now = LocalDateTime.now();
        FileProcessingStatus status = new FileProcessingStatus();
        status.setFileMd5("0123456789abcdef0123456789abcdef");
        status.setUserId("1");
        status.setState(ProcessingState.FAILED);
        status.setProcessingStartedAt(now.minusMinutes(30));
        status.setCompletedAt(now.minusMinutes(29));
        status.setUpdatedAt(now.minusMinutes(29));
        status.setAccumulatedProcessingDurationMillis(20_000L);

        FileProcessingStatusRepository repository = repositoryReturning(status);
        FileProcessingStatusService service = new FileProcessingStatusService(
                repository, new ProcessingStatusEventService());
        FileProcessingTask task = new FileProcessingTask();
        task.setFileMd5(status.getFileMd5());
        task.setUserId(status.getUserId());

        assertTrue(service.claimRetry(task));

        long accumulated = status.getAccumulatedProcessingDurationMillis();
        assertTrue(accumulated >= 79_000L && accumulated < 82_000L);
        assertTrue(status.getProcessingStartedAt().isAfter(now.minusSeconds(2)));
        assertNull(status.getCompletedAt());
        assertEquals(ProcessingState.PENDING, status.getState());
    }

    @Test
    void staleFailureCannotOverwriteNewAttemptOrSuccessfulResult() {
        FileProcessingStatus status = new FileProcessingStatus();
        status.setAttemptId("new");
        status.setState(ProcessingState.SUCCEEDED);
        status.setCurrentStage(ProcessingStage.COMPLETED);
        var service = new FileProcessingStatusService(repositoryReturning(status), new ProcessingStatusEventService());
        var task = new FileProcessingTask();
        task.setAttemptId("old");
        service.markFailed(task, ProcessingStage.PARSING, new RuntimeException("old failure"));
        assertEquals(ProcessingState.SUCCEEDED, status.getState());
        task.setAttemptId("new");
        service.markFailed(task, ProcessingStage.PARSING, new RuntimeException("late failure"));
        assertEquals(ProcessingStage.COMPLETED, status.getCurrentStage());
    }

    @Test
    void retryRetainsCheckpointAndAssignsNewVersion() {
        FileProcessingStatus status = new FileProcessingStatus();
        status.setAttemptId("old");
        status.setState(ProcessingState.FAILED);
        status.setCurrentStage(ProcessingStage.INDEXING);
        status.setLastSuccessfulStage(ProcessingStage.CHUNKING);
        var service = new FileProcessingStatusService(repositoryReturning(status), new ProcessingStatusEventService());
        var task = new FileProcessingTask();
        assertTrue(service.claimRetry(task));
        assertEquals(task.getAttemptId(), status.getAttemptId());
        assertTrue(!"old".equals(task.getAttemptId()));
        assertEquals(ProcessingStage.INDEXING, status.getCurrentStage());
        assertEquals(ProcessingStage.CHUNKING, status.getLastSuccessfulStage());
    }

    @Test
    void reportsActualFailureStageAndRootCauseWithoutSignature() {
        FileProcessingStatus status = new FileProcessingStatus();
        status.setState(ProcessingState.RUNNING);
        status.setCurrentStage(ProcessingStage.INDEXING);
        var service = new FileProcessingStatusService(repositoryReturning(status), new ProcessingStatusEventService());
        var task = new FileProcessingTask();
        service.markRunning(task, ProcessingStage.PARSING, "下载中");
        service.markFailed(task, null, new RuntimeException("listener", new java.io.IOException("403 https://minio/file?token=secret")));
        assertEquals(ProcessingStage.PARSING, status.getCurrentStage());
        assertTrue(status.getErrorMessage().startsWith("IOException: 403"));
        assertTrue(!status.getErrorMessage().contains("secret"));
    }

    @Test
    void beginAtomicallyRejectsOldAndTerminalTasksButAllowsRestart() {
        var status = new FileProcessingStatus();
        status.setAttemptId("current");
        status.setState(ProcessingState.PENDING);
        var service = new FileProcessingStatusService(repositoryReturning(status), new ProcessingStatusEventService());
        var task = new FileProcessingTask();
        task.setAttemptId("old");
        assertTrue(!service.beginAttempt(task));
        task.setAttemptId("current");
        assertTrue(service.beginAttempt(task));
        assertEquals(ProcessingState.RUNNING, status.getState());
        assertTrue(service.beginAttempt(task));
        status.setState(ProcessingState.SUCCEEDED);
        assertTrue(!service.beginAttempt(task));
        status.setState(ProcessingState.FAILED);
        assertTrue(!service.beginAttempt(task));
    }

    private FileProcessingStatusRepository repositoryReturning(FileProcessingStatus status) {
        return (FileProcessingStatusRepository) Proxy.newProxyInstance(
                FileProcessingStatusRepository.class.getClassLoader(),
                new Class<?>[]{FileProcessingStatusRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByFileMd5AndUserIdForUpdate", "findByFileMd5AndUserId" -> Optional.of(status);
                    case "saveAndFlush" -> args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
