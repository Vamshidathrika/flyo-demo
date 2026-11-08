package com.flyo.rescue.config;

import com.flyo.rescue.model.OperatorAccount;
import com.flyo.rescue.repository.OperatorAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Production Bootstrap Service for First-Time Setup.
 *
 * In production, ZERO hardcoded accounts or demo credentials are created.
 * If the database contains zero operator accounts, an initial administrator
 * is created ONLY if explicit, non-default credentials are provided via
 * FLYO_INITIAL_ADMIN_USERNAME and FLYO_INITIAL_ADMIN_PASSWORD.
 */
@Component
@Profile("prod")
public class ProductionAdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionAdminBootstrap.class);

    private final OperatorAccountRepository operatorAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${flyo.bootstrap.admin.username:}")
    private String bootstrapUsername;

    @Value("${flyo.bootstrap.admin.password:}")
    private String bootstrapPassword;

    public ProductionAdminBootstrap(OperatorAccountRepository operatorAccountRepository,
                                    PasswordEncoder passwordEncoder) {
        this.operatorAccountRepository = operatorAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        long existingAccounts = operatorAccountRepository.count();
        if (existingAccounts > 0) {
            log.info("[Security Bootstrap] Production database contains {} operator accounts. Zero bootstrap required.", existingAccounts);
            return;
        }

        String envUser = System.getenv("FLYO_INITIAL_ADMIN_USERNAME");
        String envPass = System.getenv("FLYO_INITIAL_ADMIN_PASSWORD");

        String username = (bootstrapUsername != null && !bootstrapUsername.isBlank()) ? bootstrapUsername : envUser;
        String password = (bootstrapPassword != null && !bootstrapPassword.isBlank()) ? bootstrapPassword : envPass;

        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            if (password.length() < 12) {
                log.error("[Security Bootstrap] Initial admin password rejected: Minimum 12 characters required for production security.");
                return;
            }
            if (password.equalsIgnoreCase("AdminSecret123!") || password.equalsIgnoreCase("Operator123!") || password.contains("password")) {
                log.error("[Security Bootstrap] Initial admin password rejected: Default or weak password detected.");
                return;
            }

            String userId = "USR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            OperatorAccount admin = new OperatorAccount(
                    userId,
                    username.trim(),
                    passwordEncoder.encode(password),
                    "Production System Administrator",
                    "ROLE_ADMIN",
                    true,
                    "0000000000",
                    "tenant_default",
                    "*"
            );
            operatorAccountRepository.save(admin);
            log.info("[Security Bootstrap] Initial production administrator account created: '{}' (ID: {}).", username, userId);
        } else {
            log.info("[Security Bootstrap] Clean production startup: 0 operator accounts exist. No bootstrap credentials specified. Production starts with clean empty state.");
        }
    }
}
