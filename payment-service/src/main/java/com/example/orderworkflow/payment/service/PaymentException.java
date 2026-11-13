package com.example.orderworkflow.payment.service;

public final class PaymentException extends RuntimeException {
    private final int status;
    private final String code;

    public PaymentException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
