package com.algoshare.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public java.security.SecureRandom secureRandom() {
        return new java.security.SecureRandom();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for stateless APIs
            .csrf(AbstractHttpConfigurer::disable)

            // Stateless session management
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Authorization Rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints - OIDC Discovery
                .requestMatchers("/.well-known/**", "/oauth2/**").permitAll()

                // Public endpoints - Authentication
                .requestMatchers("/auth/register").permitAll()
                .requestMatchers("/auth/verify-email").permitAll()
                .requestMatchers("/auth/resend-verification").permitAll()
                .requestMatchers("/auth/login").permitAll()
                .requestMatchers("/auth/otp/verify").permitAll()
                .requestMatchers("/auth/otp/resend").permitAll()

                // Protected endpoints - Token management (require valid token)
                .requestMatchers("/auth/token/refresh").permitAll() // Allow refresh without auth
                .requestMatchers("/auth/token/revoke").authenticated()
                .requestMatchers("/auth/logout").authenticated()

                // Health check endpoints
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                // All other requests require authentication
                .anyRequest().authenticated()
            );

        return http.build();
    }
}
