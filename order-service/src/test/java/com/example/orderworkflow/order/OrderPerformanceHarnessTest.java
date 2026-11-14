package com.example.orderworkflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orderworkflow.order.api.OrderDtos.OrderResponse;

/** Executable one-burst, 60-task POST-acceptance + GET-status performance harness. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never", "management.health.rabbit.enabled=false",
        "order.ttl=15m", "order.worker.enabled=false"})
class OrderPerformanceHarnessTest {
    private static final int USERS = 60;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @org.springframework.test.context.DynamicPropertySource
    static void properties(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE saga_attempts,saga_steps,sagas,outbox_events,order_items,idempotency_records,orders,product_catalog");
        jdbc.update("INSERT INTO product_catalog(store_id,sku,product_name,unit_price,currency,active) "
                + "VALUES ('STORE-001','SKU-001','Product 1',125000.00,'VND',true)");
    }

    @Test
    void sixtyConcurrentTasksMeasurePostAcceptanceAndGetStatus() throws Exception {
        var ready = new CountDownLatch(USERS);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(USERS);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Sample>>(USERS);
            for (int i = 0; i < USERS; i++) {
                final int user = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) return Sample.failed("start gate timed out");
                    return runUser(user);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all 60 tasks ready").isTrue();
            long began = System.nanoTime();
            start.countDown();
            var samples = new ArrayList<Sample>(USERS);
            for (var future : futures) samples.add(future.get(60, TimeUnit.SECONDS));
            long elapsedNanos = System.nanoTime() - began;

            var post = samples.stream().map(Sample::postMillis).toList();
            var get = samples.stream().filter(s -> s.getMillis() >= 0).map(Sample::getMillis).toList();
            var combined = new ArrayList<Long>(post);
            combined.addAll(get);
            int errors = samples.stream().mapToInt(Sample::errors).sum();
            int successes = samples.stream().mapToInt(Sample::successes).sum();
            int requests = USERS + get.size();
            double seconds = elapsedNanos / 1_000_000_000.0;
            System.out.printf("PERF_RESULT users=%d tasks=%d requests=%d successes=%d errors=%d elapsed_ms=%.2f "
                            + "post_p50_ms=%.2f post_p95_ms=%.2f post_p99_ms=%.2f "
                            + "get_p50_ms=%.2f get_p95_ms=%.2f get_p99_ms=%.2f "
                            + "combined_p50_ms=%.2f combined_p95_ms=%.2f combined_p99_ms=%.2f "
                            + "throughput_rps=%.2f worker_enabled=false order_completion_measured=false%n",
                    USERS, USERS, requests, successes, errors, seconds * 1000,
                    percentile(post, .50), percentile(post, .95), percentile(post, .99),
                    percentile(get, .50), percentile(get, .95), percentile(get, .99),
                    percentile(combined, .50), percentile(combined, .95), percentile(combined, .99),
                    successes / seconds);
            var errorDetails = samples.stream().map(Sample::error).filter(java.util.Objects::nonNull).limit(5).toList();
            System.out.println("PERF_ERRORS " + errorDetails);

            assertThat(samples).hasSize(USERS);
            assertThat(errors).isZero();
            assertThat(successes).isEqualTo(USERS * 2);
            assertThat(percentile(post, .95)).isLessThanOrEqualTo(2000.0);
            assertThat(percentile(get, .95)).isLessThanOrEqualTo(2000.0);
            assertThat(percentile(combined, .95)).isLessThanOrEqualTo(2000.0);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private Sample runUser(int user) {
        long postStarted = System.nanoTime();
        try {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Client-Id", "PERF-CLIENT");
            headers.set("Idempotency-Key", "perf-order-" + user);
            headers.set("X-Correlation-Id", "perf-corr-" + user);
            String body = "{\"customerId\":\"CUSTOMER-001\",\"storeId\":\"STORE-001\","
                    + "\"items\":[{\"sku\":\"SKU-001\",\"quantity\":1}]}";
            var post = http.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, headers),
                    OrderResponse.class);
            long postMillis = elapsedMillis(postStarted);
            if (post.getStatusCode() != HttpStatus.ACCEPTED || post.getBody() == null) {
                return new Sample(postMillis, -1, 0, 1, "POST status=" + post.getStatusCode());
            }
            long getStarted = System.nanoTime();
            var get = http.getForEntity("/api/v1/orders/" + post.getBody().orderId(), OrderResponse.class);
            long getMillis = elapsedMillis(getStarted);
            if (get.getStatusCode() != HttpStatus.OK || get.getBody() == null
                    || !post.getBody().orderId().equals(get.getBody().orderId())) {
                return new Sample(postMillis, getMillis, 1, 1, "GET did not return the accepted Order");
            }
            return new Sample(postMillis, getMillis, 2, 0, null);
        } catch (Exception failure) {
            return new Sample(elapsedMillis(postStarted), -1, 0, 1,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static double percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return Double.NaN;
        var sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private record Sample(long postMillis, long getMillis, int successes, int errors, String error) {
        static Sample failed(String reason) { return new Sample(0, -1, 0, 1, reason); }
    }
}
