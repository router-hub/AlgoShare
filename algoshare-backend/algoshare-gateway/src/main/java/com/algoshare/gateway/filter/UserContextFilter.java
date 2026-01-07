package com.algoshare.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * ------------------------------------------------------------------------------------------------------
 * Custom Pre-Filter for JWT Claim Propagation
 * ------------------------------------------------------------------------------------------------------
 * Role: While 'SecurityConfig' validates WHO the user is (Authentication), this filter helps downstream services
 * understand WHO they are dealing with without re-parsing the JWT.
 * 
 * Mechanism: 
 * 1. It intercepts the request AFTER SecurityConfig validation.
 * 2. It extracts the JWT from the SecurityContext.
 * 3. It reads specific claims (sub, iss).
 * 4. It MUTATES the request by adding these claims as standard HTTP Headers (X-User-Id).
 * 
 * Benefit: Downstream services (Order, Strategy) don't need the heavy JWT library dependencies. 
 * They just read "X-User-Id" from the header.
 * ------------------------------------------------------------------------------------------------------
 */
@Component
public class UserContextFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication().getPrincipal())
                .filter(principal -> principal instanceof Jwt)
                .cast(Jwt.class)
                .map(jwt -> {

                    String userId = jwt.getClaimAsString("sub");
                    String issuer = jwt.getIssuer().toString();

                    return exchange.mutate()
                            .request(builder -> builder
                                            .header("X-User-Id", userId)
                                            .header("X-Token-Issuer", issuer)
                            )
                            .build();
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
