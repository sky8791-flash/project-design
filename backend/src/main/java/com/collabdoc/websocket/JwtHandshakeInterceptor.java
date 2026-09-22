package com.collabdoc.websocket;

import com.collabdoc.security.JwtAuthFilter;
import com.collabdoc.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Authenticates the socket upgrade. A browser cannot set an Authorization header on a WebSocket, so the
 * token travels as a {@code token} query parameter and becomes a session attribute here — the handler then
 * reads the id from the attributes and never from a caller-supplied {@code userId}.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTRIBUTE_USER = "authenticatedUser";
    public static final String ATTRIBUTE_TOKEN = "bearerToken";

    private final JwtAuthFilter jwtAuthFilter;

    public JwtHandshakeInterceptor(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String token = tokenFrom(request);
        AuthUser caller = jwtAuthFilter.authenticate(token);
        if (caller == null) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTRIBUTE_USER, caller);
        attributes.put(ATTRIBUTE_TOKEN, token);
        // The token is in the URL, so nothing may cache a handshake that carries it.
        response.getHeaders().setCacheControl("no-store");
        return true;
    }

    /**
     * Uses the servlet parameter rather than hand-splitting the query string, so a percent-encoded token
     * and repeated parameters are decoded exactly as the HTTP layer decodes them.
     */
    private String tokenFrom(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            return servletRequest.getServletRequest().getParameter("token");
        }
        String query = request.getURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            if (pair.startsWith("token=")) return pair.substring("token=".length());
        }
        return null;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // nothing to clean up
    }
}
