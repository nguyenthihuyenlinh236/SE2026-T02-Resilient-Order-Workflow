package com.example.orderworkflow.inventory.api;

import org.springframework.http.ResponseEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import com.example.orderworkflow.inventory.service.InventoryException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class InventoryErrorHandler {
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<?> duplicate(DuplicateKeyException e, HttpServletRequest request) {
        return error(request, 409, "RESERVATION_ID_CONFLICT",
                "Reservation ID is already assigned to another operation", List.of());
    }
    @ExceptionHandler(InventoryException.class)
    public ResponseEntity<?> business(InventoryException e, HttpServletRequest request) {
        return error(request, e.status(), e.code(), e.getMessage(), List.of());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception e, HttpServletRequest request) {
        List<ErrorDetail> details = e instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getFieldErrors().stream()
                    .map(field -> new ErrorDetail(field.getField(), field.getDefaultMessage())).toList()
                : List.of();
        String code = e instanceof MethodArgumentTypeMismatchException ? "INVALID_IDENTIFIER" : "INVALID_REQUEST";
        return error(request, 400, code, "Check required fields, identifiers and positive integer quantities", details);
    }

    private ResponseEntity<?> error(HttpServletRequest request, int status, String code,
            String message, List<ErrorDetail> details) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/internal/v1/")) {
            return ResponseEntity.status(status).body(new ApiError(
                    status == 400 && code.equals("INVALID_IDENTIFIER") ? "INVALID_REQUEST" : code, message));
        }
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            correlationId = UUID.randomUUID().toString();
        }
        String contractCode = code.equals("INVALID_REQUEST") ? "VALIDATION_ERROR" : code;
        return ResponseEntity.status(status).header("X-Correlation-Id", correlationId)
                .body(new ContractErrorResponse(new ContractError(contractCode, message, details,
                        correlationId, status >= 500)));
    }
}
