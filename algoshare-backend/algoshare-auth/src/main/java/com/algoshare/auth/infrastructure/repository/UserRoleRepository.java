package com.algoshare.auth.infrastructure.repository;

import com.algoshare.auth.infrastructure.entity.UserRoleEntity;
import com.algoshare.auth.infrastructure.entity.UserRoleId;
import com.algoshare.auth.domain.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserRoleRepository
        extends JpaRepository<UserRoleEntity, UserRoleId> {

    /* ================= JWT GENERATION ================= */

    @Query("""
        select ur.role
        from UserRoleEntity ur
        where ur.userId = :userId
    """)
    List<Role> findRolesByUserId(@Param("userId") UUID userId);

    /* ================= ACCESS CHECKS ================= */

    boolean existsByUserIdAndRole(UUID userId, Role role);

    /* ================= ADMIN OPERATIONS ================= */

    void deleteByUserId(UUID userId);
}
