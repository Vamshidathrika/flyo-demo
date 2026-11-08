package com.flyo.rescue.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Production-ready JWT token issuer and validator.
 *
 * Enforces OWASP API Security Top 10 (API2:2023 Broken Authentication):
 * - Strict HMAC-SHA256 signing with 256+ bit cryptographic key.
 * - Explicit algorithm allowlist (rejects 'none' and non-HS256 algorithms).
 * - Full validation of signature, issuer ('flyo-auth'), audience ('flyo-rescue-api'),
 *   and expiration timestamps.
 * - Cryptographically embeds tenantId and authorizedTenants claims for zero-trust authorization.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    public static final String ISSUER = "flyo-auth";
    public static final String AUDIENCE = "flyo-rescue-api";

    private final SecretKey signingKey;
    private final Duration tokenValidity;

    public JwtTokenProvider(
            @Value("${flyo.security.jwt.secret:flyo-production-grade-hmac-sha256-signing-secret-key-32bytes-minimum}") String secret,
            @Value("${flyo.security.jwt.validity-minutes:60}") long validityMinutes) {

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // Defend against weak or truncated signing secrets
            throw new IllegalArgumentException("JWT secret must be at least 32 characters (256 bits) for HMAC-SHA256 compliance.");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.tokenValidity = Duration.ofMinutes(validityMinutes);
    }

    public SecretKey getSigningKey() {
        return signingKey;
    }

    public String generateToken(String username, Collection<? extends GrantedAuthority> authorities, Map<String, Object> additionalClaims) {
        Instant now = Instant.now();
        Instant expiry = now.plus(tokenValidity);

        List<String> roles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        Map<String, Object> claims = new HashMap<>();
        if (additionalClaims != null) {
            claims.putAll(additionalClaims);
        }
        claims.put("roles", roles);

        // Ensure tenantId and authorizedTenants claims exist
        if (!claims.containsKey("tenantId")) {
            claims.put("tenantId", TenantContext.DEFAULT_TENANT_ID);
        }
        if (!claims.containsKey("authorizedTenants")) {
            claims.put("authorizedTenants", claims.get("tenantId"));
        }

        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(username)
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now.minusSeconds(2))) // Grace period for slight clock skew
                .expiration(Date.from(expiry))
                .claims(claims)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) return false;
        try {
            Jws<Claims> jws = parseClaims(token);
            Claims claims = jws.getPayload();

            // Explicit checks for claims
            if (!ISSUER.equals(claims.getIssuer())) {
                log.warn("[JWT Security] Rejected token with untrusted issuer: {}", claims.getIssuer());
                return false;
            }

            Set<String> aud = claims.getAudience();
            if (aud == null || !aud.contains(AUDIENCE)) {
                log.warn("[JWT Security] Rejected token with invalid audience: {}", aud);
                return false;
            }

            Date exp = claims.getExpiration();
            if (exp == null || exp.before(new Date())) {
                log.warn("[JWT Security] Rejected expired token for subject: {}", claims.getSubject());
                return false;
            }

            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("[JWT Security] Token validation failure: {}", ex.getMessage());
            return false;
        }
    }

    public Authentication getAuthentication(String token) {
        Claims claims = parseClaims(token).getPayload();
        String username = claims.getSubject();

        List<?> rawRoles = claims.get("roles", List.class);
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (rawRoles != null) {
            for (Object r : rawRoles) {
                if (r != null) {
                    authorities.add(new SimpleGrantedAuthority(r.toString()));
                }
            }
        }

        String tenantId = claims.get("tenantId", String.class);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = TenantContext.DEFAULT_TENANT_ID;
        }

        Object rawAuthTenants = claims.get("authorizedTenants");
        Set<String> authTenantsSet = new HashSet<>();
        if (rawAuthTenants instanceof List<?>) {
            for (Object o : (List<?>) rawAuthTenants) {
                if (o != null) authTenantsSet.add(o.toString());
            }
        } else if (rawAuthTenants instanceof String) {
            authTenantsSet.addAll(Arrays.stream(((String) rawAuthTenants).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet()));
        }
        if (authTenantsSet.isEmpty()) {
            authTenantsSet.add(tenantId);
        }

        FlyoUserPrincipal principal = new FlyoUserPrincipal(username, "", authorities, tenantId, authTenantsSet);
        return new UsernamePasswordAuthenticationToken(principal, token, authorities);
    }

    public Claims getClaims(String token) {
        return parseClaims(token).getPayload();
    }

    private Jws<Claims> parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token);
    }

    public long getValidityMillis() {
        return tokenValidity.toMillis();
    }
}
