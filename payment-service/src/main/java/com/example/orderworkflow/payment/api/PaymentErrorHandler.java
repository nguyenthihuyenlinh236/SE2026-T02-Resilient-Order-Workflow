package com.example.orderworkflow.payment.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.orderworkflow.payment.api.PaymentDtos.ErrorBody;
import com.example.orderworkflow.payment.api.PaymentDtos.ErrorDetail;
import com.example.orderworkflow.payment.api.PaymentDtos.ErrorResponse;
import com.example.orderworkflow.payment.service.PaymentException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class PaymentErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentErrorHandler.class);

    @ExceptionHandler(PaymentException.class)
    ResponseEntity<ErrorResponse> payment(PaymentException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(error(exception.code(), exception.getMessage(),
                List.of(), request, exception.status() >= 500));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        var details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorDetail(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", "Request validation failed",
                details, request, false));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    ResponseEntity<ErrorResponse> malformed(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", exception.getMessage(),
                List.of(), request, false));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected Payment Service failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("INTERNAL_ERROR",
                "Payment Service could not process the request", List.of(), request, true));
    }

    private static ErrorResponse error(String code, String message, List<ErrorDetail> details,
            HttpServletRequest request, boolean retryable) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        return new ErrorResponse(new ErrorBody(code, message, details, correlationId, retryable));
    }
}
