package com.luky.nexusmind.performance;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.asLongAsDuring;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.ByteArrayBodyPart;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

public class FullPipelineSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8081");
    private static final String USERNAME = System.getProperty("test.username", "testuser");
    private static final String PASSWORD = System.getProperty("test.password", "test123456");
    private static final int CHUNK_COUNT = 10;
    private static final int CHUNK_SIZE = 5 * 1024 * 1024;
    private static final long UPLOAD_TOTAL_SIZE = (long) CHUNK_COUNT * CHUNK_SIZE;
    private static final int POLL_INTERVAL_SECONDS = Integer.getInteger("pipeline.pollIntervalSeconds", 2);
    private static final int POLL_TIMEOUT_SECONDS = Integer.getInteger("pipeline.pollTimeoutSeconds", 1800);
    private static final byte[] CHUNK_BYTES = createSparseTextChunkBytes();

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .userAgentHeader("NexusMind-Gatling-Full-Pipeline-Test");

    private final ChainBuilder login = exec(http("POST /api/v1/users/login")
            .post("/api/v1/users/login")
            .header("Content-Type", "application/json")
            .body(StringBody(session -> """
                    {"username":"%s","password":"%s"}
                    """.formatted(escapeJson(USERNAME), escapeJson(PASSWORD))))
            .check(status().is(200))
            .check(jsonPath("$.data.token").saveAs("token")));

    private final ChainBuilder prepareUploadData = exec(session -> {
        String fileMd5 = UUID.randomUUID().toString().replace("-", "");
        String fileName = "gatling-full-pipeline-" + fileMd5 + ".txt";
        return session
                .set("fileMd5", fileMd5)
                .set("fileName", fileName)
                .set("processed", "false")
                .set("pipelineStartMillis", System.currentTimeMillis());
    });

    private final ChainBuilder mergeFile = exec(http("POST /api/v1/upload/merge")
            .post("/api/v1/upload/merge")
            .header("Authorization", session -> bearerToken(session))
            .header("Content-Type", "application/json")
            .body(StringBody(session -> """
                    {"fileMd5":"%s","fileName":"%s"}
                    """.formatted(escapeJson(session.getString("fileMd5")), escapeJson(session.getString("fileName")))))
            .check(status().is(200))
            .check(jsonPath("$.code").is("200")));

    private final ChainBuilder pollProcessingStatus = exec(http("GET /api/v1/upload/status/processing")
            .get("/api/v1/upload/status/processing")
            .header("Authorization", session -> bearerToken(session))
            .queryParam("file_md5", "#{fileMd5}")
            .check(status().is(200))
            .check(jsonPath("$.code").is("200"))
            .check(jsonPath("$.data.processed").saveAs("processed"))
            .check(jsonPath("$.data.uploadStatus").saveAs("uploadStatus"))
            .check(jsonPath("$.data.dbChunkCount").saveAs("dbChunkCount"))
            .check(jsonPath("$.data.esDocumentCount").saveAs("esDocumentCount")));

    private final ChainBuilder uploadAndProcessOnce = exec(prepareUploadData)
            .exec(uploadChunk(0))
            .exec(uploadChunk(1))
            .exec(uploadChunk(2))
            .exec(uploadChunk(3))
            .exec(uploadChunk(4))
            .exec(uploadChunk(5))
            .exec(uploadChunk(6))
            .exec(uploadChunk(7))
            .exec(uploadChunk(8))
            .exec(uploadChunk(9))
            .exec(mergeFile)
            .exec(pollProcessingStatus)
            .asLongAsDuring(
                    session -> !isProcessed(session),
                    Duration.ofSeconds(POLL_TIMEOUT_SECONDS))
            .on(pause(Duration.ofSeconds(POLL_INTERVAL_SECONDS)).exec(pollProcessingStatus))
            .exec(session -> {
                long elapsedMs = System.currentTimeMillis() - session.getLong("pipelineStartMillis");
                String processed = String.valueOf(session.get("processed"));
                String dbChunkCount = String.valueOf(session.get("dbChunkCount"));
                String esDocumentCount = String.valueOf(session.get("esDocumentCount"));
                String uploadStatus = String.valueOf(session.get("uploadStatus"));
                System.out.printf(
                        "FULL_PIPELINE_RESULT fileMd5=%s fileName=%s elapsedMs=%d uploadStatus=%s dbChunkCount=%s esDocumentCount=%s processed=%s%n",
                        session.getString("fileMd5"),
                        session.getString("fileName"),
                        elapsedMs,
                        uploadStatus,
                        dbChunkCount,
                        esDocumentCount,
                        processed);
                if (!isProcessed(session)) {
                    return session.markAsFailed();
                }
                return session;
            });

    private final ScenarioBuilder fullPipeline = scenario("upload-parse-vectorize-es-full-pipeline")
            .exec(login)
            .exec(uploadAndProcessOnce);

    {
        setUp(fullPipeline.injectOpen(atOnceUsers(1)))
                .protocols(httpProtocol)
                .maxDuration(Duration.ofSeconds(POLL_TIMEOUT_SECONDS + 600))
                .assertions(global().failedRequests().percent().lt(1.0));
    }

    private static ChainBuilder uploadChunk(int chunkIndex) {
        return exec(http("POST /api/v1/upload/chunk [" + chunkIndex + "]")
                .post("/api/v1/upload/chunk")
                .header("Authorization", session -> bearerToken(session))
                .formParam("fileMd5", "#{fileMd5}")
                .formParam("chunkIndex", Integer.toString(chunkIndex))
                .formParam("totalSize", Long.toString(UPLOAD_TOTAL_SIZE))
                .formParam("fileName", "#{fileName}")
                .formParam("totalChunks", Integer.toString(CHUNK_COUNT))
                .formParam("isPublic", "false")
                .bodyPart(ByteArrayBodyPart("file", CHUNK_BYTES)
                        .fileName(session -> session.getString("fileName") + ".part" + chunkIndex)
                        .contentType("text/plain"))
                .asMultipartForm()
                .check(status().is(200))
                .check(jsonPath("$.code").is("200")));
    }

    private static byte[] createSparseTextChunkBytes() {
        byte[] bytes = new byte[CHUNK_SIZE];
        Arrays.fill(bytes, (byte) '\n');
        byte[] paragraph = """
                NexusMind full pipeline performance test document.
                This paragraph is intentionally repeated sparsely so the physical upload size is 50MB, while the parsed text remains suitable for local vectorization timing.
                The system should upload chunks, merge the file, parse text, split document chunks, generate embeddings, save vectors to MySQL, and index documents into Elasticsearch.

                """.getBytes(StandardCharsets.UTF_8);

        for (int offset = 0; offset + paragraph.length < bytes.length; offset += 64 * 1024) {
            System.arraycopy(paragraph, 0, bytes, offset, paragraph.length);
        }
        return bytes;
    }

    private static boolean isProcessed(Session session) {
        return "true".equalsIgnoreCase(String.valueOf(session.get("processed")));
    }

    private static String bearerToken(Session session) {
        return "Bearer " + session.getString("token");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
