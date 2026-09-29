package com.arpitha.api_gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Tells the services who the caller is, using only the verified token.
 *
 * Any X-User-* headers sent by the client are removed first, so nobody can pose as another
 * user by setting the header themselves. For an authenticated request the gateway then adds
 * X-User-Id (the token's subject), X-User-Email and X-User-Roles.
 */
@Component
public class UserHeadersFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLES = "X-User-Roles";
    private static final String USER_HEADER_PREFIX = "X-User-";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken())
                .map(jwt -> withHeaders(exchange, jwt))
                .switchIfEmpty(Mono.fromSupplier(() -> withHeaders(exchange, null)))
                .flatMap(chain::filter);
    }

    /**
     * The request headers can already be a read-only view at this point (earlier filters wrap
     * the request), so build a fresh copy rather than editing them in place.
     */
    private static ServerWebExchange withHeaders(ServerWebExchange exchange, Jwt jwt) {
        HttpHeaders headers = new HttpHeaders();
        exchange.getRequest().getHeaders().forEach((name, values) -> {
            if (!name.regionMatches(true, 0, USER_HEADER_PREFIX, 0, USER_HEADER_PREFIX.length())) {
                headers.put(name, values);
            }
        });

        if (jwt != null) {
            headers.set(USER_ID, jwt.getSubject());
            String email = jwt.getClaimAsString("email");
            if (email != null) {
                headers.set(USER_EMAIL, email);
            }
            List<String> roles = jwt.getClaimAsStringList("roles");
            headers.set(USER_ROLES, roles == null ? "" : String.join(",", roles));
        }

        HttpHeaders readOnly = HttpHeaders.readOnlyHttpHeaders(headers);
        ServerHttpRequest request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                return readOnly;
            }
        };
        return exchange.mutate().request(request).build();
    }

    /** Before the routing filter, after Spring Cloud Gateway's own header filters. */
    @Override
    public int getOrder() {
        return 0;
    }
}
