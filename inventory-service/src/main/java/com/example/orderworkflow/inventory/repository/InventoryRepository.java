package com.example.orderworkflow.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.orderworkflow.inventory.api.InventoryDtos.Item;
import com.example.orderworkflow.inventory.api.InventoryDtos.Reservation;
import com.example.orderworkflow.inventory.api.InventoryDtos.Stock;
import com.example.orderworkflow.inventory.api.InventoryDtos.ReserveRequest;

@Repository
public class InventoryRepository {
    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockOrder(UUID orderId) {
        jdbc.update("INSERT INTO inventory_order_guards(order_id) VALUES (?) ON CONFLICT DO NOTHING", orderId);
        jdbc.queryForObject("SELECT order_id FROM inventory_order_guards WHERE order_id = ? FOR UPDATE",
                UUID.class, orderId);
    }

    public Optional<Stock> stock(UUID productId, boolean lock) {
        return jdbc.query("SELECT *, on_hand_quantity - reserved_quantity - other_commitments AS available "
                + "FROM inventory WHERE product_id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> mapStock(rs), productId).stream().findFirst();
    }

    public Optional<Stock> stock(String storeId, String sku, boolean lock) {
        return jdbc.query("SELECT *, on_hand_quantity - reserved_quantity - other_commitments AS available "
                        + "FROM inventory WHERE store_id = ? AND sku = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> mapStock(rs), storeId, sku).stream().findFirst();
    }

    private Stock mapStock(ResultSet rs) throws SQLException {
        return new Stock(rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("store_id"),
                rs.getString("product_name"), rs.getInt("on_hand_quantity"), rs.getInt("reserved_quantity"),
                rs.getInt("other_commitments"), rs.getInt("available"));
    }

    public void allocate(String storeId, Item item) {
        int changed = jdbc.update("UPDATE inventory SET reserved_quantity = reserved_quantity + ?, "
                + "updated_at = clock_timestamp() WHERE store_id = ? AND sku = ? "
                + "AND on_hand_quantity - reserved_quantity - other_commitments >= ?",
                item.quantity(), storeId, item.sku(), item.quantity());
        if (changed != 1) { throw new IllegalStateException("Stock allocation lost its locked precondition"); }
    }

    public Optional<String> payloadHash(UUID orderId) {
        return jdbc.query("SELECT payload_hash FROM reservations WHERE order_id = ?",
                (rs, row) -> rs.getString(1), orderId).stream().findFirst();
    }

    public boolean cancelled(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT cancellation_recorded FROM inventory_order_guards WHERE order_id = ?", Boolean.class, orderId));
    }

    public Instant cancelOrder(UUID orderId) {
        // Preserve the original cancellation instant regardless of which command won.
        return jdbc.queryForObject("UPDATE inventory_order_guards SET cancellation_recorded = TRUE, "
                        + "cancelled_at = COALESCE(cancelled_at, clock_timestamp()) WHERE order_id = ? RETURNING cancelled_at",
                (rs, row) -> rs.getTimestamp(1).toInstant(), orderId);
    }

    public Instant releasedAt(UUID reservationId) {
        return jdbc.queryForObject("SELECT released_at FROM reservations WHERE reservation_id = ?",
                (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), reservationId);
    }

    public Instant release(Reservation reservation) {
        var items = reservation.items().stream().sorted(java.util.Comparator.comparing(Item::sku)).toList();
        for (Item item : items) {
            stock(reservation.storeId(), item.sku(), true).orElseThrow(() ->
                    new IllegalStateException("Reserved inventory row is missing"));
        }
        int changed = jdbc.update("UPDATE reservations SET status = 'RELEASED', released_at = clock_timestamp(), "
                        + "updated_at = clock_timestamp() WHERE reservation_id = ? AND status = 'RESERVED'",
                reservation.reservationId());
        if (changed != 1) { throw new IllegalStateException("Reservation state changed under Order lock"); }
        for (Item item : items) {
            int returned = jdbc.update("UPDATE inventory SET reserved_quantity = reserved_quantity - ?, "
                            + "updated_at = clock_timestamp() WHERE store_id = ? AND sku = ? AND reserved_quantity >= ?",
                    item.quantity(), reservation.storeId(), item.sku(), item.quantity());
            if (returned != 1) { throw new IllegalStateException("Reserved quantity is inconsistent"); }
        }
        return releasedAt(reservation.reservationId());
    }

    public Reservation insert(ReserveRequest request, String key, String hash, List<Item> items, String reason) {
        UUID id = request.reservationId();
        jdbc.update("INSERT INTO reservations(reservation_id, order_id, reserve_key, payload_hash, status, "
                        + "rejection_reason, store_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, request.orderId(), key, hash, reason == null ? "RESERVED" : "REJECTED", reason, request.storeId());
        for (Item item : items) {
            jdbc.update("INSERT INTO reservation_items(reservation_id, sku, product_id, quantity) "
                            + "VALUES (?, ?, (SELECT product_id FROM inventory WHERE store_id = ? AND sku = ?), ?)",
                    id, item.sku(), request.storeId(), item.sku(), item.quantity());
        }
        return byId(id).orElseThrow();
    }

    public Optional<Reservation> byId(UUID id) { return find("reservation_id", id); }
    public Optional<Reservation> byOrder(UUID id) { return find("order_id", id); }
    public Optional<Reservation> byKey(String key) { return find("reserve_key", key); }

    private Optional<Reservation> find(String column, Object value) {
        // column comes only from the three fixed methods above, never from HTTP input.
        var rows = jdbc.query("SELECT * FROM reservations WHERE " + column + " = ?",
                (rs, row) -> new Reservation(rs.getObject("reservation_id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getString("store_id"), rs.getString("reserve_key"),
                        rs.getString("status"), rs.getString("rejection_reason"), List.of(),
                        rs.getTimestamp("created_at").toInstant()), value);
        return rows.stream().findFirst().map(r -> new Reservation(r.reservationId(), r.orderId(), r.storeId(), r.reserveKey(),
                r.status(), r.rejectionReason(), jdbc.query(
                        "SELECT sku, quantity FROM reservation_items WHERE reservation_id = ? ORDER BY sku",
                        (rs, row) -> new Item(rs.getString("sku"), rs.getInt("quantity")),
                        r.reservationId()), r.createdAt()));
    }
}
