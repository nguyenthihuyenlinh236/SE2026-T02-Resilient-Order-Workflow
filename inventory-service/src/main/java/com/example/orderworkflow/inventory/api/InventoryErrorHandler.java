package com.example.orderworkflow.inventory.api;

import org.springframework.http.ResponseEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.orderworkflow.inventory.api.InventoryDtos.ApiError;
import com.example.orderworkflow.inventory.service.InventoryException;

@RestControllerAdvice
public class InventoryErrorHandler {
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> duplicate(DuplicateKeyException e) {
        return ResponseEntity.status(409).body(new ApiError("RESERVATION_ID_CONFLICT",
                "Reservation ID is already assigned to another operation"));
    }
    @ExceptionHandler(InventoryException.class)
    public ResponseEntity<ApiError> business(InventoryException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Check required fields, UUIDs and positive integer quantities"));
    }
}
