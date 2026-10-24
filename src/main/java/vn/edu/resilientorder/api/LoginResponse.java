package vn.edu.resilientorder.api;

import vn.edu.resilientorder.security.AuthenticatedUser;

import java.util.UUID;

public record LoginResponse(
        UUID accountId,
        String username,
        String role,
        String storeId,
        int sessionIdleTimeoutSeconds
) {
    public static LoginResponse from(AuthenticatedUser user, int idleTimeoutSeconds) {
        return new LoginResponse(
                user.id(), user.getUsername(), user.role().name(), user.storeId(), idleTimeoutSeconds
        );
    }
}
