package com.example.orderworkflow.inventory.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import com.example.orderworkflow.inventory.api.InventoryContractResponses.ReleaseResponse;
import com.example.orderworkflow.inventory.service.InventoryLifecycleService;

@RestController
public class InventoryReleaseController {
    private final InventoryLifecycleService lifecycle;

    public InventoryReleaseController(InventoryLifecycleService lifecycle) { this.lifecycle = lifecycle; }

    @PostMapping("/internal/v1/reservations/{reservationId}/release")
    public ReleaseResponse release(@PathVariable String reservationId,
            @Valid @RequestBody InternalReleaseRequest request, @RequestHeader("Idempotency-Key") String key) {
        var result = lifecycle.releaseInternal(reservationId, request, key);
        return new ReleaseResponse(result.reservationId(), result.orderId(), result.status(), result.releasedAt());
    }

    @PostMapping("/api/v1/inventory/reservations/{reservationId}/release")
    public ReleaseResult legacyRelease(@PathVariable String reservationId,
            @Valid @RequestBody ReleaseRequest request, @RequestHeader("Idempotency-Key") String key) {
        return lifecycle.release(reservationId, request, key);
    }
}
