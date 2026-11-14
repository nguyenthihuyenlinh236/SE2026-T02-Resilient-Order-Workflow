package com.example.orderworkflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orderworkflow.order.api.OrderDtos.CreateOrderRequest;
import com.example.orderworkflow.order.api.OrderDtos.ItemRequest;
import com.example.orderworkflow.order.repository.SagaRepository;
import com.example.orderworkflow.order.service.OrderService;

@Testcontainers
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "management.health.rabbit.enabled=false", "order.worker.enabled=false"})
class SagaRepositoryIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SagaRepository saga;
    @Autowired OrderService orders;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE saga_attempts,saga_steps,sagas,outbox_events,order_items,idempotency_records,orders,product_catalog");
        jdbc.update("INSERT INTO product_catalog(store_id,sku,product_name,unit_price,currency,active) "
                + "VALUES ('STORE-001','SKU-001','Product 1',125000.00,'VND',true)");
    }

    @Test
    void claimsDueStepForSingleOwnerAndFencesStaleToken() throws Exception {
        orders.create(new CreateOrderRequest("CUSTOMER-001", "STORE-001",
                List.of(new ItemRequest("SKU-001", 1))), "CLIENT-001", "claim-test", "corr-claim");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return saga.claimReserve(30_000); });
            var second = pool.submit(() -> { start.await(); return saga.claimReserve(30_000); });
            start.countDown();
            var a = first.get();
            var b = second.get();
            assertThat(List.of(a, b).stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
            var owner = a.orElseGet(b::orElseThrow);
            assertThat(owner.claimToken()).isNotNull();

            jdbc.update("UPDATE saga_steps SET claimed_at=clock_timestamp()-interval '2 seconds',"
                    + "lease_until=clock_timestamp()-interval '1 second' WHERE step_id=?",
                    owner.stepId());
            var takeover = saga.claimReserve(30_000).orElseThrow();
            assertThat(takeover.claimToken()).isNotEqualTo(owner.claimToken());
            assertThat(saga.finishReserveClaim(owner, "SUCCEEDED", "NONE", "{}", null, 1000)).isZero();
            assertThat(saga.finishReserveClaim(takeover, "UNKNOWN", "UNKNOWN", "{}", "timeout", 1000)).isOne();
            assertThat(jdbc.queryForObject("SELECT status FROM saga_steps WHERE step_id=?", String.class,
                    owner.stepId())).isEqualTo("UNKNOWN");
        } finally {
            pool.shutdownNow();
        }
    }
}
