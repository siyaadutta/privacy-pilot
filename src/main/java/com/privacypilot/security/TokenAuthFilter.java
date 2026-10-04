package com.privacypilot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests by a static bearer token, mapping each known token to a
 * role. Unknown/missing tokens are left unauthenticated; authorization rules in
 * {@link SecurityConfig} then decide whether the endpoint requires a role.
 *
 * <p>Roles: {@code OPERATOR} (dashboard/user actions) and {@code WEBHOOK}
 * (inbound-email intake only). Constant-time comparison avoids leaking token
 * length/content via timing.
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    static final String ROLE_OPERATOR = "OPERATOR";
    static final String ROLE_WEBHOOK = "WEBHOOK";

    private final SecurityProperties props;

    public TokenAuthFilter(SecurityProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            String role = roleForToken(token);
            if (role != null) {
                var auth = new UsernamePasswordAuthenticationToken(
                        role.toLowerCase(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    private String roleForToken(String token) {
        if (!props.getOperatorToken().isBlank() && constantTimeEquals(token, props.getOperatorToken())) {
            return ROLE_OPERATOR;
        }
        if (!props.getWebhookToken().isBlank() && constantTimeEquals(token, props.getWebhookToken())) {
            return ROLE_WEBHOOK;
        }
        return null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes();
        byte[] bb = b.getBytes();
        return java.security.MessageDigest.isEqual(ba, bb);
    }
}
