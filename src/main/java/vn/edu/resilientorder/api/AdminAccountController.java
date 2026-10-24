package vn.edu.resilientorder.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.resilientorder.security.AccountRole;
import vn.edu.resilientorder.service.AccountProvisioningService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {
    private final AccountProvisioningService accountProvisioningService;

    public AdminAccountController(AccountProvisioningService accountProvisioningService) {
        this.accountProvisioningService = accountProvisioningService;
    }

    @PostMapping
    AccountCreatedResponse create(@Valid @RequestBody CreateAccountRequest request) {
        if (request.password().length() < 12) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Password must contain at least 12 characters."
            );
        }
        UUID id = accountProvisioningService.create(
                request.username(), request.password(), request.role(), request.storeId()
        );
        return new AccountCreatedResponse(id, request.username().trim().toLowerCase(), request.role());
    }

    public record CreateAccountRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._@+-]+") String username,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotNull AccountRole role,
            String storeId
    ) {}

    public record AccountCreatedResponse(UUID accountId, String username, AccountRole role) {
    }
}
