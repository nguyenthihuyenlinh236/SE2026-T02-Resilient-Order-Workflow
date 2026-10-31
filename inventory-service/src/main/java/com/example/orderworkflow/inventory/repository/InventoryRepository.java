package com.example.orderworkflow.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.orderworkflow.inventory.api.InventoryDtos.Item;
import com.example.orderworkflow.inventory.api.InventoryDtos.Reservation;
import com.example.orderworkflow.inventory.api.InventoryDtos.Stock;

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
                (rs, row) -> new Stock(rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                        rs.getInt("on_hand_quantity"), rs.getInt("reserved_quantity"),
                        rs.getInt("other_commitments"), rs.getInt("available")), productId).stream().findFirst();
    }

    public void allocate(Item item) {
        int changed = jdbc.update("UPDATE inventory SET reserved_quantity = reserved_quantity + ?, "
                + "updated_at = clock_timestamp() WHERE product_id = ? "
                + "AND on_hand_quantity - reserved_quantity - other_commitments >= ?",
                item.quantity(), item.productId(), item.quantity());
        if (changed != 1) { throw new IllegalStateException("Stock allocation lost its locked precondition"); }
    }

    public Optional<String> payloadHash(UUID orderId) {
        return jdbc.query("SELECT payload_hash FROM reservations WHERE order_id = ?",
                (rs, row) -> rs.getString(1), orderId).stream().findFirst();
    }

    public Reservation insert(UUID orderId, String key, String hash, List<Item> items, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservations(reservation_id, order_id, reserve_key, payload_hash, status, "
                        + "rejection_reason) VALUES (?, ?, ?, ?, ?, ?)",
                id, orderId, key, hash, reason == null ? "RESERVED" : "REJECTED", reason);
        for (Item item : items) {
            jdbc.update("INSERT INTO reservation_items(reservation_id, product_id, quantity) VALUES (?, ?, ?)",
                    id, item.productId(), item.quantity());
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
                        rs.getObject("order_id", UUID.class), rs.getString("reserve_key"),
                        rs.getString("status"), rs.getString("rejection_reason"), List.of(),
                        rs.getTimestamp("created_at").toInstant()), value);
        return rows.stream().findFirst().map(r -> new Reservation(r.reservationId(), r.orderId(), r.reserveKey(),
                r.status(), r.rejectionReason(), jdbc.query(
                        "SELECT product_id, quantity FROM reservation_items WHERE reservation_id = ? ORDER BY product_id",
                        (rs, row) -> new Item(rs.getObject("product_id", UUID.class), rs.getInt("quantity")),
                        r.reservationId()), r.createdAt()));
    }
}
