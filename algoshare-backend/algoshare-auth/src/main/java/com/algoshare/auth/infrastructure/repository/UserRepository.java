package com.algoshare.auth.infrastructure.repository;

import com.algoshare.auth.infrastructure.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    /* ================= HOT PATHS ================= */

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    /* ================= READ OPTIMIZATION ================= */

    @Query("""
        select u.id
        from UserEntity u
        where u.email = :email
    """)
    Optional<UUID> findUserIdByEmail(@Param("email") String email);

    /* ================= ACCOUNT STATE ================= */

    @Query("""
        select u.status
        from UserEntity u
        where u.id = :userId
    """)
    Optional<String> findStatusByUserId(@Param("userId") UUID userId);
}
