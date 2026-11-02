package com.example.orderworkflow.inventory.api;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import com.example.orderworkflow.inventory.service.InventoryService;
import com.example.orderworkflow.inventory.service.InventoryException;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {
    private final InventoryService service;

    public InventoryController(InventoryService service) { this.service = service; }

    @PostMapping("/reservations")
    public Reservation reserve(@Valid @RequestBody ReserveRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return service.reserve(request, key);
    }

    @GetMapping("/products/{productId}")
    public Stock stock(@PathVariable UUID productId) { return service.stock(productId); }

    @GetMapping("/stock")
    public Stock stockBySku(@RequestParam String storeId, @RequestParam String sku) {
        return service.stock(storeId, sku);
    }

    @GetMapping("/reservations/{reservationId}")
    public Reservation reservation(@PathVariable UUID reservationId) { return service.reservation(reservationId); }

    @GetMapping("/reservations")
    public Reservation lookup(@RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String reserveKey,
            @RequestParam(required = false) String idempotencyKey) {
        if (reserveKey != null && idempotencyKey != null) {
            throw new InventoryException(400, "INVALID_REQUEST", "Use one key selector only");
        }
        return service.lookup(orderId, idempotencyKey != null ? idempotencyKey : reserveKey);
    }
}
