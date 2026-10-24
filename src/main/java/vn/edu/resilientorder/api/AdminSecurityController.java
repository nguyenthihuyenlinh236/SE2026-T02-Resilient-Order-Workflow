package vn.edu.resilientorder.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/security-alerts")
public class AdminSecurityController {
    private final JdbcTemplate jdbc;

    public AdminSecurityController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<SecurityAlertResponse> pendingAlerts() {
        return jdbc.query(
                """
                SELECT id, account_id, session_id, alert_type, created_at
                FROM security_alerts
                WHERE acknowledged_at IS NULL
                ORDER BY created_at
                LIMIT 100
                """,
                (rs, rowNum) -> new SecurityAlertResponse(
                        rs.getLong("id"),
                        rs.getObject("account_id", java.util.UUID.class),
                        rs.getString("session_id"),
                        rs.getString("alert_type"),
                        rs.getTimestamp("created_at").toInstant()
                )
        );
    }

    @PatchMapping("/{alertId}/acknowledge")
    ResponseEntity<Void> acknowledge(@PathVariable long alertId) {
        int updated = jdbc.update(
                """
                UPDATE security_alerts
                SET acknowledged_at = clock_timestamp()
                WHERE id = ? AND acknowledged_at IS NULL
                """,
                alertId
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Security alert not found.");
        }
        return ResponseEntity.noContent().build();
    }

    public record SecurityAlertResponse(
            long id,
            java.util.UUID accountId,
            String sessionId,
            String type,
            Instant createdAt
    ) {
    }
}
