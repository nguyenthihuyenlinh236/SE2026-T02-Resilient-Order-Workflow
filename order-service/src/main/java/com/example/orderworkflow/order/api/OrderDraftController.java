package com.example.orderworkflow.order.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.orderworkflow.order.api.OrderDraftDtos.DraftResponse;
import com.example.orderworkflow.order.api.OrderDraftDtos.SaveDraftRequest;
import com.example.orderworkflow.order.service.OrderDraftService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/order-drafts")
public class OrderDraftController {
    private final OrderDraftService service;

    public OrderDraftController(OrderDraftService service) {
        this.service = service;
    }

    @PutMapping("/{draftId}")
    public DraftResponse save(@PathVariable String draftId,
            @RequestHeader("X-Client-Id") String clientId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody SaveDraftRequest request) {
        return service.save(clientId, draftId, correlationId, request);
    }

    @GetMapping("/{draftId}")
    public DraftResponse get(@PathVariable String draftId,
            @RequestHeader("X-Client-Id") String clientId) {
        return service.get(clientId, draftId);
    }
}
