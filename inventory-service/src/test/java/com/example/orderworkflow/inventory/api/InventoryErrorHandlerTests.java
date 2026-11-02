package com.example.orderworkflow.inventory.api;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.UnexpectedRollbackException;
import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import static org.assertj.core.api.Assertions.*;

class InventoryErrorHandlerTests {
    @Test
    void connectionAndTransactionTimeoutErrorsAreRetryableWithoutLeakingDetails() {
        var handler = new InventoryErrorHandler();
        for (RuntimeException cause : List.of(new CannotGetJdbcConnectionException("private connection detail"),
                new CannotCreateTransactionException("private transaction detail"),
                new TransactionTimedOutException("private timeout detail"))) {
            var response = handler.infrastructure(cause, internalRequest());
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            var error = ((ContractErrorResponse) response.getBody()).error();
            assertThat(error.code()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(error.retryable()).isTrue();
            assertThat(error.message()).doesNotContain("private");
            assertThat(error.correlationId()).isEqualTo("corr-error-001");
        }
    }

    @Test
    void stateIntegrityAndUnexpectedRollbackErrorsDoNotSuggestAutomaticRetry() {
        var handler = new InventoryErrorHandler();
        for (RuntimeException cause : List.of(new IllegalStateException("private invariant detail"),
                new DataIntegrityViolationException("private SQL detail"),
                new UnexpectedRollbackException("private rollback detail"))) {
            var response = handler.infrastructure(cause, internalRequest());
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            var error = ((ContractErrorResponse) response.getBody()).error();
            assertThat(error.code()).isEqualTo("INTERNAL_SERVER_ERROR");
            assertThat(error.retryable()).isFalse();
            assertThat(error.details()).isEmpty();
            assertThat(error.message()).doesNotContain("private");
        }
    }

    @Test
    void legacyInfrastructureErrorKeepsFlatSchemaAndSafeMessage() {
        var request = new MockHttpServletRequest("POST", "/api/v1/inventory/reservations");
        var response = new InventoryErrorHandler().infrastructure(new IllegalStateException("private SQL detail"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        var error = (ApiError) response.getBody();
        assertThat(error.code()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(error.message()).doesNotContain("private");
    }

    private MockHttpServletRequest internalRequest() {
        var request = new MockHttpServletRequest("POST", "/internal/v1/reservations");
        request.addHeader("X-Correlation-Id", "corr-error-001");
        return request;
    }
}
