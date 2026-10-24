package vn.edu.resilientorder.security;

public record AuthenticationResult(
        AuthenticatedUser user,
        Failure failure,
        int failedAttempts
) {
    public enum Failure {
        INVALID_CREDENTIALS,
        ACCOUNT_LOCKED
    }

    public static AuthenticationResult authenticated(AuthenticatedUser user) {
        return new AuthenticationResult(user, null, 0);
    }

    public static AuthenticationResult failed(Failure failure, int failedAttempts) {
        return new AuthenticationResult(null, failure, failedAttempts);
    }

    public boolean isAuthenticated() {
        return user != null;
    }
}
