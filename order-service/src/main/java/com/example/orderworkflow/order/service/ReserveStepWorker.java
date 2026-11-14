package com.example.orderworkflow.order.service;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;

import com.example.orderworkflow.order.repository.SagaRepository;

/** Claims one durable RESERVE intent per poll; the HTTP call is outside repository/result transactions. */
public class ReserveStepWorker {
    private final InventoryClient inventory;
    private final SagaRepository saga;
    private final ReserveResultHandler results;
    private final long leaseMillis;

    public ReserveStepWorker(InventoryClient inventory, SagaRepository saga, ReserveResultHandler results,
            Duration lease) {
        this.inventory = inventory;
        this.saga = saga;
        this.results = results;
        this.leaseMillis = lease.toMillis();
        if (lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("Worker lease must be positive");
    }

    @Scheduled(fixedDelayString = "${order.worker.poll-interval:250}")
    public void poll() {
        saga.claimReserve(leaseMillis).ifPresent(claim -> {
            var outcome = inventory.reserve(claim.idempotencyKey(), claim.requestPayload());
            results.apply(claim, outcome);
        });
    }
}
