package com.collabdoc.service;

import com.collabdoc.entity.User;
import com.collabdoc.exception.ForbiddenException;
import com.collabdoc.exception.NotFoundException;
import com.collabdoc.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.Random;

@Service
@Transactional
public class UserService {

    private static final String LEGACY_PREFIX = "sha256:";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    /** A real digest of a throwaway value, so an unknown username still costs one bcrypt round. */
    private final String dummyHash;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(Long.toHexString(System.nanoTime()));
    }

    public User createUser(String username, String email, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException("Username already exists: " + username);
        }
        if (userRepository.existsByEmail(email)) {
            throw new IllegalStateException("Email already exists: " + email);
        }
        User user = new User(username, email, passwordEncoder.encode(password));
        user.setUserCode(generateUniqueUserCode());
        return userRepository.save(user);
    }

    private String generateUniqueUserCode() {
        Random random = new Random();
        String code;
        do {
            int num = 10000000 + random.nextInt(90000000);
            code = String.valueOf(num);
        } while (userRepository.existsByUserCode(code));
        return code;
    }

    @Transactional(readOnly = true)
    public Optional<User> findByUserCode(String userCode) {
        return userRepository.findByUserCode(userCode);
    }

    /**
     * Verifies against the stored hash. Accounts created before BCrypt adopted the {@code sha256:} marker,
     * and the bare Base64 SHA-256 digests written before that, are still accepted; on a successful legacy
     * login the row is rewritten with a BCrypt hash so the old scheme disappears account by account.
     */
    public Optional<User> authenticate(String username, String password) {
        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            return Optional.empty();
        }

        User user = found.get();
        String stored = user.getPasswordHash();

        if (passwordEncoder.matches(password, stored)) {
            return Optional.of(user);
        }
        if (isLegacySha256(stored) && sha256(password).equals(stored)) {
            user.setPasswordHash(passwordEncoder.encode(password));
            return Optional.of(userRepository.save(user));
        }
        // A legacy row skips bcrypt above, so burn the same work factor here or the pre-migration
        // accounts are exactly the ones that leak existence by response time.
        passwordEncoder.matches(password, dummyHash);
        return Optional.empty();
    }

    /** Sets a new password, used by the admin reset path. */
    public User setPassword(Long userId, String rawPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        return userRepository.save(user);
    }

    public void requireEnabled(User user) {
        if (!user.isEnabled()) {
            throw new ForbiddenException("Account disabled");
        }
    }

    public User byId(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    private boolean isLegacySha256(String stored) {
        return stored != null && (stored.startsWith(LEGACY_PREFIX) || stored.matches("^[A-Za-z0-9+/=]{44}$"));
    }

    private String sha256(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(password.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
