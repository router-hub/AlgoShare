package com.algoshare.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * ------------------------------------------------------------------------------------------------------
     * Spring Security Configuration for MVC (Servlet Stack)
     * ------------------------------------------------------------------------------------------------------
     * Interview QA: "How does Security work in Spring MVC?"
     * 
     * 1. Filter Chain Proxy (DelegatingFilterProxy):
     *    Spring Security sits as a standard Servlet Filter in the Tomcat container.
     *    It intercepts every HTTP request before it reaches the DispatcherServlet.
     * 
     * 2. Configure Bean: 'SecurityFilterChain'
     *    Unlike WebFlux's 'SecurityWebFilterChain', here we use 'SecurityFilterChain'.
     *    We configure the 'HttpSecurity' object (Mutable builder pattern).
     * 
     * 3. Thread Model (ThreadLocal):
     *    In MVC, the 'SecurityContext' is stored in a ThreadLocal variable.
     *    This means the Authentication object is accessible anywhere in the request thread via:
     *    SecurityContextHolder.getContext().getAuthentication()
     * ------------------------------------------------------------------------------------------------------
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF for stateless APIs
                .csrf(csrf -> csrf.disable())
                
                // Authorization Rules
                .authorizeHttpRequests(auth -> auth
                        // Allow access to OIDC Discovery endpoints and H2 Console (if used)
                        .requestMatchers("/.well-known/**", "/oauth2/**").permitAll()
                        .anyRequest().authenticated()
                )
                
                // Form Login (Optional, usually disabled for pure REST APIs)
                // .formLogin(withDefaults())
                ;
        
        return http.build();
    }
}
