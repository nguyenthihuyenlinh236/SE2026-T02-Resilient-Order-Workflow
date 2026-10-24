package vn.edu.resilientorder.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import vn.edu.resilientorder.security.AccountRole;

import java.util.UUID;

@Service
public class AccountProvisioningService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public AccountProvisioningService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UUID create(
            String rawUsername,
            String rawPassword,
            AccountRole role,
            String storeId
    ) {
        String username = rawUsername.trim().toLowerCase();
        String normalizedStoreId = storeId == null || storeId.isBlank() ? null : storeId.trim();
        if (role == AccountRole.STAFF && normalizedStoreId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff accounts require a store.");
        }
        if (role != AccountRole.STAFF && normalizedStoreId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only staff accounts may be assigned to a store.");
        }
        if (normalizedStoreId != null && jdbc.queryForObject(
                "SELECT COUNT(*) FROM stores WHERE id = ?", Integer.class, normalizedStoreId
        ) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store does not exist.");
        }

        UUID accountId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO accounts (id, username, password_hash, role, store_id)
                VALUES (?, ?, ?, ?, ?)
                """,
                accountId, username, passwordEncoder.encode(rawPassword), role.name(), normalizedStoreId
        );
        return accountId;
    }
}
