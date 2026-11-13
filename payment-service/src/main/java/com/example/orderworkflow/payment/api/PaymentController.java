package com.example.orderworkflow.payment.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.orderworkflow.payment.api.PaymentDtos.CaptureRequest;
import com.example.orderworkflow.payment.api.PaymentDtos.CaptureResponse;
import com.example.orderworkflow.payment.service.PaymentCaptureService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/v1/payments")
public class PaymentController {
    private final PaymentCaptureService service;

    public PaymentController(PaymentCaptureService service) {
        this.service = service;
    }

    @PostMapping("/captures")
    public CaptureResponse capture(@Valid @RequestBody CaptureRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return service.capture(request, key);
    }

    @GetMapping("/{paymentId}")
    public CaptureResponse byPaymentId(@PathVariable String paymentId) {
        return service.getByPaymentId(paymentId);
    }

    @GetMapping("/by-idempotency-key/{key}")
    public CaptureResponse byIdempotencyKey(@PathVariable String key) {
        return service.getByIdempotencyKey(key);
    }
}
