package com.example.orderworkflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

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

import com.example.orderworkflow.order.api.OrderDtos.OrderResponse;

@Testcontainers
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.health.rabbit.enabled=false")
class OrderDemoProfileTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;

    @Test
    void demoStartsWithFlywaySchemaAndUsableCatalogSeed() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history WHERE success
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM product_catalog WHERE store_id = 'STORE-001'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT active FROM product_catalog WHERE store_id = 'STORE-001' AND sku = 'SKU-INACTIVE'
                """, Boolean.class)).isFalse();

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-Id", "DEMO-CLIENT");
        headers.set("Idempotency-Key", "demo-profile-startup");
        headers.set("X-Correlation-Id", "demo-profile-test");
        var body = Map.of("customerId", "DEMO-CUSTOMER", "storeId", "STORE-001",
                "items", new Object[]{Map.of("sku", "SKU-001", "quantity", 1)});

        var response = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, headers), OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("PENDING");
    }
}
