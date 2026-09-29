package com.arpitha.identity_service.service.impl;

import com.arpitha.identity_service.dto.LoginRequest;
import com.arpitha.identity_service.dto.RegisterRequest;
import com.arpitha.identity_service.dto.TokenResponse;
import com.arpitha.identity_service.dto.UserResponse;
import com.arpitha.identity_service.entity.Role;
import com.arpitha.identity_service.entity.UserAccount;
import com.arpitha.identity_service.exception.EmailAlreadyRegisteredException;
import com.arpitha.identity_service.exception.InvalidCredentialsException;
import com.arpitha.identity_service.repository.UserAccountRepository;
import com.arpitha.identity_service.service.AuthService;
import com.arpitha.identity_service.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    /**
     * Compared against when the email is unknown, so a login takes about as long whether or
     * not the account exists (otherwise response time would reveal registered emails).
     */
    private final String dummyPasswordHash;

    public AuthServiceImpl(UserAccountRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalize(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        UserAccount user = UserAccount.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(Role.USER)
                .build();
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two registrations for the same email at the same moment: the unique constraint decides.
            throw new EmailAlreadyRegisteredException(email);
        }
        log.info("Registered user {}", user.getId());
        return UserResponse.from(user);
    }

    @Override
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        Optional<UserAccount> user = userRepository.findByEmail(normalize(request.getEmail()));
        String hash = user.map(UserAccount::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hash);
        if (user.isEmpty() || !passwordMatches) {
            log.info("Failed login attempt");
            throw new InvalidCredentialsException();
        }
        return tokenService.issueAccessToken(user.get());
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUser(UUID id) {
        return userRepository.findById(id).map(UserResponse::from).orElseThrow(InvalidCredentialsException::new);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
