package com.luky.nexusmind.service;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Service
public class AiTraceService {

    private final boolean enabled;
    private final SdkTracerProvider tracerProvider;
    private final Tracer tracer;
    private final String environment;
    private final boolean captureContent;

    public AiTraceService(@Value("${langfuse.tracing.enabled:false}") boolean enabled,
                          @Value("${langfuse.tracing.base-url:https://cloud.langfuse.com}") String baseUrl,
                          @Value("${langfuse.tracing.public-key:}") String publicKey,
                          @Value("${langfuse.tracing.secret-key:}") String secretKey,
                          @Value("${langfuse.tracing.environment:dev}") String environment,
                          @Value("${langfuse.tracing.capture-content:false}") boolean captureContent) {
        this.enabled = enabled && hasText(publicKey) && hasText(secretKey);
        this.environment = environment;
        this.captureContent = captureContent;

        if (this.enabled) {
            String endpoint = normalizeBaseUrl(baseUrl) + "/api/public/otel/v1/traces";
            String auth = Base64.getEncoder()
                    .encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));

            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .addHeader("Authorization", "Basic " + auth)
                    .addHeader("x-langfuse-ingestion-version", "4")
                    .setTimeout(Duration.ofSeconds(10))
                    .build();

            this.tracerProvider = SdkTracerProvider.builder()
                    .setResource(Resource.getDefault().merge(Resource.builder()
                            .put(AttributeKey.stringKey("service.name"), "nexusmind")
                            .put(AttributeKey.stringKey("langfuse.environment"), environment)
                            .build()))
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();

            OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();
            this.tracer = openTelemetry.getTracer("com.luky.nexusmind.ai");
        } else {
            this.tracerProvider = null;
            this.tracer = OpenTelemetry.noop().getTracer("com.luky.nexusmind.ai");
        }
    }

    public TraceSpan startSpan(String name, String userId, String sessionId, String conversationId) {
        if (!enabled) {
            return TraceSpan.noop();
        }
        Span span = tracer.spanBuilder(name).startSpan();
        span.setAttribute("langfuse.trace.name", "nexusmind-rag-chat");
        span.setAttribute("langfuse.environment", environment);
        setIfPresent(span, "langfuse.user.id", userId);
        setIfPresent(span, "langfuse.session.id", conversationId);
        setIfPresent(span, "nexusmind.websocket.session_id", sessionId);
        setIfPresent(span, "nexusmind.conversation_id", conversationId);
        return new TraceSpan(span, span.makeCurrent(), true);
    }

    public TraceSpan startFileSpan(String name, String userId, String fileMd5, String fileName) {
        if (!enabled) {
            return TraceSpan.noop();
        }
        Span span = tracer.spanBuilder(name).startSpan();
        applyFileTraceAttributes(span, userId, fileMd5, fileName);
        return new TraceSpan(span, span.makeCurrent(), true);
    }

    public TraceSpan startFileSpanWithParent(String name, String traceparent, String userId, String fileMd5, String fileName) {
        if (!enabled) {
            return TraceSpan.noop();
        }
        var builder = tracer.spanBuilder(name);
        Context parentContext = contextFromTraceparent(traceparent);
        if (parentContext != null) {
            builder.setParent(parentContext);
        }
        Span span = builder.startSpan();
        applyFileTraceAttributes(span, userId, fileMd5, fileName);
        return new TraceSpan(span, span.makeCurrent(), true);
    }

    public boolean shouldCaptureContent() {
        return enabled && captureContent;
    }

    @PreDestroy
    public void shutdown() {
        if (tracerProvider != null) {
            tracerProvider.close();
        }
    }

    private static void setIfPresent(Span span, String key, String value) {
        if (hasText(value)) {
            span.setAttribute(key, value);
        }
    }

    private void applyFileTraceAttributes(Span span, String userId, String fileMd5, String fileName) {
        span.setAttribute("langfuse.trace.name", "nexusmind-file-ingestion");
        span.setAttribute("langfuse.environment", environment);
        setIfPresent(span, "langfuse.user.id", userId);
        setIfPresent(span, "langfuse.session.id", fileMd5);
        setIfPresent(span, "nexusmind.file.md5", fileMd5);
        setIfPresent(span, "nexusmind.file.name", fileName);
    }

    private Context contextFromTraceparent(String traceparent) {
        if (!hasText(traceparent)) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length != 4 || parts[1].length() != 32 || parts[2].length() != 16) {
            return null;
        }
        SpanContext spanContext = SpanContext.createFromRemoteParent(
                parts[1],
                parts[2],
                "01".equals(parts[3]) ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                TraceState.getDefault());
        if (!spanContext.isValid()) {
            return null;
        }
        return Context.root().with(Span.wrap(spanContext));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            return "https://cloud.langfuse.com";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public static class TraceSpan implements AutoCloseable {
        private static final TraceSpan NOOP = new TraceSpan(null, null, false);

        private final Span span;
        private final Scope scope;
        private final boolean enabled;
        private boolean ended;

        private TraceSpan(Span span, Scope scope, boolean enabled) {
            this.span = span;
            this.scope = scope;
            this.enabled = enabled;
        }

        public static TraceSpan noop() {
            return NOOP;
        }

        public TraceSpan attribute(String key, String value) {
            if (enabled && value != null) {
                span.setAttribute(key, value);
            }
            return this;
        }

        public TraceSpan attribute(String key, long value) {
            if (enabled) {
                span.setAttribute(key, value);
            }
            return this;
        }

        public TraceSpan attribute(String key, double value) {
            if (enabled) {
                span.setAttribute(key, value);
            }
            return this;
        }

        public TraceSpan attribute(String key, boolean value) {
            if (enabled) {
                span.setAttribute(key, value);
            }
            return this;
        }

        public void error(Throwable error) {
            if (enabled && error != null) {
                span.recordException(error);
                span.setStatus(StatusCode.ERROR, error.getMessage() != null ? error.getMessage() : "error");
            }
        }

        public void end() {
            if (enabled && !ended) {
                ended = true;
                span.end();
            }
        }

        public String traceparent() {
            if (!enabled) {
                return null;
            }
            SpanContext context = span.getSpanContext();
            if (!context.isValid()) {
                return null;
            }
            return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-"
                    + (context.getTraceFlags().isSampled() ? "01" : "00");
        }

        @Override
        public void close() {
            if (scope != null) {
                scope.close();
            }
        }
    }
}

