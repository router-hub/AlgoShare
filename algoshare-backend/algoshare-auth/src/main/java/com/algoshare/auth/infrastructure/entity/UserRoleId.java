package com.algoshare.auth.infrastructure.entity;

import com.algoshare.auth.domain.model.Role;

import java.io.Serializable;
import java.util.UUID;
import java.util.Objects;

public class UserRoleId implements Serializable {

    private UUID userId;
    private Role role;

    protected UserRoleId() {}

    public UserRoleId(UUID userId, Role role) {
        this.userId = userId;
        this.role = role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserRoleId)) return false;
        UserRoleId that = (UserRoleId) o;
        return Objects.equals(userId, that.userId)
                && role == that.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, role);
    }
}

