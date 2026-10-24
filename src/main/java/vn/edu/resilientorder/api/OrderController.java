package vn.edu.resilientorder.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.resilientorder.security.AccountRole;
import vn.edu.resilientorder.security.AuthenticatedUser;
import vn.edu.resilientorder.service.OrderService;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/orders")
public class OrderController {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

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
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
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
    List<OrderResponse> listOrders(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        if (user.role() != AccountRole.CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer account required.");
        }
        return orderService.listForCustomer(user.id(), limit);
    }
}
