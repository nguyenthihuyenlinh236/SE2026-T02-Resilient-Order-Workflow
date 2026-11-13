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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
class OrderServiceTests {
    @Mock OrderRepository repository;
    OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(repository, new ObjectMapper().registerModule(new JavaTimeModule()),
                Duration.ofMinutes(15));
    }

    @Test
    void combinesDuplicateSkusBeforePricingAndPersistence() {
        var request = new CreateOrderRequest("CUSTOMER-001", "STORE-001",
                List.of(new ItemRequest("SKU-001", 1), new ItemRequest("SKU-001", 2)));
        when(repository.claimIdempotencyKey(eq("key-1"), anyString(), anyString())).thenReturn(true);
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
        when(repository.claimIdempotencyKey(eq("key-2"), anyString(), anyString())).thenReturn(false);
        when(repository.findIdempotency("key-2"))
                .thenReturn(Optional.of(new IdempotencyRecord("different-hash", "ORD-existing", "{}")));

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

    @Test
    void canonicalPayloadAndHashAreStableForEquivalentItemOrders() {
        var first = new CreateOrderRequest("CUSTOMER-001", "STORE-001", List.of(
                new ItemRequest("SKU-002", 1), new ItemRequest("SKU-001", 1),
                new ItemRequest("SKU-001", 2)));
        var equivalent = new CreateOrderRequest("CUSTOMER-001", "STORE-001", List.of(
                new ItemRequest("SKU-001", 3), new ItemRequest("SKU-002", 1)));
        when(repository.claimIdempotencyKey(eq("stable-1"), anyString(), anyString())).thenReturn(true);
        when(repository.claimIdempotencyKey(eq("stable-2"), anyString(), anyString())).thenReturn(true);
        when(repository.lockCatalog(eq("STORE-001"), any())).thenReturn(List.of(
                new CatalogProduct("SKU-001", new BigDecimal("10.00"), "VND", true),
                new CatalogProduct("SKU-002", new BigDecimal("20.00"), "VND", true)));
        when(repository.databaseTime()).thenReturn(Instant.parse("2026-11-13T10:00:00Z"));

        service.create(first, "stable-1", "corr-1");
        service.create(equivalent, "stable-2", "corr-2");

        var hash = ArgumentCaptor.forClass(String.class);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(repository).claimIdempotencyKey(eq("stable-1"), hash.capture(), payload.capture());
        String firstHash = hash.getValue();
        String firstPayload = payload.getValue();
        verify(repository).claimIdempotencyKey(eq("stable-2"), hash.capture(), payload.capture());
        assertThat(payload.getValue()).isEqualTo(firstPayload).isEqualTo(
                "{\"customerId\":\"CUSTOMER-001\",\"storeId\":\"STORE-001\","
                + "\"items\":[{\"sku\":\"SKU-001\",\"quantity\":3},"
                + "{\"sku\":\"SKU-002\",\"quantity\":1}]}");
        assertThat(hash.getValue()).isEqualTo(firstHash)
                .isEqualTo("f810cddc04c0a957e2d0fe7c5877c06d0adaec1549851459386c39980b9cd83c");
    }
}
