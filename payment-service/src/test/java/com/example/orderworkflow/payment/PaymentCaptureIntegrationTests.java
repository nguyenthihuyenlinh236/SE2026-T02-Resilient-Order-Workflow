package com.example.orderworkflow.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orderworkflow.payment.api.PaymentDtos.CaptureResponse;
import com.example.orderworkflow.payment.api.PaymentDtos.ErrorResponse;

@Testcontainers
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.health.rabbit.enabled=false")
class PaymentCaptureIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db")
            .withUsername("payment")
            .withPassword("payment");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearCaptures() {
        jdbc.update("TRUNCATE TABLE captures");
    }

    @Test
    void capturesSuccessfullyAndPersistsAuthoritativeResult() {
        var response = capture("ORDER-001", "250000.00", "VND", "cmd-001", "corr-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("SUCCEEDED");
        assertThat(response.getBody().amount()).isEqualTo("250000.00");
        assertThat(response.getBody().currency()).isEqualTo("VND");
        assertThat(response.getBody().capturedAt()).isNotNull();
        assertThat(count()).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM captures WHERE order_id = 'ORDER-001'", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void sameKeyAndPayloadReplaysTheOriginalCapture() {
        var first = capture("ORDER-002", "100.00", "VND", "cmd-002", "corr-002");
        var replay = capture("ORDER-002", "100.00", "VND", "cmd-002", "corr-002");

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(count()).isOne();
    }

    @Test
    void sameKeyWithDifferentPayloadReturnsConflict() {
        capture("ORDER-003", "100.00", "VND", "cmd-003", "corr-003");

        var response = captureError("ORDER-003", "101.00", "VND", "cmd-003", "corr-003",
                key("ORDER-003"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(count()).isOne();
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneCapture() throws Exception {
        int requests = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<CaptureResponse>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    var response = capture("ORDER-004", "900.00", "VND", "cmd-004", "corr-004");
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    return response.getBody();
                }));
            }
            start.countDown();
            Set<String> paymentIds = new java.util.HashSet<>();
            for (Future<CaptureResponse> future : futures) {
                paymentIds.add(future.get().paymentId());
            }
            assertThat(paymentIds).hasSize(1);
            assertThat(count()).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentKeyForTheSameOrderIsRejectedBeforeCreatingAnotherCapture() {
        capture("ORDER-005", "100.00", "VND", "cmd-005", "corr-005");

        var response = captureError("ORDER-005", "100.00", "VND", "cmd-005", "corr-005",
                "payment:capture:ORDER-005:v2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(count()).isOne();
    }

    @Test
    void rejectsInvalidMoneyAndCurrency() {
        List<Map<String, String>> invalid = List.of(
                Map.of("amount", "0", "currency", "VND"),
                Map.of("amount", "-1.00", "currency", "VND"),
                Map.of("amount", "1.001", "currency", "VND"),
                Map.of("amount", "1234567890123.00", "currency", "VND"),
                Map.of("amount", "100.00", "currency", "USD"));

        int sequence = 0;
        for (Map<String, String> value : invalid) {
            String order = "INVALID-" + sequence++;
            var response = captureError(order, value.get("amount"), value.get("currency"),
                    "cmd-invalid", "corr-invalid", key(order));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(count()).isZero();
    }

    @Test
    void rejectsJsonNumberForAmount() {
        String orderId = "ORDER-NUMBER";
        var headers = headers(key(orderId));
        String body = """
                {"commandId":"cmd-number","orderId":"ORDER-NUMBER","amount":100.00,
                 "currency":"VND","correlationId":"corr-number"}
                """;

        var response = http.exchange("/internal/v1/payments/captures", HttpMethod.POST,
                new HttpEntity<>(body, headers), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count()).isZero();
    }

    @Test
    void queriesCaptureByPaymentIdAndIdempotencyKey() {
        var created = capture("ORDER-006", "700.00", "VND", "cmd-006", "corr-006").getBody();
        assertThat(created).isNotNull();

        var byId = http.getForEntity("/internal/v1/payments/{paymentId}", CaptureResponse.class,
                created.paymentId());
        var byKey = http.getForEntity("/internal/v1/payments/by-idempotency-key/{key}",
                CaptureResponse.class, created.idempotencyKey());

        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody()).isEqualTo(created);
        assertThat(byKey.getBody()).isEqualTo(created);
    }

    private org.springframework.http.ResponseEntity<CaptureResponse> capture(String orderId, String amount,
            String currency, String commandId, String correlationId) {
        return http.exchange("/internal/v1/payments/captures", HttpMethod.POST,
                new HttpEntity<>(request(orderId, amount, currency, commandId, correlationId), headers(key(orderId))),
                CaptureResponse.class);
    }

    private org.springframework.http.ResponseEntity<ErrorResponse> captureError(String orderId, String amount,
            String currency, String commandId, String correlationId, String key) {
        return http.exchange("/internal/v1/payments/captures", HttpMethod.POST,
                new HttpEntity<>(request(orderId, amount, currency, commandId, correlationId), headers(key)),
                ErrorResponse.class);
    }

    private static Map<String, String> request(String orderId, String amount, String currency,
            String commandId, String correlationId) {
        return Map.of("commandId", commandId, "orderId", orderId, "amount", amount,
                "currency", currency, "correlationId", correlationId);
    }

    private static HttpHeaders headers(String key) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Correlation-Id", "corr-test");
        return headers;
    }

    private static String key(String orderId) {
        return "payment:capture:" + orderId + ":v1";
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM captures", Integer.class);
    }
}
