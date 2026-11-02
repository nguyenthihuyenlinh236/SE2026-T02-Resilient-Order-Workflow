package com.example.orderworkflow.inventory.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.orderworkflow.inventory.api.InventoryDtos.Item;
import com.example.orderworkflow.inventory.api.InventoryDtos.ReserveRequest;
import com.example.orderworkflow.inventory.repository.InventoryRepository;
import com.example.orderworkflow.inventory.repository.InventoryOperations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryServiceTests {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad!id", "has space", "_leading", "-leading", "khóa", "a\n"})
    void invalidIdentifiersCannotReachRepository(String id) {
        var repository = mock(InventoryRepository.class);
        var operations = mock(InventoryOperations.class);
        var service = new InventoryService(repository, operations);
        assertThatThrownBy(() -> service.reserve(new ReserveRequest(id, "RES-001", "STORE", List.of(new Item("A", 1))),
                "reserve:" + id + ":v1")).isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.reserve(new ReserveRequest("ORD-001", id, "STORE", List.of(new Item("A", 1))),
                "reserve:ORD-001:v1")).isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.reservation(id)).isInstanceOf(InventoryException.class);
        if (id != null) {
            assertThatThrownBy(() -> service.lookup(id, null)).isInstanceOf(InventoryException.class);
        }
        verifyNoInteractions(repository, operations);
    }

    @Test
    void normalizesDuplicateSkusInStableOrder() {
        assertThat(InventoryService.normalize(List.of(new Item("B", 1), new Item("A", 2), new Item("B", 3))))
                .containsExactly(new Item("A", 2), new Item("B", 4));
    }

    static Stream<List<Item>> invalidItems() {
        return Stream.of(null, List.of(), Arrays.asList((Item) null), List.of(new Item(null, 1)),
                List.of(new Item(" ", 1)), List.of(new Item("A", null)), List.of(new Item("A", 0)),
                List.of(new Item("A", -1)), List.of(new Item("X".repeat(129), 1)),
                java.util.Collections.nCopies(21, new Item("A", 1)),
                List.of(new Item("A", Integer.MAX_VALUE), new Item("A", 1)));
    }

    @ParameterizedTest
    @MethodSource("invalidItems")
    void rejectsInvalidItems(List<Item> items) {
        assertThatThrownBy(() -> InventoryService.normalize(items)).isInstanceOfSatisfying(
                InventoryException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void invalidCommandDoesNotTouchRepository() {
        var repository = mock(InventoryRepository.class);
        var service = new InventoryService(repository, mock(InventoryOperations.class));
        String order = UUID.randomUUID().toString();
        assertThatThrownBy(() -> service.reserve(new ReserveRequest(order, null, "STORE",
                List.of(new Item("A", 1))), "reserve:" + order + ":v1"))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.reserve(new ReserveRequest(order, UUID.randomUUID().toString(), "STORE",
                List.of(new Item("A", 1))), "wrong-key"))
                .isInstanceOf(InventoryException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void lookupRequiresExactlyOneSelector() {
        var service = new InventoryService(mock(InventoryRepository.class), mock(InventoryOperations.class));
        assertThatThrownBy(() -> service.lookup(null, null)).isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.lookup(UUID.randomUUID().toString(), "key")).isInstanceOf(InventoryException.class);
    }

    @Test
    void unknownStockReportsNotFound() {
        var repository = mock(InventoryRepository.class);
        when(repository.stock("STORE", "MISSING", false)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new InventoryService(repository, mock(InventoryOperations.class)).stock("STORE", "MISSING"))
                .isInstanceOfSatisfying(InventoryException.class, e -> assertThat(e.status()).isEqualTo(404));
    }
}
