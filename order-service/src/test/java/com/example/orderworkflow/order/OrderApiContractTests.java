package com.example.orderworkflow.order;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "management.health.rabbit.enabled=false", "order.ttl=15m"})
class OrderApiContractTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() { OrderSearchFixtures.reset(jdbc); }

    @Test
    @DisplayName("T09: preserve POST JSON, 202, original snapshot, conflict and technical-client scope")
    void t09CreateAndIdempotencyContractRemainsCompatible() {
        var first = create("CLIENT-A", "contract-create", 2);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertOrderJson(first);
        var original = first.getBody();
        assertThat(original.get("status").asText()).isEqualTo("PENDING");
        assertThat(Duration.between(Instant.parse(original.get("createdAt").asText()),
                Instant.parse(original.get("expireAt").asText()))).isEqualTo(Duration.ofMinutes(15));
        jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE order_id = ?", original.get("orderId").asText());
        var replay = create("CLIENT-A", "contract-create", 2);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody()).isEqualTo(original);
        var conflict = create("CLIENT-A", "contract-create", 3);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertErrorJson(conflict, "IDEMPOTENCY_KEY_CONFLICT");
        var otherClient = create("CLIENT-B", "contract-create", 2);
        assertThat(otherClient.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(otherClient.getBody().get("orderId").asText()).isNotEqualTo(original.get("orderId").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("T10: preserve GET-by-ID JSON, current state and 400/404 error contracts")
    void t10GetByIdContractRemainsCompatible() {
        var created = create("CLIENT-A", "contract-get", 1);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String orderId = created.getBody().get("orderId").asText();
        var found = http.getForEntity("/api/v1/orders/{id}", JsonNode.class, orderId);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertOrderJson(found);
        assertThat(found.getBody()).isEqualTo(created.getBody());
        jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE order_id = ?", orderId);
        var current = http.getForEntity("/api/v1/orders/{id}", JsonNode.class, orderId);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertOrderJson(current);
        assertThat(current.getBody().get("status").asText()).isEqualTo("CONFIRMED");
        var missing = http.getForEntity("/api/v1/orders/ORD-MISSING", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertErrorJson(missing, "ORDER_NOT_FOUND");
        var invalid = http.getForEntity("/api/v1/orders/{id}", JsonNode.class, "bad id");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorJson(invalid, "INVALID_IDENTIFIER");
    }

    private ResponseEntity<JsonNode> create(String client, String key, int quantity) {
        return http.exchange("/api/v1/orders", HttpMethod.POST,
                OrderSearchFixtures.createRequest(client, key, quantity), JsonNode.class);
    }

    private static void assertOrderJson(ResponseEntity<JsonNode> response) {
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        var json = response.getBody();
        assertThat(json.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("orderId", "status", "createdAt", "expireAt", "correlationId");
        for (String field : java.util.List.of("orderId", "status", "createdAt", "expireAt", "correlationId")) {
            assertThat(json.path(field).isTextual()).as("JSON string %s", field).isTrue();
        }
        assertThat(Instant.parse(json.get("expireAt").asText())).isAfter(Instant.parse(json.get("createdAt").asText()));
    }

    private static void assertErrorJson(ResponseEntity<JsonNode> response, String code) {
        var error = response.getBody().path("error");
        assertThat(error.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("code", "message", "details", "correlationId", "retryable");
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("message").isTextual()).isTrue();
        assertThat(error.path("details").isArray()).isTrue();
        assertThat(error.path("correlationId").isTextual()).isTrue();
        assertThat(error.path("retryable").isBoolean()).isTrue();
        assertThat(error.path("retryable").asBoolean()).isFalse();
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(error.get("correlationId").asText());
    }
}
