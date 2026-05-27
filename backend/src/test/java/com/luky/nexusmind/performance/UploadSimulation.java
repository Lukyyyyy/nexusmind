package com.luky.nexusmind.performance;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.RawFileBodyPart;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import java.time.Duration;
import java.util.UUID;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

public class UploadSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8081");
    private static final String USERNAME = System.getProperty("test.username", "testuser");
    private static final String PASSWORD = System.getProperty("test.password", "test123456");
    private static final String UPLOAD_RESOURCE = "gatling/upload-test-chunk.txt";
    private static final int CHUNK_COUNT = 10;
    private static final long CHUNK_SIZE = 5L * 1024 * 1024;
    private static final long UPLOAD_TOTAL_SIZE = CHUNK_COUNT * CHUNK_SIZE;
    private static final int SMOKE_USERS = Integer.getInteger("smoke.users", 1);
    private static final double UPLOAD_USERS_PER_SEC = Double.parseDouble(System.getProperty("upload.usersPerSec", "2"));
    private static final int HOLD_SECONDS = Integer.getInteger("hold.seconds", 120);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .userAgentHeader("NexusMind-Gatling-Upload-Test");

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
        String fileName = "gatling-upload-" + fileMd5 + ".txt";
        return session
                .set("fileMd5", fileMd5)
                .set("fileName", fileName);
    });

    private final ChainBuilder queryUploadStatus = exec(http("GET /api/v1/upload/status")
            .get("/api/v1/upload/status")
            .header("Authorization", session -> bearerToken(session))
            .queryParam("file_md5", "#{fileMd5}")
            .check(status().is(200))
            .check(jsonPath("$.code").is("200"))
            .check(jsonPath("$.data.progress").ofDouble().gte(100.0)));

    private final ChainBuilder uploadOnce = exec(prepareUploadData)
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
            .exec(queryUploadStatus);

    private final ScenarioBuilder smoke = scenario("upload-smoke")
            .exec(login)
            .exec(uploadOnce);

    private final ScenarioBuilder uploadQps = scenario("upload-chunk-qps")
            .exec(login)
            .exec(uploadOnce);

    {
        setUp(
                smoke.injectOpen(atOnceUsers(SMOKE_USERS)),
                uploadQps.injectOpen(
                        constantUsersPerSec(UPLOAD_USERS_PER_SEC)
                                .during(Duration.ofSeconds(HOLD_SECONDS))
                                .randomized()))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        global().responseTime().percentile3().lt(5000));
    }

    private static String bearerToken(Session session) {
        return "Bearer " + session.getString("token");
    }

    private static ChainBuilder uploadChunk(int chunkIndex) {
        return exec(http("POST /api/v1/upload/chunk [" + chunkIndex + "]")
                .post("/api/v1/upload/chunk")
                .header("Authorization", session -> bearerToken(session))
                .formParam("fileMd5", "#{fileMd5}")
                .formParam("chunkIndex", Integer.toString(chunkIndex))
                .formParam("totalSize", UPLOAD_TOTAL_SIZE)
                .formParam("fileName", "#{fileName}")
                .formParam("totalChunks", Integer.toString(CHUNK_COUNT))
                .formParam("isPublic", "false")
                .bodyPart(RawFileBodyPart("file", UPLOAD_RESOURCE)
                        .fileName(session -> session.getString("fileName") + ".part" + chunkIndex)
                        .contentType("text/plain"))
                .asMultipartForm()
                .check(status().is(200))
                .check(jsonPath("$.code").is("200")));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
