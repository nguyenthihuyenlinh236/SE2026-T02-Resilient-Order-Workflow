package com.example.orderworkflow.order.api;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.orderworkflow.order.api.OrderDtos.ErrorBody;
import com.example.orderworkflow.order.api.OrderDtos.ErrorDetail;
import com.example.orderworkflow.order.api.OrderDtos.ErrorResponse;
import com.example.orderworkflow.order.service.OrderException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class OrderErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(OrderErrorHandler.class);

    @ExceptionHandler(OrderException.class)
    ResponseEntity<ErrorResponse> order(OrderException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), List.of(), request,
                exception.status() >= 500);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        var details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorDetail(error.getField(), error.getDefaultMessage())).toList();
        return response(400, "VALIDATION_ERROR", "Request validation failed", details, request, false);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    ResponseEntity<ErrorResponse> malformed(Exception exception, HttpServletRequest request) {
        return response(400, "VALIDATION_ERROR", "Request JSON or required headers are invalid",
                List.of(), request, false);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class, IllegalStateException.class})
    ResponseEntity<ErrorResponse> infrastructure(RuntimeException exception, HttpServletRequest request) {
        log.error("Order persistence failure", exception);
        boolean retryable = exception instanceof TransientDataAccessException
                || exception instanceof RecoverableDataAccessException
                || exception instanceof CannotCreateTransactionException;
        return response(retryable ? 503 : 500,
                retryable ? "SERVICE_UNAVAILABLE" : "INTERNAL_SERVER_ERROR",
                "Order Service could not complete the request; retry with the same Idempotency-Key",
                List.of(), request, retryable);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> invalidQuery(MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return response(400, "VALIDATION_ERROR", "Query parameter is invalid",
                List.of(new ErrorDetail(exception.getName(), "Expected an integer")), request, false);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected Order Service failure", exception);
        return response(500, "INTERNAL_SERVER_ERROR", "Order Service could not process the request",
                List.of(), request, true);
    }

    private static ResponseEntity<ErrorResponse> response(int status, String code, String message,
            List<ErrorDetail> details, HttpServletRequest request, boolean retryable) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            correlationId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(status).header("X-Correlation-Id", correlationId)
                .body(new ErrorResponse(new ErrorBody(code, message, details, correlationId, retryable)));
    }
}
