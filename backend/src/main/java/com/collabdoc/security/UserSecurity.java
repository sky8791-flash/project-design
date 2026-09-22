package com.collabdoc.security;

/** Reads the authenticated caller outside a controller signature, for guards deep in a service. */
public final class UserSecurity {

    private UserSecurity() {}

    public static Long currentUserId() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser user) {
            return user.id();
        }
        return null;
    }
}
