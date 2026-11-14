package com.example.orderworkflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "management.health.rabbit.enabled=false"})
class OrderDraftIntegrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetDrafts() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('public.order_drafts') IS NOT NULL", Boolean.class);
        if (Boolean.TRUE.equals(exists)) {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_draft_write_trigger ON order_drafts");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_draft_write()");
            jdbc.execute("TRUNCATE order_drafts");
        }
    }

    @Test
    void firstSaveAcceptsPartialDraftAndGetReturnsSnapshot() {
        var body = draft(1, null, null, List.of());
        var saved = put("CLIENT-1", "DRAFT-1", body);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).containsEntry("outcome", "CREATED").containsEntry("revision", 1);
        var found = get("CLIENT-1", "DRAFT-1");
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).containsEntry("revision", 1).containsEntry("items", List.of());
        assertThat(found.getBody()).containsEntry("customerId", null).containsEntry("storeId", null);
    }

    @Test
    void higherRevisionAndRevisionGapReplaceSnapshot() {
        assertThat(put("CLIENT-1", "DRAFT-2", draft(1, "C-1", null, List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var updated = put("CLIENT-1", "DRAFT-2", draft(9, "C-9", "STORE-9", List.of(item("B", 1))));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).containsEntry("outcome", "UPDATED").containsEntry("revision", 9);
        assertThat(get("CLIENT-1", "DRAFT-2").getBody()).containsEntry("customerId", "C-9");
    }

    @Test
    void staleRevisionReturnsBusinessConflictWithoutOverwrite() {
        assertThat(put("CLIENT-1", "DRAFT-3", draft(5, "NEW", null, List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var stale = put("CLIENT-1", "DRAFT-3", draft(4, "OLD", null, List.of()));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(stale)).isEqualTo("STALE_DRAFT_REVISION");
        assertThat(get("CLIENT-1", "DRAFT-3").getBody())
                .containsEntry("revision", 5).containsEntry("customerId", "NEW");
    }

    @Test
    void sameRevisionSameSnapshotReplaysWithoutChangingSavedAt() {
        var request = draft(3, "C-1", "S-1", List.of(item("SKU-1", 2)));
        var first = put("CLIENT-1", "DRAFT-4", request);
        var replay = put("CLIENT-1", "DRAFT-4", request);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).containsEntry("outcome", "REPLAYED");
        assertThat(replay.getBody().get("savedAt")).isEqualTo(first.getBody().get("savedAt"));
    }

    @Test
    void sameRevisionDifferentSnapshotIsConflict() {
        assertThat(put("CLIENT-1", "DRAFT-5", draft(2, "A", null, List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var conflict = put("CLIENT-1", "DRAFT-5", draft(2, "B", null, List.of()));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(conflict)).isEqualTo("DRAFT_REVISION_CONFLICT");
        assertThat(get("CLIENT-1", "DRAFT-5").getBody()).containsEntry("customerId", "A");
    }

    @Test
    void snapshotPreservesItemOrderAndDuplicates() {
        var items = List.of(item("SKU-B", 1), item("SKU-A", 2), item("SKU-A", 3));
        assertThat(put("CLIENT-1", "DRAFT-6", draft(1, null, null, items)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("CLIENT-1", "DRAFT-6").getBody().get("items")).isEqualTo(items);
    }

    @Test
    void sameDraftIdIsIsolatedByDemoClientScope() {
        assertThat(put("CLIENT-A", "SHARED", draft(1, "A", null, List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(put("CLIENT-B", "SHARED", draft(1, "B", null, List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("CLIENT-A", "SHARED").getBody()).containsEntry("customerId", "A");
        assertThat(get("CLIENT-B", "SHARED").getBody()).containsEntry("customerId", "B");
    }

    @Test
    void invalidRevisionAndMissingDraftReturnContractErrors() {
        var invalid = put("CLIENT-1", "DRAFT-7", draft(0, null, null, List.of()));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("CLIENT-1", "MISSING").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void concurrentSavesRetainHighestRevisionWithoutServerErrors() throws Exception {
        int requests = 20;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (int revision = 1; revision <= requests; revision++) {
                int value = revision;
                futures.add(pool.submit(() -> {
                    start.await();
                    return put("CLIENT-1", "DRAFT-RACE", draft(value, "C-" + value, null, List.of()));
                }));
            }
            start.countDown();
            for (var future : futures) {
                var status = future.get().getStatusCode();
                assertThat(status.is2xxSuccessful() || status == HttpStatus.CONFLICT).isTrue();
                assertThat(status.is5xxServerError()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(get("CLIENT-1", "DRAFT-RACE").getBody())
                .containsEntry("revision", requests).containsEntry("customerId", "C-20");
    }

    @Test
    void databaseFailureDoesNotPartiallyReplaceDraft() {
        assertThat(put("CLIENT-1", "DRAFT-FAIL", draft(1, "SAFE", null, List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        jdbc.execute("CREATE FUNCTION fail_draft_write() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'injected draft failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_draft_write_trigger BEFORE UPDATE ON order_drafts "
                + "FOR EACH ROW EXECUTE FUNCTION fail_draft_write()");
        assertThat(put("CLIENT-1", "DRAFT-FAIL", draft(2, "UNSAFE", null, List.of()))
                .getStatusCode().is5xxServerError()).isTrue();
        jdbc.execute("DROP TRIGGER fail_draft_write_trigger ON order_drafts");
        jdbc.execute("DROP FUNCTION fail_draft_write()");
        assertThat(get("CLIENT-1", "DRAFT-FAIL").getBody())
                .containsEntry("revision", 1).containsEntry("customerId", "SAFE");
    }

    private ResponseEntity<Map> put(String clientId, String draftId, Map<String, Object> body) {
        return http.exchange("/api/v1/order-drafts/{draftId}", HttpMethod.PUT,
                new HttpEntity<>(body, headers(clientId)), Map.class, draftId);
    }

    private ResponseEntity<Map> get(String clientId, String draftId) {
        return http.exchange("/api/v1/order-drafts/{draftId}", HttpMethod.GET,
                new HttpEntity<>(headers(clientId)), Map.class, draftId);
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(ResponseEntity<Map> response) {
        return (String) ((Map<String, Object>) response.getBody().get("error")).get("code");
    }

    private static HttpHeaders headers(String clientId) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-Id", clientId);
        headers.set("X-Correlation-Id", "corr-vt007");
        return headers;
    }

    private static Map<String, Object> draft(int revision, String customerId, String storeId,
            List<Map<String, Object>> items) {
        var body = new LinkedHashMap<String, Object>();
        body.put("revision", revision);
        body.put("customerId", customerId);
        body.put("storeId", storeId);
        body.put("items", items);
        return body;
    }

    private static Map<String, Object> item(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }
}
