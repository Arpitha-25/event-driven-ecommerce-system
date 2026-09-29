package com.arpitha.identity_service.config;

import com.arpitha.identity_service.entity.Role;
import com.arpitha.identity_service.entity.UserAccount;
import com.arpitha.identity_service.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Creates the ADMIN account from app.admin.email / app.admin.password at startup if it doesn't
 * exist yet. Registration only ever creates USER accounts, so this is how an admin comes to be.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.info("No admin account configured (app.admin.email / app.admin.password)");
            return;
        }
        String email = adminEmail.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            return;
        }
        userRepository.save(UserAccount.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .fullName("Administrator")
                .role(Role.ADMIN)
                .build());
        log.info("Created admin account {}", email);
    }
}
