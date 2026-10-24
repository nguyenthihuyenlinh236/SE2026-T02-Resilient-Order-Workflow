package vn.edu.resilientorder.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.resilientorder.security.AuthenticatedUser;
import vn.edu.resilientorder.service.StaffOrderService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/staff/orders")
public class StaffOrderController {
    private final StaffOrderService staffOrderService;

    public StaffOrderController(StaffOrderService staffOrderService) {
        this.staffOrderService = staffOrderService;
    }

    @GetMapping
    List<OrderResponse> listOrders(@AuthenticationPrincipal AuthenticatedUser staff) {
        return staffOrderService.listForStore(staff);
    }

    @PostMapping("/{orderId}/claim")
    OrderResponse claim(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal AuthenticatedUser staff,
            HttpServletRequest request
    ) {
        return staffOrderService.claim(orderId, staff, request);
    }

    @PatchMapping("/{orderId}/fulfillment")
    OrderResponse updateFulfillment(
            @PathVariable UUID orderId,
            @Valid @RequestBody FulfillmentUpdateRequest update,
            @AuthenticationPrincipal AuthenticatedUser staff,
            HttpServletRequest request
    ) {
        return staffOrderService.updateFulfillment(orderId, update.status(), staff, request);
    }

    public record FulfillmentUpdateRequest(@NotNull FulfillmentStatus status) {
    }

    public enum FulfillmentStatus {
        PREPARING,
        DELIVERED
    }
}
