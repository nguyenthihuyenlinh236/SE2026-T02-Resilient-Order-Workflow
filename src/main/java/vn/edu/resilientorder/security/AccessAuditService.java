package vn.edu.resilientorder.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class AccessAuditService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int revocationThreshold;

    public AccessAuditService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${app.security.denied-request-revocation-threshold:10}") int revocationThreshold
    ) {
        if (revocationThreshold < 1) {
            throw new IllegalArgumentException("The denied request threshold must be positive.");
        }
        this.jdbc = jdbc;
        this.revocationThreshold = revocationThreshold;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void recordDenied(
            AuthenticatedUser user,
            HttpServletRequest request,
            String target,
            String eventType
    ) {
        HttpSession session = request.getSession(false);
        String sessionId = session == null ? null : session.getId();
        boolean revoke = Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.queryForObject(
                    "SELECT id FROM accounts WHERE id = ? FOR UPDATE",
                    UUID.class,
                    user.id()
            );
            jdbc.update(
                    """
                    INSERT INTO security_audit_log
                        (actor_account_id, event_type, target, request_path, session_id)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    user.id(), eventType, truncate(target, 500),
                    truncate(request.getRequestURI(), 500), sessionId
            );
            if (sessionId == null) {
                return false;
            }

            int updated = jdbc.update(
                    """
                    UPDATE session_denial_counts
                    SET denied_count = denied_count + 1, updated_at = clock_timestamp()
                    WHERE session_id = ? AND account_id = ?
                    """,
                    sessionId, user.id()
            );
            if (updated == 0) {
                jdbc.update(
                        """
                        INSERT INTO session_denial_counts (session_id, account_id, denied_count)
                        VALUES (?, ?, 1)
                        """,
                        sessionId, user.id()
                );
            }
            int deniedCount = jdbc.queryForObject(
                    "SELECT denied_count FROM session_denial_counts WHERE session_id = ? AND account_id = ?",
                    Integer.class,
                    sessionId, user.id()
            );
            if (deniedCount == revocationThreshold) {
                jdbc.update(
                        """
                        INSERT INTO security_alerts (account_id, session_id, alert_type)
                        VALUES (?, ?, 'SESSION_REVOKED_AFTER_DENIED_REQUESTS')
                        """,
                        user.id(), sessionId
                );
            }
            return deniedCount >= revocationThreshold;
        }));

        if (revoke && session != null) {
            session.invalidate();
        }
    }

    public boolean canAccessOrder(
            AuthenticatedUser user,
            UUID customerId,
            String storeId
    ) {
        return switch (user.role()) {
            case ADMIN -> true;
            case CUSTOMER -> user.id().equals(customerId);
            case STAFF -> user.storeId() != null && user.storeId().equals(storeId);
        };
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }
}
