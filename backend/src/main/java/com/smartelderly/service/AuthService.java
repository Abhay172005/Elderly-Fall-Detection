package com.smartelderly.service;

import com.smartelderly.config.AppProperties;
import com.smartelderly.dto.AuthDtos.AuthResponse;
import com.smartelderly.dto.AuthDtos.LoginRequest;
import com.smartelderly.dto.AuthDtos.RegisterRequest;
import com.smartelderly.dto.AuthDtos.RegistrationStatus;
import com.smartelderly.dto.AuthDtos.UserDto;
import com.smartelderly.exception.ApiException;
import com.smartelderly.model.User;
import com.smartelderly.repository.UserRepository;
import com.smartelderly.security.JwtTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registration, login (BCrypt + JWT) and simple brute-force protection. */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(10);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtTokenService tokens;
    private final AppProperties props;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    /** Compared against when the email is unknown, so failed lookups take as long as a real password check. */
    private final String dummyHash;

    private static final class Attempts {
        int failures;
        Instant lockedUntil;
    }

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtTokenService tokens, AppProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.props = props;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public AuthResponse login(LoginRequest r) {
        String email = normalize(r.email());
        Attempts a = attempts.get(email);
        if (a != null && a.lockedUntil != null && a.lockedUntil.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again in a few minutes.");
        }
        User user = users.findByEmail(email).orElse(null);
        boolean ok = encoder.matches(r.password(), user == null ? dummyHash : user.getPasswordHash()) && user != null;
        if (!ok) {
            recordFailure(email);
            throw ApiException.unauthorized("Invalid email or password.");
        }
        attempts.remove(email);
        user.setLastLoginAt(Instant.now());
        users.save(user);
        return issue(user);
    }

    public synchronized AuthResponse register(RegisterRequest r) {
        boolean first = users.count() == 0;
        if (!first && !props.security().allowRegistration()) {
            throw ApiException.forbidden("Self-registration is disabled. Ask an administrator for an account.");
        }
        String email = normalize(r.email());
        if (users.existsByEmail(email)) throw ApiException.conflict("An account with this email already exists.");
        User u = new User();
        u.setEmail(email);
        u.setFullName(r.fullName().trim());
        u.setPasswordHash(encoder.encode(r.password()));
        u.setRole(first ? User.Role.ADMIN : User.Role.CAREGIVER);
        u = users.insert(u);
        log.info("Registered new {} account", u.getRole());
        return issue(u);
    }

    public RegistrationStatus registrationStatus() {
        boolean first = users.count() == 0;
        return new RegistrationStatus(first || props.security().allowRegistration(), first);
    }

    public UserDto me(String userId) {
        return users.findById(userId).map(AuthService::toDto)
                .orElseThrow(() -> ApiException.unauthorized("Account no longer exists. Please sign in again."));
    }

    /** Creates the first administrator from ADMIN_EMAIL / ADMIN_PASSWORD when the users collection is empty. */
    public void bootstrapAdmin() {
        var sec = props.security();
        if (sec.bootstrapAdminEmail() == null || sec.bootstrapAdminEmail().isBlank()
                || sec.bootstrapAdminPassword() == null || sec.bootstrapAdminPassword().isBlank()) return;
        if (users.count() > 0) return;
        if (sec.bootstrapAdminPassword().length() < 8) {
            log.warn("ADMIN_PASSWORD must be at least 8 characters; bootstrap admin not created.");
            return;
        }
        User u = new User();
        u.setEmail(normalize(sec.bootstrapAdminEmail()));
        u.setFullName(sec.bootstrapAdminName() == null || sec.bootstrapAdminName().isBlank() ? "Administrator" : sec.bootstrapAdminName());
        u.setPasswordHash(encoder.encode(sec.bootstrapAdminPassword()));
        u.setRole(User.Role.ADMIN);
        users.insert(u);
        log.info("Bootstrap administrator account created from ADMIN_EMAIL.");
    }

    private AuthResponse issue(User u) {
        String token = tokens.issue(u);
        return new AuthResponse(token, Instant.now().plus(tokens.ttl()), toDto(u));
    }

    private void recordFailure(String email) {
        Attempts a = attempts.computeIfAbsent(email, k -> new Attempts());
        synchronized (a) {
            a.failures++;
            if (a.failures >= MAX_FAILURES) {
                a.lockedUntil = Instant.now().plus(LOCKOUT);
                a.failures = 0;
                log.warn("Login temporarily locked after repeated failures");
            }
        }
    }

    private static UserDto toDto(User u) {
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole().name());
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
