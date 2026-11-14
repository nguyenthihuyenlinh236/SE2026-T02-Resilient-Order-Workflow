package com.example.orderworkflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "management.health.rabbit.enabled=false"})
class OrderDraftLoadHarness {
    private static final int TASKS = 150;
    private static final String DRAFT_ID = "VT007-LOAD-DRAFT";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate http;

    @Test
    void runOneHundredFiftyConcurrentDraftSaves() throws Exception {
        List<Integer> revisions = new ArrayList<>();
        for (int revision = 1; revision <= TASKS; revision++) revisions.add(revision);
        Collections.shuffle(revisions, new java.util.Random(7007L));

        var ready = new CountDownLatch(TASKS);
        var start = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peakActive = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(TASKS);
        List<Future<Sample>> futures = new ArrayList<>();
        try {
            for (int revision : revisions) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    int current = active.incrementAndGet();
                    peakActive.accumulateAndGet(current, Math::max);
                    long began = System.nanoTime();
                    try {
                        ResponseEntity<Map> response = put(revision);
                        return new Sample(revision, response.getStatusCode().value(),
                                TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - began));
                    } catch (RuntimeException exception) {
                        return new Sample(revision, 0,
                                TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - began));
                    } finally {
                        active.decrementAndGet();
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            long loadBegan = System.nanoTime();
            start.countDown();
            List<Sample> samples = new ArrayList<>();
            for (Future<Sample> future : futures) samples.add(future.get(30, TimeUnit.SECONDS));
            long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - loadBegan);

            Summary summary = summarize(samples, elapsedMicros, peakActive.get());
            ResponseEntity<Map> finalDraft = get();
            assertThat(finalDraft.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(finalDraft.getBody()).containsEntry("revision", TASKS)
                    .containsEntry("customerId", "CLIENT-" + TASKS);
            assertThat(summary.serverErrors()).isZero();
            assertThat(summary.timeouts()).isZero();
            assertThat(summary.p95Millis()).isLessThanOrEqualTo(4_000.0);
            writeEvidence(samples, summary);
        } finally {
            pool.shutdownNow();
        }
    }

    private ResponseEntity<Map> put(int revision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revision", revision);
        body.put("customerId", "CLIENT-" + revision);
        body.put("storeId", null);
        body.put("items", List.of());
        return http.exchange("/api/v1/order-drafts/{draftId}", HttpMethod.PUT,
                new HttpEntity<>(body, headers()), Map.class, DRAFT_ID);
    }

    private ResponseEntity<Map> get() {
        return http.exchange("/api/v1/order-drafts/{draftId}", HttpMethod.GET,
                new HttpEntity<>(headers()), Map.class, DRAFT_ID);
    }

    private static HttpHeaders headers() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-Id", "VT007-LOAD-CLIENT");
        headers.set("X-Correlation-Id", "vt007-load");
        return headers;
    }

    private static Summary summarize(List<Sample> samples, long elapsedMicros, int peakActive) {
        List<Long> sorted = samples.stream().map(Sample::latencyMicros).sorted().toList();
        long p50 = sorted.get((int) Math.ceil(sorted.size() * .50) - 1);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * .95) - 1);
        long max = sorted.get(sorted.size() - 1);
        long successful = samples.stream().filter(value -> value.status() >= 200 && value.status() < 300).count();
        long conflicts = samples.stream().filter(value -> value.status() == 409).count();
        long serverErrors = samples.stream().filter(value -> value.status() >= 500).count();
        long timeouts = samples.stream().filter(value -> value.status() == 0).count();
        return new Summary(samples.size(), peakActive, successful, conflicts, serverErrors, timeouts,
                p50 / 1_000.0, p95 / 1_000.0, max / 1_000.0,
                samples.size() / (elapsedMicros / 1_000_000.0), TASKS, "CLIENT-" + TASKS);
    }

    private static void writeEvidence(List<Sample> samples, Summary summary) throws Exception {
        Path directory = Path.of(System.getProperty("vt007.load.outputDir", "target/vt007-load"));
        Files.createDirectories(directory);
        var csv = new StringBuilder("revision,status,latency_micros\n");
        samples.stream().sorted(java.util.Comparator.comparingInt(Sample::revision))
                .forEach(value -> csv.append(value.revision()).append(',').append(value.status())
                        .append(',').append(value.latencyMicros()).append('\n'));
        Files.writeString(directory.resolve("load-raw.csv"), csv, StandardCharsets.UTF_8);
        String json = """
                {
                  "measuredAt": "%s",
                  "tasks": %d,
                  "peakActive": %d,
                  "http2xx": %d,
                  "http409": %d,
                  "http5xx": %d,
                  "timeouts": %d,
                  "p50Millis": %.3f,
                  "p95Millis": %.3f,
                  "maxMillis": %.3f,
                  "throughputPerSecond": %.3f,
                  "finalRevision": %d,
                  "finalCustomerId": "%s"
                }
                """.formatted(Instant.now(), summary.tasks(), summary.peakActive(),
                summary.successful(), summary.conflicts(), summary.serverErrors(), summary.timeouts(),
                summary.p50Millis(), summary.p95Millis(), summary.maxMillis(), summary.throughput(),
                summary.finalRevision(), summary.finalCustomerId());
        Files.writeString(directory.resolve("load-summary.json"), json, StandardCharsets.UTF_8);
    }

    private record Sample(int revision, int status, long latencyMicros) { }
    private record Summary(int tasks, int peakActive, long successful, long conflicts,
            long serverErrors, long timeouts, double p50Millis, double p95Millis,
            double maxMillis, double throughput, int finalRevision, String finalCustomerId) { }
}
