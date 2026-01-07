package com.algoshare.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * ------------------------------------------------------------------------------------------------------
 * Security Configuration for Spring Cloud Gateway
 * ------------------------------------------------------------------------------------------------------
 * Interview QA: "How do you secure a microservices architecture?"
 * Answer: "We use an API Gateway acting as an OAuth2 Resource Server. It validates JWTs at the edge,
 * ensuring that only authenticated requests reach internal services (Stateless verification via RS256 Public Keys)."
 * ------------------------------------------------------------------------------------------------------
 * Interview QA: "Difference between Spring Security WebFlux vs. MVC?"
 * 
 * 1. Thread Model:
 *    - MVC (Servlet): Thread-per-Request. SecurityContext is stored in ThreadLocal.
 *    - WebFlux (Netty): Event Loop (few threads). SecurityContext is passed via Reactor Context (Subscriber Context).
 * 
 * 2. Configuration Class:
 *    - MVC: extends WebSecurityConfigurerAdapter (old) or SecurityFilterChain bean.
 *    - WebFlux: SecurityWebFilterChain bean.
 * 
 * 3. Annotations:
 *    - MVC: @EnableWebSecurity
 *    - WebFlux: @EnableWebFluxSecurity
 * ------------------------------------------------------------------------------------------------------
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                // 1. CSRF (Cross-Site Request Forgery) is disabled because we use Stateless REST APIs (JWTs),
                // not Session-based authentication where CSRF is critical.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                
                // 2. Authorization Rules
                .authorizeExchange(exchanges -> exchanges
                        // Public Endpoints: Auth (Login/Register), WebSockets (Handshaking), and Webhooks (Stripe)
                        .pathMatchers("/auth/**", "/ws/**", "/api/payment/webhook").permitAll()
                        // All other endpoints require a valid JWT
                        .anyExchange().authenticated()
                )
                
                // 3. OAuth2 Resource Server
                // This enables the "Bearer Token" authentication mechanism.
                // It reads the "Authorization: Bearer <token>" header, parses the JWT, and validates the signature
                // using the 'issuer-uri' configured in application.yml.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
