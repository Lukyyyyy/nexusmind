package com.luky.nexusmind.performance;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import java.time.Duration;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

public class AuthSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8081");
    private static final String USERNAME = System.getProperty("test.username", "testuser");
    private static final String PASSWORD = System.getProperty("test.password", "test123456");
    private static final int SMOKE_USERS = Integer.getInteger("smoke.users", 1);
    private static final double LOGIN_USERS_PER_SEC = Double.parseDouble(System.getProperty("login.usersPerSec", "10"));
    private static final int REFRESH_CONCURRENT_USERS = Integer.getInteger("refresh.concurrentUsers", 10);
    private static final int HOLD_SECONDS = Integer.getInteger("hold.seconds", 120);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("NexusMind-Gatling-QPS-Test");

    private final ChainBuilder login = exec(http("POST /api/v1/users/login")
            .post("/api/v1/users/login")
            .body(StringBody(session -> """
                    {"username":"%s","password":"%s"}
                    """.formatted(escapeJson(USERNAME), escapeJson(PASSWORD))))
            .check(status().is(200))
            .check(jsonPath("$.data.token").saveAs("token"))
            .check(jsonPath("$.data.refreshToken").saveAs("refreshToken")));

    private final ChainBuilder refreshToken = exec(http("POST /api/v1/auth/refreshToken")
            .post("/api/v1/auth/refreshToken")
            .body(StringBody(session -> """
                    {"refreshToken":"%s"}
                    """.formatted(session.getString("refreshToken"))))
            .check(status().is(200))
            .check(jsonPath("$.data.token").saveAs("token"))
            .check(jsonPath("$.data.refreshToken").saveAs("refreshToken")));

    private final ScenarioBuilder smoke = scenario("auth-smoke")
            .exec(login)
            .exec(refreshToken);

    private final ScenarioBuilder loginQps = scenario("login-qps")
            .exec(login);

    private final ScenarioBuilder refreshTokenQps = scenario("refresh-token-qps")
            .exec(login)
            .during(Duration.ofSeconds(HOLD_SECONDS)).on(
                    pace(Duration.ofSeconds(1))
                            .exec(refreshToken));

    {
        setUp(
                smoke.injectOpen(atOnceUsers(SMOKE_USERS)),
                loginQps.injectOpen(
                        constantUsersPerSec(LOGIN_USERS_PER_SEC)
                                .during(Duration.ofSeconds(HOLD_SECONDS))
                                .randomized()),
                refreshTokenQps.injectOpen(atOnceUsers(REFRESH_CONCURRENT_USERS)))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        global().responseTime().percentile3().lt(1000));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
