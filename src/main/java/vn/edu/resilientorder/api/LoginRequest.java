package vn.edu.resilientorder.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 72) String password
) {
}
