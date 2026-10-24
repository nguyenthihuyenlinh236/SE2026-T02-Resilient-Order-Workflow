package vn.edu.resilientorder.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import vn.edu.resilientorder.security.AuthenticatedUser;
import vn.edu.resilientorder.service.OrderService;

import java.net.URI;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    ResponseEntity<OrderResponse> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required.");
        }
        if (idempotencyKey.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be at most 200 characters.");
        }

        OrderResponse response = orderService.create(idempotencyKey, request, user);
        return ResponseEntity.accepted()
                .location(URI.create(response.statusUrl()))
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(response);
    }

    @GetMapping("/{orderId}")
    OrderResponse getOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request
    ) {
        return orderService.get(orderId, user, request);
    }

    @GetMapping
    java.util.List<OrderResponse> listOrders(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "20") @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(50) int limit
    ) {
        if (user.role() != vn.edu.resilientorder.security.AccountRole.CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer account required.");
        }
        return orderService.listForCustomer(user.id(), limit);
    }
}
