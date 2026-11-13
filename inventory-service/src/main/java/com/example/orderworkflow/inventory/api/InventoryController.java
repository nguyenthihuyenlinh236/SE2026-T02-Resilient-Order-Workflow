package com.example.orderworkflow.inventory.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import com.example.orderworkflow.inventory.service.InventoryService;
import com.example.orderworkflow.inventory.service.InventoryException;
import com.example.orderworkflow.inventory.service.InventoryLifecycleService;

@RestController
@RequestMapping({"/api/v1/inventory", "/internal/v1"})
public class InventoryController {
    private final InventoryService service;
    private final InventoryLifecycleService lifecycle;

    public InventoryController(InventoryService service, InventoryLifecycleService lifecycle) {
        this.service = service;
        this.lifecycle = lifecycle;
    }

    @PostMapping("/orders/{orderId}/cancel")
    public CancelResult cancel(@PathVariable String orderId, @Valid @RequestBody CancelRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return lifecycle.cancel(orderId, request, key);
    }

    @GetMapping("/operations")
    public OperationResult operation(@RequestParam String idempotencyKey) { return lifecycle.operation(idempotencyKey); }

    @PostMapping("/reservations")
    public Object reserve(@Valid @RequestBody ReserveRequest request,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest http) {
        var result = service.reserve(request, key);
        return internal(http) ? InventoryContractResponses.reserve(result) : result;
    }

    @GetMapping("/products/{productId}")
    public Stock stock(@PathVariable UUID productId) { return service.stock(productId); }

    @GetMapping("/stock")
    public Stock stockBySku(@RequestParam String storeId, @RequestParam String sku) {
        return service.stock(storeId, sku);
    }

    @GetMapping("/reservations/{reservationId}")
    public Reservation reservation(@PathVariable String reservationId) { return service.reservation(reservationId); }

    @GetMapping("/reservations/by-order/{orderId}")
    public Object reservationByOrder(@PathVariable String orderId, HttpServletRequest http) {
        var result = service.lookup(orderId, null);
        return internal(http) ? InventoryContractResponses.reservation(result) : result;
    }

    private static boolean internal(HttpServletRequest http) {
        return http.getRequestURI().substring(http.getContextPath().length()).startsWith("/internal/v1/");
    }

    @GetMapping("/reservations")
    public Reservation lookup(@RequestParam(required = false) String orderId,
            @RequestParam(required = false) String reserveKey,
            @RequestParam(required = false) String idempotencyKey) {
        if (reserveKey != null && idempotencyKey != null) {
            throw new InventoryException(400, "INVALID_REQUEST", "Use one key selector only");
        }
        return service.lookup(orderId, idempotencyKey != null ? idempotencyKey : reserveKey);
    }
}
