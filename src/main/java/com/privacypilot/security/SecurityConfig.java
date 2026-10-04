package com.privacypilot.security;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Authentication + authorization boundary.
 *
 * <p>Endpoints are grouped by the principal that legitimately calls them:
 * <ul>
 *   <li>{@code POST /api/emails/process} — inbound-email intake — requires the
 *       {@code WEBHOOK} role (the mail provider), or {@code OPERATOR}.</li>
 *   <li>All other {@code /api/**} (viewing/creating/approving/sending/following up)
 *       — requires the {@code OPERATOR} role.</li>
 * </ul>
 *
 * <p><b>Demo mode:</b> if no tokens are configured ({@link SecurityProperties#isEnforced()}
 * is false), the API is left open so the project runs with zero configuration — a
 * loud warning is logged. As soon as any token is set, the boundary is enforced.
 * CORS is restricted to the configured origins (no wildcard).
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final SecurityProperties props;
    private final List<String> allowedOrigins;

    public SecurityConfig(SecurityProperties props,
                          @Value("${privacypilot.cors.allowed-origins:http://localhost:5173}") String origins) {
        this.props = props;
        this.allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // Stateless token API: CSRF tokens are not applicable (no cookies/sessions).
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!props.isEnforced()) {
            log.warn("SECURITY DISABLED (demo mode): no privacypilot.security.*-token configured. "
                    + "Set OPERATOR_API_TOKEN / WEBHOOK_API_TOKEN to enforce the auth boundary.");
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http.addFilterBefore(new TokenAuthFilter(props), UsernamePasswordAuthenticationFilter.class);
        http.exceptionHandling(ex -> ex.authenticationEntryPoint(
                (request, response, authEx) ->
                        response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED,
                                "Missing or invalid bearer token")));
        http.authorizeHttpRequests(auth -> auth
                // Inbound intake: webhook (or operator).
                .requestMatchers(HttpMethod.POST, "/api/emails/process")
                    .hasAnyRole(TokenAuthFilter.ROLE_WEBHOOK, TokenAuthFilter.ROLE_OPERATOR)
                // Everything else under /api requires an operator.
                .requestMatchers("/api/**").hasRole(TokenAuthFilter.ROLE_OPERATOR)
                .anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
