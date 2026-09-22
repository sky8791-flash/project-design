package com.collabdoc.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The token is the only thing standing between a caller and another user's documents, so the parser has
 * to reject everything that is not a currently valid, correctly signed access token.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-value-that-is-long-enough-32";
    private static final String OTHER_SECRET = "a-completely-different-but-still-32-char-secret";

    private final JwtService service = new JwtService(SECRET, Duration.ofHours(1), "test");

    @Test
    void roundTripsIdentityAndRole() {
        AuthUser parsed = service.parse(service.issue(42L, "alice", "ADMIN"));

        assertThat(parsed).isEqualTo(new AuthUser(42L, "alice", "ADMIN"));
    }

    @Test
    void refusesAHandCraftedUnsignedToken() {
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"1\",\"role\":\"ADMIN\",\"typ\":\"access\"}".getBytes());

        assertThat(service.parse(header + "." + payload + ".")).isNull();
    }

    @Test
    void refusesATokenSignedWithAnotherKey() {
        String foreign = new JwtService(OTHER_SECRET, Duration.ofHours(1), "test").issue(1L, "eve", "ADMIN");

        assertThat(service.parse(foreign)).isNull();
    }

    @Test
    void acceptsATokenInsideTheClockSkewWindowButRefusesOneBeyondIt() {
        // The parser allows 60s of clock skew, so "expired 10s ago" is deliberately still accepted;
        // the boundary that matters is past that window.
        String justExpired = new JwtService(SECRET, Duration.ofSeconds(-10), "test").issue(1L, "alice", "USER");
        assertThat(service.parse(justExpired)).isNotNull();

        String longExpired = new JwtService(SECRET, Duration.ofSeconds(-600), "test").issue(1L, "alice", "USER");
        assertThat(service.parse(longExpired)).isNull();
    }

    @Test
    void refusesGarbage() {
        assertThat(service.parse(null)).isNull();
        assertThat(service.parse("")).isNull();
        assertThat(service.parse("not.a.jwt")).isNull();
    }

    @Test
    void refusesToRunOnThePublishedDevelopmentSecretOutsideDev() {
        assertThatThrownBy(() -> new JwtService(
                "collabdoc-development-only-secret-change-me-32b", Duration.ofHours(1), "prod"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void refusesAShortSecret() {
        assertThatThrownBy(() -> new JwtService("too-short", Duration.ofHours(1), "prod"))
            .isInstanceOf(IllegalStateException.class);
    }
}
