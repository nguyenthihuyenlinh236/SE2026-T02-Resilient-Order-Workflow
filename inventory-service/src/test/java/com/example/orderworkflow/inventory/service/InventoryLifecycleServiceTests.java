package com.example.orderworkflow.inventory.service;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import com.example.orderworkflow.inventory.repository.InventoryOperations;
import com.example.orderworkflow.inventory.repository.InventoryRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryLifecycleServiceTests {
    @Test
    void invalidInternalReleaseCannotTouchDatabase() {
        var repository = mock(InventoryRepository.class);
        var operations = mock(InventoryOperations.class);
        var service = new InventoryLifecycleService(repository, operations);
        String key = "inventory:release:ORD-001:RES-001:v1";
        assertThatThrownBy(() -> service.releaseInternal("RES-001", null, key)).isInstanceOf(InventoryException.class);
        for (var request : java.util.List.of(
                new InternalReleaseRequest(null, "ORD-001", "corr-001"),
                new InternalReleaseRequest("cmd-001", "bad!id", "corr-001"),
                new InternalReleaseRequest("cmd-001", "ORD-001", " "),
                new InternalReleaseRequest("x".repeat(129), "ORD-001", "corr-001"))) {
            assertThatThrownBy(() -> service.releaseInternal("RES-001", request, key)).isInstanceOf(InventoryException.class);
        }
        assertThatThrownBy(() -> service.releaseInternal("RES-001",
                new InternalReleaseRequest("cmd-001", "ORD-001", "corr-001"), "wrong-key"))
                .isInstanceOf(InventoryException.class);
        verifyNoInteractions(repository, operations);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "key with space", "key\tvalue", "key\nvalue", "key\u007f", "khóa"})
    void invalidOperationKeysAreRejectedBeforeDatabaseAccess(String key) {
        var repository = mock(InventoryRepository.class);
        var operations = mock(InventoryOperations.class);
        var service = new InventoryLifecycleService(repository, operations);
        assertThatThrownBy(() -> service.operation(key)).isInstanceOfSatisfying(InventoryException.class,
                error -> assertThat(error.status()).isEqualTo(400));
        verifyNoInteractions(repository, operations);
    }

    @Test
    void operationKeyLongerThan512IsRejectedBeforeDatabaseAccess() {
        var repository = mock(InventoryRepository.class);
        var operations = mock(InventoryOperations.class);
        var service = new InventoryLifecycleService(repository, operations);
        assertThatThrownBy(() -> service.operation("k".repeat(513))).isInstanceOf(InventoryException.class);
        verifyNoInteractions(repository, operations);
    }

    @Test
    void invalidReleaseIsRejectedBeforeDatabaseAccess() {
        var repository = mock(InventoryRepository.class);
        var operations = mock(InventoryOperations.class);
        var service = new InventoryLifecycleService(repository, operations);
        String order = UUID.randomUUID().toString();
        String reservation = UUID.randomUUID().toString();
        String key = "inventory:release:" + order + ":" + reservation + ":v1";
        assertThatThrownBy(() -> service.release(reservation, null, key)).isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.release(UUID.randomUUID().toString(), new ReleaseRequest(order, reservation, "FAILED"), key))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.release(reservation, new ReleaseRequest(order, reservation, " "), key))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.release(reservation, new ReleaseRequest(order, reservation, "FAILED"), "old-key"))
                .isInstanceOf(InventoryException.class);
        verifyNoInteractions(repository, operations);
    }

    @Test
    void invalidCancelAndOperationLookupAreRejectedBeforeDatabaseAccess() {
        var repository = mock(InventoryRepository.class);
        var operations = mock(InventoryOperations.class);
        var service = new InventoryLifecycleService(repository, operations);
        String order = UUID.randomUUID().toString();
        String key = "inventory:cancel:" + order + ":v1";
        assertThatThrownBy(() -> service.cancel(order, null, key)).isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.cancel(UUID.randomUUID().toString(), new CancelRequest(order, "EXPIRED"), key))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.cancel(order, new CancelRequest(order, "x".repeat(256)), key))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.cancel(order, new CancelRequest(order, "EXPIRED"), "bad-key"))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.operation(" ")).isInstanceOf(InventoryException.class);
        verifyNoInteractions(repository, operations);
    }
}
