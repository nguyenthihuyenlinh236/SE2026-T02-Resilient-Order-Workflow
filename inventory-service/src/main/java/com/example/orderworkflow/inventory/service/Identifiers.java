package com.example.orderworkflow.inventory.service;

public final class Identifiers {
    private Identifiers() { }

    public static void require(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new InventoryException(400, "INVALID_IDENTIFIER", field + " must be a valid Identifier (1 to 128 characters)");
        }
    }
}
