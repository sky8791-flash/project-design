package com.collabdoc.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Signed with an HMAC secret from configuration; tokens are short-lived and carry only the identity. */
@Service
public class JwtService {

    private static final String ROLE_CLAIM = "role";
    private static final String USERNAME_CLAIM = "username";
    private static final long CLOCK_SKEW_SECONDS = 60;
    private static final String DEVELOPMENT_SECRET = "collabdoc-development-only-secret-change-me-32b";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl:8h}") Duration ttl,
                      @Value("${spring.profiles.active:}") String activeProfiles) {
        if (secret == null || secret.trim().length() < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 characters");
        }
        boolean development = activeProfiles.contains("dev") || activeProfiles.contains("local");
        if (DEVELOPMENT_SECRET.equals(secret) && !development) {
            // The fallback is public (it is committed), so booting with it anywhere real would let
            // anyone mint an ADMIN token.
            throw new IllegalStateException("app.jwt.secret is the published development value; "
                    + "set JWT_SECRET for this profile");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
    }

    public String issue(Long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(USERNAME_CLAIM, username)
                .claim(ROLE_CLAIM, role)
                // Keeps an access token from being replayed as some other kind of credential.
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Returns {@code null} for anything that is not a currently valid token. */
    public AuthUser parse(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    // Client and server clocks drift; without slack a skewed laptop is logged out at odd times.
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!"access".equals(claims.get("typ", String.class))) return null;
            return new AuthUser(Long.valueOf(claims.getSubject()),
                    claims.get(USERNAME_CLAIM, String.class),
                    claims.get(ROLE_CLAIM, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long expiresInSeconds() {
        return ttl.toSeconds();
    }
}
