package vn.edu.resilientorder.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(
        @NotEmpty @Size(max = 20) List<@Valid OrderLineRequest> items,
        @Size(max = 100) String storeId
) {
    public record OrderLineRequest(
            @NotBlank String sku,
            @Positive int quantity
    ) {
    }
}
