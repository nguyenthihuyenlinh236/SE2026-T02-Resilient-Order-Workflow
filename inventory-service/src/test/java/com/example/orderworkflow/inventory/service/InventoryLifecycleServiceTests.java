package com.example.orderworkflow.inventory.service;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import com.example.orderworkflow.inventory.repository.InventoryOperations;
import com.example.orderworkflow.inventory.repository.InventoryRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryLifecycleServiceTests {
    @Test
    void invalidReleaseIsRejectedBeforeDatabaseAccess() {
        var repository = mock(InventoryRepository.class);
        var operations = mock(InventoryOperations.class);
        var service = new InventoryLifecycleService(repository, operations);
        UUID order = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        String key = "inventory:release:" + order + ":" + reservation + ":v1";
        assertThatThrownBy(() -> service.release(reservation, null, key)).isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.release(UUID.randomUUID(), new ReleaseRequest(order, reservation, "FAILED"), key))
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
        UUID order = UUID.randomUUID();
        String key = "inventory:cancel:" + order + ":v1";
        assertThatThrownBy(() -> service.cancel(order, null, key)).isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), new CancelRequest(order, "EXPIRED"), key))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.cancel(order, new CancelRequest(order, "x".repeat(256)), key))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.cancel(order, new CancelRequest(order, "EXPIRED"), "bad-key"))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.operation(" ")).isInstanceOf(InventoryException.class);
        verifyNoInteractions(repository, operations);
    }
}
