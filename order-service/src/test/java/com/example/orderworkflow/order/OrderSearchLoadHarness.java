package com.example.orderworkflow.order;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in only: run via scripts/vt005-load.ps1; not part of the default test suite. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never", "management.health.rabbit.enabled=false",
        "debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
class OrderSearchLoadHarness {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    record Sample(int worker, int request, double milliseconds, int status, boolean valid, String error) { }

    @Test
    void measuresSearchWith120ConcurrentTasks() throws Exception {
        jdbc.execute("""
                INSERT INTO orders(order_id, customer_id, client_id, store_id, status, total_amount,
                    currency, reservation_id, correlation_id, created_at, updated_at, expire_at)
                SELECT 'LOAD-' || lpad(i::text, 6, '0'), 'CUSTOMER-' || (i % 100), 'CLIENT-LOAD',
                    'STORE-001', CASE WHEN i % 2 = 0 THEN 'PENDING' ELSE 'CONFIRMED' END,
                    100.00, 'VND', 'RES-LOAD-' || i, 'corr-load-' || i,
                    TIMESTAMPTZ '2026-11-01 10:00:00+00' + (i / 10) * INTERVAL '1 second',
                    TIMESTAMPTZ '2026-11-01 10:00:00+00', TIMESTAMPTZ '2026-11-02 10:00:00+00'
                FROM generate_series(1, 10000) AS i
                """);
        var queries = List.of("?page=0&size=20", "?customerId=CUSTOMER-2&page=1&size=20",
                "?status=PENDING&page=2&size=20", "?customerId=CUSTOMER-2&status=PENDING&size=20");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        for (int i = 0; i < 20; i++) {
            assertThat(sample(client, queries.get(i % queries.size()), -1, i).valid()).isTrue();
        }
        var executor = Executors.newFixedThreadPool(120);
        var ready = new CountDownLatch(120);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<List<Sample>>>();
        List<Sample> samples = new ArrayList<>();
        long started;
        double seconds;
        try {
            for (int worker = 0; worker < 120; worker++) {
                final int workerId = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("Start gate timeout"); }
                    var results = new ArrayList<Sample>();
                    for (int request = 0; request < 5; request++) {
                        results.add(sample(client, queries.get((workerId + request) % queries.size()), workerId, request));
                    }
                    return results;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            started = System.nanoTime();
            start.countDown();
            for (var future : futures) { samples.addAll(future.get(150, TimeUnit.SECONDS)); }
            seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        var latencies = samples.stream().mapToDouble(Sample::milliseconds).sorted().toArray();
        long errors = samples.stream().filter(sample -> !sample.valid()).count();
        var result = new LinkedHashMap<String, Object>();
        result.put("recordedAt", Instant.now().toString());
        result.put("concurrentTasks", 120);
        result.put("requestsPerTask", 5);
        result.put("warmupRequestsExcluded", 20);
        result.put("seedRows", 10000);
        result.put("queries", queries);
        result.put("requests", samples.size());
        result.put("errors", errors);
        result.put("errorRate", (double) errors / samples.size());
        result.put("elapsedSeconds", seconds);
        result.put("requestsPerSecond", samples.size() / seconds);
        result.put("p50Ms", percentile(latencies, 0.50));
        result.put("p95Ms", percentile(latencies, 0.95));
        result.put("p99Ms", percentile(latencies, 0.99));
        result.put("p95ThresholdMs", 3500);
        result.put("p95WithinThreshold", percentile(latencies, 0.95) <= 3500);
        result.put("percentileMethod", "nearest rank, all measured requests including errors");
        result.put("java", System.getProperty("java.version"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("postgresVersion", jdbc.queryForObject("SHOW server_version", String.class));
        result.put("samples", samples);
        Path output = Path.of("target", "vt005-load-results.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        assertThat(samples).hasSize(600);
        assertThat(errors).isZero();
        assertThat(percentile(latencies, 0.95)).isLessThanOrEqualTo(3500);
    }

    private Sample sample(HttpClient client, String query, int worker, int requestNumber) {
        long started = System.nanoTime();
        int status = 0;
        boolean valid = false;
        String error = null;
        try {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders" + query))
                    .timeout(Duration.ofSeconds(20)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            JsonNode body = mapper.readTree(response.body());
            long expectedTotal = query.contains("customerId=") ? 100 : query.contains("status=") ? 5000 : 10000;
            valid = status == 200 && body.path("items").isArray() && body.path("items").size() == 20
                    && body.path("totalElements").asLong() == expectedTotal;
            if (!valid) { error = "Unexpected HTTP status or page metadata"; }
        } catch (Exception exception) {
            error = exception.getClass().getSimpleName();
            if (exception instanceof InterruptedException) { Thread.currentThread().interrupt(); }
        }
        return new Sample(worker, requestNumber, (System.nanoTime() - started) / 1_000_000.0, status, valid, error);
    }

    private static double percentile(double[] sorted, double fraction) {
        return sorted[(int) Math.ceil(sorted.length * fraction) - 1];
    }
}
