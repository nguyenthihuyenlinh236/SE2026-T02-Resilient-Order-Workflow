package vn.edu.resilientorder.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class AuthenticatedUser implements UserDetails, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String username;
    private final AccountRole role;
    private final String storeId;

    public AuthenticatedUser(UUID id, String username, AccountRole role, String storeId) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.storeId = storeId;
    }

    public UUID id() {
        return id;
    }

    public AccountRole role() {
        return role;
    }

    public String storeId() {
        return storeId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
