package vn.edu.resilientorder.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CreateOrderRequest(
        @NotEmpty List<@Valid OrderLineRequest> items
) {
    public record OrderLineRequest(
            @NotBlank String sku,
            @Positive int quantity
    ) {
    }
}
