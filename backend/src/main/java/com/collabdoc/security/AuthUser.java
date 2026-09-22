package com.collabdoc.security;

import java.io.Serializable;

/**
 * The authenticated caller, taken from the bearer token and never from a request parameter. Controllers
 * receive it with {@code @AuthenticationPrincipal}, so an endpoint can no longer be told "act as user 7".
 */
public record AuthUser(Long id, String username, String role) implements Serializable {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
