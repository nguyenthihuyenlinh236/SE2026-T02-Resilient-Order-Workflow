package com.example.orderworkflow.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
        "spring.flyway.enabled=true", "spring.sql.init.mode=never", "management.health.rabbit.enabled=false"})
class OrderSearchIntegrationTests {
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
    @DisplayName("T01: filter exactly by customerId")
    void t01FiltersByCustomer() {
        var page = successfulPage("?customerId=CUSTOMER-A");
        assertThat(ids(page)).containsExactly("ORD-008", "ORD-006", "ORD-005", "ORD-003", "ORD-001");
        assertThat(page.get("totalElements").asLong()).isEqualTo(5);
    }

    @ParameterizedTest
    @CsvSource({"PENDING,ORD-008", "CONFIRMED,ORD-006", "COMPENSATING,ORD-003",
            "COMPENSATION_FAILED,ORD-002", "EXPIRING,ORD-004", "FAILED,ORD-001", "EXPIRED,ORD-000"})
    @DisplayName("T02: filter by every persisted Order status")
    void t02FiltersByStatus(String status, String firstId) {
        var page = successfulPage("?status=" + status);
        for (JsonNode item : page.get("items")) { assertThat(item.get("status").asText()).isEqualTo(status); }
        assertThat(ids(page)).isNotEmpty().startsWith(firstId);
        if (status.equals("PENDING")) {
            assertThat(ids(page)).containsExactly("ORD-008", "ORD-007", "ORD-005");
            assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        } else {
            assertThat(ids(page)).containsExactly(firstId);
            assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("T03: customerId AND status, not OR")
    void t03CombinesFilters() {
        var page = successfulPage("?customerId=CUSTOMER-A&status=PENDING");
        assertThat(ids(page)).containsExactly("ORD-008", "ORD-005");
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("T04: defaults page=0 and size=20 apply at the database boundary")
    void t04UsesDefaultPagination() {
        for (int i = 0; i < 25; i++) {
            OrderSearchFixtures.insert(jdbc, "ORD-EXTRA-" + String.format("%03d", i), "CUSTOMER-A", "PENDING",
                    OrderSearchFixtures.BASE_TIME.plusSeconds(100 + i));
        }
        var page = successfulPage("");
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("items").size()).isEqualTo(20);
        assertThat(page.get("totalElements").asLong()).isEqualTo(34);
        assertThat(page.get("totalPages").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("T05: fixed dataset traverses pages without gaps or duplicates")
    void t05TraversesPagesWithoutMissingOrDuplicateRows() {
        var allIds = new ArrayList<String>();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            var page = successfulPage("?page=" + pageNumber + "&size=4");
            assertThat(page.get("page").asInt()).isEqualTo(pageNumber);
            assertThat(page.get("size").asInt()).isEqualTo(4);
            assertThat(page.get("totalElements").asLong()).isEqualTo(9);
            assertThat(page.get("totalPages").asLong()).isEqualTo(3);
            assertThat(page.get("items").size()).isEqualTo(pageNumber == 2 ? 1 : 4);
            allIds.addAll(ids(page));
        }
        assertThat(allIds).containsExactlyElementsOf(OrderSearchFixtures.ORDERED_IDS).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("T06: timestamp ties use order_id DESC, including across page boundaries")
    void t06UsesDescendingIdAsStableTieBreaker() {
        for (int repetition = 0; repetition < 3; repetition++) {
            assertThat(ids(successfulPage("?page=0&size=1"))).containsExactly("ORD-008");
            assertThat(ids(successfulPage("?page=1&size=1"))).containsExactly("ORD-007");
            assertThat(ids(successfulPage("?page=2&size=1"))).containsExactly("ORD-006");
            assertThat(ids(successfulPage("?page=3&size=1"))).containsExactly("ORD-005");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=abc", "size=abc",
            "page=2147483648", "size=2147483648"})
    @DisplayName("T07: invalid pagination returns 400 using the error contract")
    void t07RejectsInvalidPagination(String query) {
        var before = OrderSearchFixtures.snapshot(jdbc);
        var response = get("?" + query);
        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(OrderSearchFixtures.snapshot(jdbc)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "NOT_A_STATUS", "pending", ""})
    @DisplayName("T08: status is a case-sensitive Order status, not a Payment/Saga status")
    void t08RejectsInvalidStatus(String status) {
        var response = get("?status=" + status);
        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("T11: a PostgreSQL query failure must not become 200 with an empty page")
    void t11DatabaseFailureMustNotBecomeSuccessfulEmptyPage() {
        // This prerequisite prevents a missing route returning 500 from falsely passing T11 in RED.
        assertThat(ids(successfulPage("?customerId=CUSTOMER-A"))).isNotEmpty();
        jdbc.execute("ALTER TABLE orders RENAME TO vt005_orders_unavailable");
        try {
            var failed = get("?customerId=CUSTOMER-A");
            assertError(failed, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR");
            assertThat(failed.getBody().has("items")).isFalse();
            assertThat(failed.getBody().path("error").path("message").asText())
                    .doesNotContain("vt005_orders_unavailable", "SELECT", "relation");
        } finally {
            jdbc.execute("ALTER TABLE vt005_orders_unavailable RENAME TO orders");
        }
        assertThat(ids(successfulPage("?customerId=CUSTOMER-A")))
                .containsExactly("ORD-008", "ORD-006", "ORD-005", "ORD-003", "ORD-001");
    }

    @Test
    @DisplayName("T12: a valid filter matching nothing returns a successful empty page")
    void t12ReturnsEmptyPageWhenNothingMatches() {
        var page = successfulPage("?customerId=CUSTOMER-MISSING&status=PENDING");
        assertThat(ids(page)).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
        assertThat(page.get("totalPages").asLong()).isZero();
    }

    @Test
    @DisplayName("T12b: beyond the last page preserves the filtered total")
    void t12bPageBeyondEndIsEmpty() {
        var page = successfulPage("?customerId=CUSTOMER-A&page=10&size=2");
        assertThat(ids(page)).isEmpty();
        assertThat(page.get("page").asInt()).isEqualTo(10);
        assertThat(page.get("totalElements").asLong()).isEqualTo(5);
        assertThat(page.get("totalPages").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("T13: searching leaves all persisted business data unchanged")
    void t13SearchDoesNotChangeData() {
        var created = http.exchange("/api/v1/orders", HttpMethod.POST,
                OrderSearchFixtures.createRequest("CLIENT-VT005", "readonly-contract", 1), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var before = OrderSearchFixtures.snapshot(jdbc);
        successfulPage("?customerId=CUSTOMER-A&status=PENDING&page=0&size=2");
        assertThat(OrderSearchFixtures.snapshot(jdbc)).isEqualTo(before);
    }

    private ResponseEntity<JsonNode> get(String query) {
        return http.getForEntity("/api/v1/orders" + query, JsonNode.class);
    }

    private JsonNode successfulPage(String query) {
        var response = get(query);
        assertThat(response.getStatusCode()).as("GET /api/v1/orders%s", query).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull().satisfies(
                type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
        JsonNode page = response.getBody();
        assertThat(page).isNotNull();
        assertThat(page.isObject()).isTrue();
        assertThat(page.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("items", "page", "size", "totalElements", "totalPages");
        assertThat(page.path("items").isArray()).isTrue();
        for (String field : List.of("page", "size", "totalElements", "totalPages")) {
            assertThat(page.path(field).isIntegralNumber()).as("type of %s", field).isTrue();
        }
        for (JsonNode item : page.get("items")) {
            assertThat(item.properties()).extracting(java.util.Map.Entry::getKey)
                    .containsExactlyInAnyOrder("orderId", "status", "createdAt", "expireAt", "correlationId");
            for (String field : List.of("orderId", "status", "createdAt", "expireAt", "correlationId")) {
                assertThat(item.path(field).isTextual()).as("type of item.%s", field).isTrue();
            }
            assertThat(Instant.parse(item.get("expireAt").asText())).isAfter(Instant.parse(item.get("createdAt").asText()));
        }
        return page;
    }

    private static List<String> ids(JsonNode page) {
        return StreamSupport.stream(page.get("items").spliterator(), false)
                .map(item -> item.get("orderId").asText()).toList();
    }

    private static void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        var error = response.getBody().path("error");
        assertThat(error.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("code", "message", "details", "correlationId", "retryable");
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("message").isTextual()).isTrue();
        assertThat(error.path("details").isArray()).isTrue();
        assertThat(error.path("correlationId").isTextual()).isTrue();
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(error.get("correlationId").asText());
        assertThat(error.path("retryable").isBoolean()).isTrue();
        if (status.is4xxClientError()) { assertThat(error.get("retryable").asBoolean()).isFalse(); }
    }
}
