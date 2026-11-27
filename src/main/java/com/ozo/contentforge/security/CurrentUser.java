package com.ozo.contentforge.security;

import java.util.Optional;

/** The signed-in team member for the current request, set by {@link AuthenticationFilter}. */
public record CurrentUser(Long id, String email, String name, String role) {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public boolean isAdmin() {
        return UserRole.ADMIN.equals(role);
    }

    public static Optional<CurrentUser> get() {
        return Optional.ofNullable(HOLDER.get());
    }

    /** Who did it, for audit fields: the user's email, or "system" for background work. */
    public static String actor() {
        return get().map(CurrentUser::email).orElse("system");
    }

    static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    static void clear() {
        HOLDER.remove();
    }
}
