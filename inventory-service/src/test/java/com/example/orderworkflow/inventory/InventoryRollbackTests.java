package com.example.orderworkflow.inventory;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orderworkflow.inventory.api.InventoryDtos.Item;
import com.example.orderworkflow.inventory.api.InventoryDtos.ReserveRequest;
import com.example.orderworkflow.inventory.service.InventoryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL transaction test; never connects to the developer's demo DB. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=true",
        "spring.sql.init.mode=never",
        "management.health.rabbit.enabled=false"
})
class InventoryRollbackTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("inventory_rollback_test")
            .withUsername("inventory_test")
            .withPassword("inventory_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    InventoryService service;

    @Autowired
    JdbcTemplate jdbc;

    // Deliberately no @Transactional on the test: the real service proxy must
    // roll back its own transaction before the assertions query committed data.
    @Test
    void itemInsertFailureRollsBackAllStockAndReservationWritesThenAllowsRetry() {
        UUID firstProduct = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID secondProduct = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID orderId = UUID.randomUUID();
        String key = "reserve:" + orderId + ":v1";
        var request = new ReserveRequest(orderId, UUID.randomUUID(), "TEST-STORE",
                List.of(new Item("A", 2), new Item("B", 3)));

        for (UUID product : List.of(firstProduct, secondProduct)) {
            jdbc.update("INSERT INTO inventory(product_id, sku, store_id, product_name, on_hand_quantity) "
                            + "VALUES (?, ?, 'TEST-STORE', ?, 10)",
                    product, product.equals(firstProduct) ? "A" : "B", "Rollback test product");
        }

        // The second item fails AFTER both stock updates, the Reservation header
        // and the first item insert. This constraint exists only in this container.
        jdbc.execute("ALTER TABLE reservation_items ADD CONSTRAINT test_reject_quantity_three CHECK (quantity <> 3)");
        try {
            assertThatThrownBy(() -> service.reserve(request, key))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("test_reject_quantity_three");

            for (UUID product : List.of(firstProduct, secondProduct)) {
                var stock = service.stock(product);
                assertThat(stock.onHandQuantity()).isEqualTo(10);
                assertThat(stock.reservedQuantity()).isZero();
                assertThat(stock.availableQuantity()).isEqualTo(10);
            }
            assertThat(count("reservations")).isZero();
            assertThat(count("reservation_items")).isZero();
            assertThat(count("inventory_order_guards")).isZero();
            assertThat(count("inventory_operations")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE reservation_items DROP CONSTRAINT test_reject_quantity_three");
        }

        // A rolled-back command must not poison its Order/key or consume stock.
        var result = service.reserve(request, key);
        assertThat(result.status()).isEqualTo("RESERVED");
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.items()).hasSize(2);
        assertThat(service.stock(firstProduct).reservedQuantity()).isEqualTo(2);
        assertThat(service.stock(secondProduct).reservedQuantity()).isEqualTo(3);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(count("reservation_items")).isEqualTo(2);
        assertThat(count("inventory_order_guards")).isEqualTo(1);
        assertThat(service.lookup(orderId, null).reservationId()).isEqualTo(result.reservationId());
    }

    private long count(String table) {
        // Table names are fixed test constants, never user-supplied values.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
