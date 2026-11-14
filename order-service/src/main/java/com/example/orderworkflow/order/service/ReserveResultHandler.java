package com.example.orderworkflow.order.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderworkflow.order.repository.SagaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Applies an Inventory result in a short local transaction, fenced by the claim token. */
@Service
public class ReserveResultHandler {
    private final SagaRepository saga;
    private final OrderStateMachine states;
    private final ObjectMapper json;
    private final long retryDelayMillis;

    public ReserveResultHandler(SagaRepository saga, OrderStateMachine states, ObjectMapper json,
            @Value("${order.worker.retry-delay:1s}") Duration retryDelay) {
        this.saga = saga;
        this.states = states;
        this.json = json;
        this.retryDelayMillis = retryDelay.toMillis();
        if (retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("order.worker.retry-delay must be positive");
        }
    }

    @Transactional
    public void apply(SagaRepository.ClaimedReserve claim, InventoryClient.Outcome outcome) {
        String target = outcome.definitive() ? ("RESERVED".equals(outcome.status()) ? "SUCCEEDED" : "FAILED") : "UNKNOWN";
        String failureClass = "REJECTED".equals(outcome.status()) ? "BUSINESS"
                : "UNKNOWN".equals(target) ? "UNKNOWN" : "NONE";
        String result = write(outcome);
        int changed = saga.finishReserveClaim(claim, target, failureClass, result,
                outcome.errorMessage(), retryDelayMillis);
        if (changed != 1) {
            saga.finishSendAttempt(claim, "STALE", "STALE_CLAIM", "Claim token no longer owns the RESERVE step");
            return;
        }

        if ("UNKNOWN".equals(target)) {
            states.recordReservation(claim.orderId(), "UNKNOWN", false, "RESERVE");
            saga.finishSendAttempt(claim, "UNKNOWN", outcome.errorCode(), outcome.errorMessage());
            return;
        }

        states.recordReservation(claim.orderId(), outcome.status(), true, null);
        if ("REJECTED".equals(outcome.status())) {
            if (states.startCompensation(claim.orderId(), "INVENTORY_REJECTED")
                    != OrderStateMachine.Transition.APPLIED
                    || states.fail(claim.orderId()) != OrderStateMachine.Transition.APPLIED) {
                throw new IllegalStateException("Verified Inventory rejection could not complete the guarded Order failure transition");
            }
        }
        saga.finishSendAttempt(claim, outcome.status(), null, null);
    }

    private String write(InventoryClient.Outcome outcome) {
        try { return json.writeValueAsString(outcome); }
        catch (Exception exception) { throw new IllegalStateException("Inventory result could not be serialized", exception); }
    }
}
