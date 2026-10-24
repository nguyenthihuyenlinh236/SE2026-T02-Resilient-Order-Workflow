package vn.edu.resilientorder.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Service
public class AuthenticationService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;
    private final String dummyPasswordHash;
    private final int maximumFailedAttempts;

    public AuthenticationService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager,
            @Value("${app.security.maximum-failed-attempts:5}") int maximumFailedAttempts
    ) {
        if (maximumFailedAttempts < 1) {
            throw new IllegalArgumentException("The maximum failed login attempts must be positive.");
        }
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.maximumFailedAttempts = maximumFailedAttempts;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public AuthenticationResult authenticate(String rawUsername, String rawPassword, String requestPath) {
        if (rawUsername == null || rawPassword == null) {
            passwordEncoder.matches(rawPassword == null ? "" : rawPassword, dummyPasswordHash);
            return AuthenticationResult.failed(AuthenticationResult.Failure.INVALID_CREDENTIALS, 0);
        }
        String username = rawUsername.trim().toLowerCase();
        return transaction.execute(status -> authenticateInTransaction(username, rawPassword, requestPath));
    }

    private AuthenticationResult authenticateInTransaction(String username, String rawPassword, String requestPath) {
        List<AccountRecord> accounts = jdbc.query(
                """
                SELECT id, username, password_hash, role, store_id, enabled,
                       failed_login_attempts, locked_until
                FROM accounts WHERE username = ? FOR UPDATE
                """,
                (rs, rowNum) -> new AccountRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        AccountRole.valueOf(rs.getString("role")),
                        rs.getString("store_id"),
                        rs.getBoolean("enabled"),
                        rs.getInt("failed_login_attempts"),
                        rs.getTimestamp("locked_until") == null
                                ? null : rs.getTimestamp("locked_until").toInstant()
                ),
                username
        );
        if (accounts.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyPasswordHash);
            recordAuthenticationAttempt(null, username, requestPath, "AUTHENTICATION_FAILED");
            return AuthenticationResult.failed(AuthenticationResult.Failure.INVALID_CREDENTIALS, 0);
        }

        AccountRecord account = accounts.get(0);
        if (!account.enabled()) {
            passwordEncoder.matches(rawPassword, account.passwordHash());
            recordAuthenticationAttempt(account.id(), username, requestPath, "AUTHENTICATION_FAILED");
            return AuthenticationResult.failed(AuthenticationResult.Failure.INVALID_CREDENTIALS, 0);
        }

        if (account.lockedUntil() != null && jdbc.queryForObject(
                "SELECT clock_timestamp() < ?",
                Boolean.class,
                Timestamp.from(account.lockedUntil())
        )) {
            recordAuthenticationAttempt(account.id(), username, requestPath, "AUTHENTICATION_LOCKED");
            return AuthenticationResult.failed(
                    AuthenticationResult.Failure.ACCOUNT_LOCKED, account.failedAttempts()
            );
        }

        boolean passwordMatches = passwordEncoder.matches(rawPassword, account.passwordHash());
        if (!passwordMatches) {
            return recordFailedPassword(account, username, requestPath);
        }

        jdbc.update(
                "UPDATE accounts SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?",
                account.id()
        );
        recordAuthenticationAttempt(account.id(), username, requestPath, "AUTHENTICATION_SUCCEEDED");
        return AuthenticationResult.authenticated(
                new AuthenticatedUser(account.id(), account.username(), account.role(), account.storeId())
        );
    }

    private AuthenticationResult recordFailedPassword(
            AccountRecord account,
            String username,
            String requestPath
    ) {
        int failedAttempts = account.lockedUntil() != null ? 1 : account.failedAttempts() + 1;
        if (failedAttempts >= maximumFailedAttempts) {
            jdbc.update(
                    """
                    UPDATE accounts
                    SET failed_login_attempts = ?, locked_until = clock_timestamp() + INTERVAL '15' MINUTE
                    WHERE id = ?
                    """,
                    maximumFailedAttempts, account.id()
            );
            recordAuthenticationAttempt(account.id(), username, requestPath, "AUTHENTICATION_LOCKED");
            return AuthenticationResult.failed(
                    AuthenticationResult.Failure.ACCOUNT_LOCKED, maximumFailedAttempts
            );
        }

        jdbc.update(
                """
                UPDATE accounts SET failed_login_attempts = ?, locked_until = NULL
                WHERE id = ?
                """,
                failedAttempts, account.id()
        );
        recordAuthenticationAttempt(account.id(), username, requestPath, "AUTHENTICATION_FAILED");
        return AuthenticationResult.failed(
                AuthenticationResult.Failure.INVALID_CREDENTIALS, failedAttempts
        );
    }

    private void recordAuthenticationAttempt(
            UUID accountId,
            String username,
            String requestPath,
            String eventType
    ) {
        jdbc.update(
                """
                INSERT INTO security_audit_log (actor_account_id, event_type, target, request_path)
                VALUES (?, ?, ?, ?)
                """,
                accountId, eventType, username, requestPath
        );
    }

    private record AccountRecord(
            UUID id,
            String username,
            String passwordHash,
            AccountRole role,
            String storeId,
            boolean enabled,
            int failedAttempts,
            java.time.Instant lockedUntil
    ) {
    }
}
