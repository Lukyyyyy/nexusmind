package com.luky.nexusmind.client;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;

public final class GenerationCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Sinks.Empty<Void> signal = Sinks.empty();

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) signal.tryEmitEmpty();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    Mono<Void> signal() {
        return signal.asMono();
    }
}
