package com.example.orderworkflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OrderMigrationIntegrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void upgradesV001OrdersWithoutInventingRemoteEvidenceOrLosingData() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("1")).load().migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO orders(order_id,customer_id,client_id,store_id,total_amount,currency,"
                    + "reservation_id,correlation_id,created_at,updated_at,expire_at) VALUES "
                    + "('ORD-legacy','CUSTOMER-1','CLIENT-1','STORE-1',100.00,'VND','RES-legacy','corr',"
                    + "clock_timestamp()-interval '1 minute',clock_timestamp(),clock_timestamp()+interval '14 minutes')");
            statement.executeUpdate("INSERT INTO order_items(order_id,sku,quantity,unit_price) "
                    + "VALUES ('ORD-legacy','SKU-1',2,50.00)");
        }

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT status,reservation_status,reservation_verified,capture_status,"
                    + "capture_verified,refund_status,refund_verified FROM orders WHERE order_id='ORD-legacy'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("PENDING");
                assertThat(row.getString("reservation_status")).isEqualTo("NOT_CREATED");
                assertThat(row.getBoolean("reservation_verified")).isFalse();
                assertThat(row.getString("capture_status")).isEqualTo("NOT_STARTED");
                assertThat(row.getBoolean("capture_verified")).isFalse();
                assertThat(row.getString("refund_status")).isEqualTo("NOT_REQUIRED");
                assertThat(row.getBoolean("refund_verified")).isFalse();
            }
            try (var row = statement.executeQuery("SELECT s.status,st.status,st.request_payload,st.saga_payload "
                    + "FROM sagas s JOIN saga_steps st ON st.saga_id=s.saga_id WHERE s.order_id='ORD-legacy'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("RUNNING");
                assertThat(row.getString(2)).isEqualTo("NOT_STARTED");
                assertThat(row.getString(3)).doesNotContain("schemaVersion").contains("SKU-1");
                assertThat(row.getString(4)).contains("schemaVersion");
            }
            try (var row = statement.executeQuery("SELECT count(*) FROM order_items WHERE order_id='ORD-legacy'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isOne();
            }
        }
    }
}
