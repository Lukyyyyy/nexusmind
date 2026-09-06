package com.luky.nexusmind.service;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Per-model admission control; each document gets one turn in a round-robin queue. */
@Service
public class GraphModelScheduler {
    private final ModelConfigService configs;
    private final ScheduledExecutorService dispatcher =
            Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers = Executors.newFixedThreadPool(30);
    private final LinkedHashMap<String, ArrayDeque<Job<?>>> groups = new LinkedHashMap<>();
    private final Map<String, Integer> running = new HashMap<>();
    private final Map<String, Set<Job<?>>> admitted = new HashMap<>();
    private final Set<String> cancelled = new HashSet<>();
    private int active;
    private boolean closed;

    public GraphModelScheduler(ModelConfigService configs) {
        this.configs = configs;
        dispatcher.scheduleWithFixedDelay(this::dispatch, 0, 100, TimeUnit.MILLISECONDS);
    }

    public synchronized <T> CompletableFuture<T> submit(
            String document,
            String username,
            Function<ModelConfigService.ResolvedModelConfig, T> call) {
        if (closed || cancelled.contains(document)) return CompletableFuture.failedFuture(new CancellationException("服务停止"));
        var future = new CompletableFuture<T>();
        groups.computeIfAbsent(document, k -> new ArrayDeque<>())
                .add(new Job<>(username, call, future));
        return future;
    }

    private synchronized void dispatch() {
        // No worker waits for a permit, so one busy model cannot starve another model's workers.
        int turns = groups.size();
        while (turns-- > 0 && active < 30 && !groups.isEmpty()) {
            String document = groups.keySet().iterator().next();
            var queue = groups.remove(document);
            Job<?> job = queue.peek();
            try {
                var config = configs.resolveGraphExtractionConfig(job.username);
                String key =
                        config.id() == null
                                ? "legacy:" + config.baseUrl() + ":" + config.modelName()
                                : "id:" + config.id();
                int limit =
                        config.maxConcurrency() == null
                                ? 10
                                : Math.max(1, Math.min(30, config.maxConcurrency()));
                if (running.getOrDefault(key, 0) < limit) {
                    queue.remove();
                    active++;
                    running.merge(key, 1, Integer::sum);
                    admitted.computeIfAbsent(document, ignored -> new HashSet<>()).add(job);
                    workers.execute(
                            () -> {
                                try {
                                    synchronized (this) { job.runner = Thread.currentThread(); }
                                    job.execute(config);
                                } finally {
                                    synchronized (this) {
                                        job.runner = null;
                                        Set<Job<?>> jobs = admitted.get(document);
                                        if (jobs != null) {
                                            jobs.remove(job);
                                            if (jobs.isEmpty()) admitted.remove(document);
                                        }
                                        active--;
                                        running.merge(key, -1, Integer::sum);
                                    }
                                }
                            });
                }
            } catch (Exception error) {
                queue.remove();
                job.future.completeExceptionally(error);
            }
            if (!queue.isEmpty()) groups.put(document, queue);
        }
    }

    public synchronized void cancelDocument(String document) {
        cancelled.add(document);
        cancelCurrentJobs(document);
        // 文件 ID 不复用。保留短期撤销标记覆盖删除事务提交前的调度竞争。
        if (!closed) dispatcher.schedule(() -> {
            synchronized (this) { cancelled.remove(document); }
        }, 10, TimeUnit.MINUTES);
    }

    /** Cancel the current generation while allowing a replacement generation to start at once. */
    public synchronized void resetDocument(String document) {
        cancelCurrentJobs(document);
    }

    private void cancelCurrentJobs(String document) {
        var queue = groups.remove(document);
        if (queue != null) queue.forEach(job -> job.future.cancel(true));
        // Copy because a cancelled worker may finish while this method is unwinding.
        for (Job<?> job : new ArrayList<>(admitted.getOrDefault(document, Set.of()))) {
            job.future.cancel(true);
            if (job.runner != null) job.runner.interrupt();
        }
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        dispatcher.shutdownNow();
        workers.shutdownNow();
        groups.values()
                .forEach(
                        q ->
                                q.forEach(
                                        j ->
                                                j.future.completeExceptionally(
                                                        new CancellationException("服务停止"))));
        groups.clear();
    }

    private static final class Job<T> {
        final String username;
        final Function<ModelConfigService.ResolvedModelConfig, T> call;
        final CompletableFuture<T> future;
        Thread runner;
        Job(String username, Function<ModelConfigService.ResolvedModelConfig, T> call, CompletableFuture<T> future) {
            this.username = username; this.call = call; this.future = future;
        }
        void execute(ModelConfigService.ResolvedModelConfig config) {
            try {
                if (!future.isCancelled()) future.complete(call.apply(config));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        }
    }
}
