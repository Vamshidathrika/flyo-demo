package com.flyo.rescue.controller;

import com.flyo.rescue.dto.AuthRequest;
import com.flyo.rescue.dto.AuthResponse;
import com.flyo.rescue.model.OperatorAccount;
import com.flyo.rescue.repository.OperatorAccountRepository;
import com.flyo.rescue.security.JwtTokenProvider;
import com.flyo.rescue.security.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final OperatorAccountRepository operatorRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final LoginRateLimiter rateLimiter;
    private final Environment environment;

    public AuthController(OperatorAccountRepository operatorRepository,
                          PasswordEncoder passwordEncoder,
                          JwtTokenProvider tokenProvider,
                          LoginRateLimiter rateLimiter,
                          Environment environment) {
        this.operatorRepository = operatorRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.rateLimiter = rateLimiter;
        this.environment = environment;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        String clientKey = clientIp + ":" + (request.getUsername() != null ? request.getUsername().toLowerCase().trim() : "unknown");

        if (rateLimiter.isBlocked(clientKey)) {
            log.warn("[Auth] Rate-limited login attempt for key '{}'", clientKey);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(AuthResponse.failure("Too many failed login attempts. Account temporarily locked for 15 minutes."));
        }

        String username = request.getUsername() != null ? request.getUsername().trim() : "";
        Optional<OperatorAccount> accountOpt = operatorRepository.findByUsername(username);

        // Constant-time behavior to prevent timing enumeration attacks
        boolean passwordMatches = false;
        if (accountOpt.isPresent()) {
            OperatorAccount account = accountOpt.get();
            passwordMatches = account.isEnabled() && passwordEncoder.matches(request.getPassword(), account.getPasswordHash());
        } else {
            // Dummy match to consume similar execution time
            passwordEncoder.matches("dummy_pass", "$2a$10$wK2VjK8bQ.vI1N4Q7g6w3eH3q2M7o9R0k1L2m3N4o5P6q7R8s9T0u");
        }

        if (passwordMatches && accountOpt.isPresent()) {
            OperatorAccount account = accountOpt.get();
            rateLimiter.recordSuccess(clientKey);

            String role = account.getRole();
            if (role != null && !role.startsWith("ROLE_")) {
                role = "ROLE_" + role;
            }
            List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role != null ? role : "ROLE_OPERATOR"));

            Map<String, Object> claims = Map.of(
                    "fullName", account.getFullName() != null ? account.getFullName() : "",
                    "userId", account.getId() != null ? account.getId() : "",
                    "tenantId", account.getTenantId() != null ? account.getTenantId() : "tenant_default",
                    "authorizedTenants", account.getAuthorizedTenants() != null ? account.getAuthorizedTenants() : "tenant_default"
            );

            String token = tokenProvider.generateToken(account.getUsername(), authorities, claims);
            log.info("[Auth] Operator '{}' logged in successfully with role '{}'", account.getUsername(), role);

            return ResponseEntity.ok(AuthResponse.success(
                    token,
                    tokenProvider.getValidityMillis(),
                    account.getUsername(),
                    account.getFullName(),
                    List.of(role)
            ));
        } else {
            rateLimiter.recordFailure(clientKey);
            log.warn("[Auth] Failed login attempt for username '{}' from IP {}", username, clientIp);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(AuthResponse.failure("Invalid username or password."));
        }
    }

    @PostMapping("/demo-session")
    public ResponseEntity<?> demoSession() {
        if (!environment.acceptsProfiles(Profiles.of("demo"))) {
            log.warn("[Auth] Rejected /api/auth/demo-session request because 'demo' profile is not active. Active profiles: {}",
                    (Object) environment.getActiveProfiles());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "error", "Forbidden",
                            "message", "Demo authentication is only permitted under the demo profile."
                    ));
        }

        String username = "admin";
        Optional<OperatorAccount> accountOpt = operatorRepository.findByUsername(username);
        if (accountOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Demo admin account not found. Ensure flyo.seeding.enabled=true in demo profile."));
        }

        OperatorAccount account = accountOpt.get();
        String role = account.getRole();
        if (role != null && !role.startsWith("ROLE_")) {
            role = "ROLE_" + role;
        }
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role != null ? role : "ROLE_ADMIN"));

        Map<String, Object> claims = Map.of(
                "fullName", account.getFullName() != null ? account.getFullName() : "System Admin",
                "userId", account.getId() != null ? account.getId() : "USR-DEMO",
                "tenantId", account.getTenantId() != null ? account.getTenantId() : "tenant_default",
                "authorizedTenants", account.getAuthorizedTenants() != null ? account.getAuthorizedTenants() : "*"
        );

        String token = tokenProvider.generateToken(account.getUsername(), authorities, claims);
        log.info("[Auth] Operator '{}' authenticated via demo session under 'demo' profile", account.getUsername());

        return ResponseEntity.ok(AuthResponse.success(
                token,
                tokenProvider.getValidityMillis(),
                account.getUsername(),
                account.getFullName(),
                List.of(role)
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(Map.of(
                "username", auth.getName(),
                "authorities", auth.getAuthorities().stream().map(a -> a.getAuthority()).toList(),
                "authenticated", true
        ));
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }
}
