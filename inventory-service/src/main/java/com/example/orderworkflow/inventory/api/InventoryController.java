package com.example.orderworkflow.inventory.api;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import com.example.orderworkflow.inventory.service.InventoryService;

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

    @GetMapping("/reservations/{reservationId}")
    public Reservation reservation(@PathVariable UUID reservationId) { return service.reservation(reservationId); }

    @GetMapping("/reservations")
    public Reservation lookup(@RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String reserveKey) { return service.lookup(orderId, reserveKey); }
}
