package com.collabdoc.security;

import com.collabdoc.entity.User;
import com.collabdoc.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The token proves who is calling; the database decides what they may still do. These cases are the
 * reason the filter re-reads the row instead of trusting the token's claims.
 */
class JwtAuthFilterTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtService jwtService = new JwtService(
            "unit-test-secret-value-that-is-long-enough-32", Duration.ofHours(1), "test");
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtService, userRepository);
    }

    private String tokenFor(long userId, String role, boolean enabled) {
        User user = new User("u" + userId, "u" + userId + "@x.io", "hash");
        user.setId(userId);
        user.setRole("ADMIN".equals(role) ? User.Role.ADMIN : User.Role.USER);
        user.setEnabled(enabled);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        return jwtService.issue(userId, "u" + userId, role);
    }

    @Test
    void acceptsAnEnabledAccountAndTakesTheRoleFromTheDatabase() {
        AuthUser caller = filter.authenticate(tokenFor(7L, "USER", true));

        assertThat(caller).isEqualTo(new AuthUser(7L, "u7", "USER"));
    }

    @Test
    void refusesADisabledAccountEvenWithAnUnexpiredToken() {
        assertThat(filter.authenticate(tokenFor(8L, "ADMIN", false))).isNull();
    }

    @Test
    void refusesADeletedAccount() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());
        String token = jwtService.issue(9L, "gone", "USER");

        assertThat(filter.authenticate(token)).isNull();
    }

    @Test
    void aDemotedAdminLosesTheRoleWithoutWaitingForTokenExpiry() {
        String adminToken = tokenFor(10L, "ADMIN", true);
        assertThat(filter.authenticate(adminToken).role()).isEqualTo("ADMIN");

        User demoted = new User("u10", "u10@x.io", "hash");
        demoted.setId(10L);
        demoted.setRole(User.Role.USER);
        when(userRepository.findById(10L)).thenReturn(Optional.of(demoted));

        assertThat(filter.authenticate(adminToken).role()).isEqualTo("USER");
    }

    @Test
    void refusesAnUnsignedOrForeignTokenWithoutTouchingTheDatabase() {
        assertThat(filter.authenticate(null)).isNull();
        assertThat(filter.authenticate("nonsense")).isNull();
    }
}
