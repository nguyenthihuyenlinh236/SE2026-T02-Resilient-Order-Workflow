package com.example.orderworkflow.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.orderworkflow.order.api.OrderDtos.CreateOrderRequest;
import com.example.orderworkflow.order.api.OrderDtos.ItemRequest;
import com.example.orderworkflow.order.repository.OrderRecords.CatalogProduct;
import com.example.orderworkflow.order.repository.OrderRecords.IdempotencyRecord;
import com.example.orderworkflow.order.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceTests {
    @Mock OrderRepository repository;
    OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(repository, Duration.ofMinutes(15));
    }

    @Test
    void combinesDuplicateSkusBeforePricingAndPersistence() {
        var request = new CreateOrderRequest("CUSTOMER-001", "STORE-001",
                List.of(new ItemRequest("SKU-001", 1), new ItemRequest("SKU-001", 2)));
        when(repository.claimIdempotencyKey(eq("key-1"), anyString())).thenReturn(true);
        when(repository.lockCatalog("STORE-001", List.of("SKU-001")))
                .thenReturn(List.of(new CatalogProduct("SKU-001", new BigDecimal("10.00"), "VND", true)));
        when(repository.databaseTime()).thenReturn(Instant.parse("2026-11-13T10:00:00Z"));

        var response = service.create(request, "key-1", "corr-1");

        assertThat(response.status()).isEqualTo("PENDING");
        var quantities = ArgumentCaptor.<Map<String, Integer>>captor();
        verify(repository).insertItems(eq(response.orderId()), quantities.capture(), any());
        assertThat(quantities.getValue()).containsExactly(Map.entry("SKU-001", 3));
        verify(repository).insertOrder(eq(response.orderId()), eq("CUSTOMER-001"), eq("STORE-001"),
                eq(new BigDecimal("30.00")), eq("VND"), anyString(), eq("corr-1"),
                eq(response.createdAt()), eq(response.expireAt()));
    }

    @Test
    void conflictingStoredHashReturnsContractConflict() {
        var request = new CreateOrderRequest("CUSTOMER-001", "STORE-001",
                List.of(new ItemRequest("SKU-001", 1)));
        when(repository.claimIdempotencyKey(eq("key-2"), anyString())).thenReturn(false);
        when(repository.findIdempotency("key-2"))
                .thenReturn(Optional.of(new IdempotencyRecord("different-hash", "ORD-existing")));

        assertThatThrownBy(() -> service.create(request, "key-2", null))
                .isInstanceOfSatisfying(OrderException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
                });
    }

    @Test
    void rejectsInvalidOrderIdentifierBeforeQueryingDatabase() {
        assertThatThrownBy(() -> service.get("bad id"))
                .isInstanceOfSatisfying(OrderException.class, error -> {
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.code()).isEqualTo("INVALID_IDENTIFIER");
                });
    }
}
