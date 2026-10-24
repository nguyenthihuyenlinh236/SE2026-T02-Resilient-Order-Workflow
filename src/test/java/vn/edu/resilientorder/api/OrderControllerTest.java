package vn.edu.resilientorder.api;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.resilientorder.security.AccountRole;
import vn.edu.resilientorder.security.AuthenticatedUser;
import vn.edu.resilientorder.service.OrderService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderControllerTest {
    private OrderService orderService;
    private OrderController controller;
    private AuthenticatedUser customer;
    private CreateOrderRequest request;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        controller = new OrderController(orderService);
        customer = new AuthenticatedUser(UUID.randomUUID(), "customer", AccountRole.CUSTOMER, null);
        request = new CreateOrderRequest(
                List.of(new CreateOrderRequest.OrderLineRequest("SKU-1", 1)),
                "DEMO-STORE"
        );
    }

    @Test
    void rejectsMissingAndBlankIdempotencyKeysBeforeCallingService() {
        ResponseStatusException missing = assertThrows(
                ResponseStatusException.class,
                () -> controller.createOrder(null, request, customer)
        );
        ResponseStatusException blank = assertThrows(
                ResponseStatusException.class,
                () -> controller.createOrder(" \t", request, customer)
        );

        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatusCode());
        verify(orderService, never()).create(anyString(), any(), any());
    }

    @Test
    void rejectsIdempotencyKeyLongerThanDatabaseLimit() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.createOrder("k".repeat(201), request, customer)
        );
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(orderService, never()).create(anyString(), any(), any());
    }

    @Test
    void acceptsValidKeyAndReturnsStatusLocationAndRetryHint() {
        UUID orderId = UUID.randomUUID();
        OrderResponse response = new OrderResponse(
                orderId, "PENDING", null, "RUNNING", null, null, "DEMO-STORE",
                Instant.now(), Instant.now().plusSeconds(900),
                "/api/v1/orders/" + orderId, List.of()
        );
        when(orderService.create("request-1", request, customer)).thenReturn(response);

        var result = controller.createOrder("request-1", request, customer);

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertEquals(response.statusUrl(), result.getHeaders().getLocation().toString());
        assertEquals("1", result.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(response, result.getBody());
        verify(orderService).create("request-1", request, customer);
    }

    @Test
    void listsOrdersForCustomerWithRequestedLimit() {
        List<OrderResponse> orders = List.of();
        when(orderService.listForCustomer(customer.id(), 10)).thenReturn(orders);

        List<OrderResponse> result = controller.listOrders(customer, 10);

        assertEquals(orders, result);
        verify(orderService).listForCustomer(customer.id(), 10);
    }

    @Test
    void rejectsStaffOrderListRequestBeforeCallingService() {
        AuthenticatedUser staff = new AuthenticatedUser(
                UUID.randomUUID(), "staff", AccountRole.STAFF, "DEMO-STORE"
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.listOrders(staff, 20)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(orderService, never()).listForCustomer(any(), anyInt());
    }

    @Test
    void getsOrderByIdAndPassesRequestContextToService() {
        UUID orderId = UUID.randomUUID();
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        OrderResponse response = new OrderResponse(
                orderId, "PENDING", null, "RUNNING", null, null, "DEMO-STORE",
                Instant.now(), Instant.now().plusSeconds(900),
                "/api/v1/orders/" + orderId, List.of()
        );
        when(orderService.get(orderId, customer, servletRequest)).thenReturn(response);

        OrderResponse result = controller.getOrder(orderId, customer, servletRequest);

        assertEquals(response, result);
        verify(orderService).get(orderId, customer, servletRequest);
    }
}
