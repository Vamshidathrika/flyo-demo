package com.flyo.rescue.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

/**
 * Enterprise Fail-Closed Spring Security Configuration.
 *
 * Enforces OWASP API Security Top 10:
 * - API1:2023 Broken Object Level Authorization (BOLA)
 * - API2:2023 Broken Authentication (Fail-closed JWT Bearer token enforcement)
 * - API5:2023 Broken Function Level Authorization (Role-based access control)
 * - API7:2023 Server-Side Request Forgery & CORS Hardening
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final com.flyo.rescue.security.TenantFilter tenantFilter;
    private final Environment environment;
    private final com.flyo.rescue.repository.OperatorAccountRepository operatorRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          com.flyo.rescue.security.TenantFilter tenantFilter,
                          Environment environment,
                          com.flyo.rescue.repository.OperatorAccountRepository operatorRepository) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.tenantFilter = tenantFilter;
        this.environment = environment;
        this.operatorRepository = operatorRepository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public org.springframework.security.core.userdetails.UserDetailsService userDetailsService() {
        return username -> operatorRepository.findByUsername(username)
                .map(acc -> {
                    String role = acc.getRole();
                    String authority = (role != null && role.startsWith("ROLE_")) ? role : "ROLE_" + (role != null ? role : "OPERATOR");
                    java.util.Set<String> authTenants = acc.getAuthorizedTenantsSet();
                    return new com.flyo.rescue.security.FlyoUserPrincipal(
                            acc.getUsername(),
                            acc.getPasswordHash(),
                            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(authority)),
                            acc.getTenantId() != null ? acc.getTenantId() : "tenant_default",
                            authTenants
                    );
                })
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + username));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean isDevProfile = environment.acceptsProfiles(Profiles.of("dev", "test", "default"));

        http
            .csrf(csrf -> csrf.disable()) // Stateless Bearer Token Architecture
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(org.springframework.security.config.Customizer.withDefaults())
            .headers(headers -> headers
                .frameOptions(frame -> {
                    if (isDevProfile) {
                        frame.sameOrigin(); // Allow H2 console and mock portal in local development
                    } else {
                        frame.deny(); // Prevent clickjacking in production
                    }
                })
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: file:; connect-src 'self'"
                ))
                .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                            "error", "Unauthorized",
                            "message", "Full authentication is required to access this resource.",
                            "status", 401
                    )));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                            "error", "Forbidden",
                            "message", "Access denied: insufficient administrative or operator privileges.",
                            "status", 403
                    )));
                })
            )
            .authorizeHttpRequests(auth -> {
                // 1. Explicit Public Endpoints
                auth.requestMatchers(
                    "/",
                    "/index.html",
                    "/style.css",
                    "/app.js",
                    "/favicon.ico",
                    "/screenshots/**",
                    "/api/auth/login",
                    "/api/whatsapp/webhook", // Authenticated via Meta HMAC-SHA256 signature in controller
                    "/api/webhooks/**",       // Authenticated via HMAC-SHA256 signature and timestamp in WebhookProcessor
                    "/actuator/health",
                    "/actuator/info"
                ).permitAll();

                // 2. Development & Testing Mock Tools (Excluded from production by profile)
                if (isDevProfile) {
                    auth.requestMatchers(
                        "/mock-airline/**",
                        "/h2-console/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/api/whatsapp/**"
                    ).permitAll();
                }

                // 3. Consequential Disruption & Rescue Operations (Role-Gated)
                auth.requestMatchers(HttpMethod.POST, "/api/cases/{id}/approve").hasAnyRole("SUPERVISOR", "ADMIN");
                auth.requestMatchers(HttpMethod.POST, "/api/cases/{id}/reject").hasAnyRole("SUPERVISOR", "ADMIN");
                auth.requestMatchers(HttpMethod.POST, "/api/cases/simulate").hasAnyRole("SUPERVISOR", "ADMIN");
                auth.requestMatchers("/api/cases/**").hasAnyRole("OPERATOR", "SUPERVISOR", "ADMIN", "AUDITOR");

                // 4. Booking & Servicing Execution
                auth.requestMatchers(HttpMethod.POST, "/api/bookings/rebook").hasAnyRole("SUPERVISOR", "ADMIN");
                auth.requestMatchers("/api/bookings/**").hasAnyRole("OPERATOR", "SUPERVISOR", "ADMIN", "AUDITOR");

                // 5. Payment & Financial Settlement (Admin Only)
                auth.requestMatchers("/api/payments/**", "/api/settlement/**").hasRole("ADMIN");

                // 6. Saga & Outbox Administration (Supervisor & Admin)
                auth.requestMatchers("/api/admin/**").hasAnyRole("SUPERVISOR", "ADMIN");

                // 7. Ingestion & Operational Radar Alerts
                auth.requestMatchers("/api/alerts/**").hasAnyRole("OPERATOR", "SUPERVISOR", "ADMIN");
                auth.requestMatchers("/api/ingest/**").hasAnyRole("OPERATOR", "SUPERVISOR", "ADMIN");
                auth.requestMatchers("/api/intelligence/**", "/api/weather/**").hasAnyRole("OPERATOR", "SUPERVISOR", "ADMIN");

                // 8. Actuator Metrics Protection
                auth.requestMatchers("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus", "/actuator/prometheus/**", "/actuator/env/**").hasRole("ADMIN");

                // 9. Strict Fail-Closed Catch-All
                auth.anyRequest().authenticated();
            })
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(tenantFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
            "http://localhost:[*]",
            "http://127.0.0.1:[*]"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With", "X-Flyo-Monitoring-Key"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
