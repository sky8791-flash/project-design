package com.collabdoc.security;

import com.collabdoc.entity.User;
import com.collabdoc.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Turns a bearer token into an {@link AuthUser}.
 *
 * <p>The account is re-read on every request rather than trusting the token's claims: that is what makes
 * disabling or demoting a user take effect immediately instead of whenever their token happens to expire.
 * The token only has to prove who is calling; the database decides what they may still do.</p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        AuthUser caller = resolve(request);
        if (caller != null) {
            var authentication = new UsernamePasswordAuthenticationToken(caller, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + caller.role())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    /** Exposed so the WebSocket handshake interceptor can authenticate a socket the same way. */
    public AuthUser resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return authenticate(header != null && header.startsWith("Bearer ") ? header.substring(7) : null);
    }

    public AuthUser authenticate(String token) {
        AuthUser claimed = jwtService.parse(token);
        if (claimed == null || claimed.id() == null) return null;

        return userRepository.findById(claimed.id())
                .filter(User::isEnabled)
                .map(user -> new AuthUser(user.getId(), user.getUsername(), user.getRole().name()))
                .orElse(null);
    }
}
