package vn.edu.resilientorder.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SagaOrchestratorTest {

    @Test
    void acceptsConfiguredPaymentOutcomesWithoutCaseSensitivity() {
        for (String outcome : new String[]{"SUCCEEDED", "FAILED", "UNKNOWN", "succeeded"}) {
            assertDoesNotThrow(() -> new SagaOrchestrator(
                    mock(JdbcTemplate.class), mock(OrderService.class), outcome
            ));
        }
    }

    @Test
    void rejectsUnsupportedConfiguredPaymentOutcome() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SagaOrchestrator(mock(JdbcTemplate.class), mock(OrderService.class), "PENDING")
        );

        assertEquals(
                "app.payment.simulated-outcome must be SUCCEEDED, FAILED or UNKNOWN.",
                exception.getMessage()
        );
    }

    @Test
    void doesNotRunExpirationCompensationWhenConditionalClaimLoses() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SagaOrchestrator orchestrator = new SagaOrchestrator(
                jdbc, mock(OrderService.class), "SUCCEEDED"
        );
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        orchestrator.expire(UUID.randomUUID());

        verify(jdbc).update(anyString(), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }
}
