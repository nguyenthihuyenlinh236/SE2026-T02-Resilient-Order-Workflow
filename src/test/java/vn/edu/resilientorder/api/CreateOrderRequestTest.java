package vn.edu.resilientorder.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateOrderRequestTest {
    private static Validator validator;
    private static ValidatorFactory validatorFactory;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void requiresItemsAndPositiveQuantities() {
        assertFalse(validator.validate(new CreateOrderRequest(List.of(), "DEMO-STORE")).isEmpty());
        assertFalse(validator.validate(
                new CreateOrderRequest(
                        List.of(new CreateOrderRequest.OrderLineRequest("DEMO-ITEM", 0)), "DEMO-STORE"
                )
        ).isEmpty());
        assertTrue(validator.validate(
                new CreateOrderRequest(
                        List.of(new CreateOrderRequest.OrderLineRequest("DEMO-ITEM", 1)), "DEMO-STORE"
                )
        ).isEmpty());
        assertFalse(validator.validate(new CreateOrderRequest(
                java.util.Collections.nCopies(21, new CreateOrderRequest.OrderLineRequest("SKU", 1)),
                "DEMO-STORE"
        )).isEmpty());
    }

    @Test
    void rejectsBlankSku() {
        assertFalse(validator.validate(
                new CreateOrderRequest(
                        List.of(new CreateOrderRequest.OrderLineRequest(" ", 1)), "DEMO-STORE"
                )
        ).isEmpty());
    }
}
