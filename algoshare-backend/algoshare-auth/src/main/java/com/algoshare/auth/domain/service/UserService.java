package com.algoshare.auth.domain.service;

import com.algoshare.auth.api.dto.RegistrationRequestDto;
import com.algoshare.auth.domain.constants.AuthConstants;
import com.algoshare.auth.domain.model.AccountStatus;
import com.algoshare.auth.infrastructure.entity.UserEntity;
import com.algoshare.auth.infrastructure.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * User Service - All user-related operations
 * Handles user CRUD, password hashing, status updates
 */
@Service
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder(AuthConstants.BCRYPT_COST_FACTOR);
    }

    /**
     * Check if email already exists
     */
    public boolean existsByEmail(String email) {
        boolean exists = userRepository.existsByEmail(email);
        log.debug("[EMAIL_CHECK] Email existence check | email={} | exists={}", email, exists);
        return exists;
    }

    /**
     * Create new user with hashed password
     * Status: PENDING_EMAIL_VERIFICATION
     */
    public UserEntity createUser(RegistrationRequestDto request) {
        log.info("[USER_CREATE_START] Creating new user | email={}", request.getEmail());

        UUID userId = UUID.randomUUID();
        String passwordHash = passwordEncoder.encode(request.getPassword());
        log.debug("[PASSWORD_HASHED] Password hashed with BCrypt cost=12 | userId={}", userId);

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordHash);
        user.setEmailVerified(false);
        user.setStatus(AccountStatus.PENDING_EMAIL_VERIFICATION.toString());
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        UserEntity savedUser = userRepository.save(user);
        log.info("[USER_CREATED] User created successfully | userId={} | email={} | status={}", 
            userId, request.getEmail(), "PENDING_EMAIL_VERIFICATION");

        return savedUser;
    }

    /**
     * Update user status (e.g., EMAIL_VERIFIED, ACTIVE)
     */
    public UserEntity updateStatus(UUID userId, String status) {
        log.info("[STATUS_UPDATE_START] Updating user status | userId={} | newStatus={}", userId, status);

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        user.setStatus(status);
        user.setUpdatedAt(Instant.now());
        user.setEmailVerified(true);

        UserEntity updatedUser = userRepository.save(user);
        log.info("[STATUS_UPDATED] User status updated | userId={} | status={}", userId, status);

        return updatedUser;
    }

    /**
     * Find user by email
     */
    public UserEntity findByEmail(String email) {
        log.debug("[USER_FIND] Finding user by email | email={}", email);
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
    }

    /**
     * Verify password matches stored hash
     */
    public boolean verifyPassword(String rawPassword, String passwordHash) {
        boolean matches = passwordEncoder.matches(rawPassword, passwordHash);
        log.debug("[PASSWORD_VERIFY] Password verification result | matches={}", matches);
        return matches;
    }
}
