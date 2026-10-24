package vn.edu.resilientorder.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public AdminBootstrap(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.admin-username:}") String username,
            @Value("${app.bootstrap.admin-password:}") String password
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() && password.isBlank()) {
            return;
        }
        if (username.isBlank() || password.isBlank() || password.length() < 12) {
            throw new IllegalStateException(
                    "Set both APP_BOOTSTRAP_ADMIN_USERNAME and APP_BOOTSTRAP_ADMIN_PASSWORD (minimum 12 characters)."
            );
        }
        jdbc.update(
                """
                INSERT INTO accounts (id, username, password_hash, role)
                VALUES (?, ?, ?, 'ADMIN')
                ON CONFLICT (username) DO NOTHING
                """,
                UUID.randomUUID(), username.trim().toLowerCase(), passwordEncoder.encode(password)
        );
    }
}
