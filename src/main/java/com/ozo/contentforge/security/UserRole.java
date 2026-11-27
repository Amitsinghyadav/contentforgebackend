package com.ozo.contentforge.security;

/** Team roles. Admins also manage brands, plans and the team; team members work inside brands. */
public final class UserRole {
    public static final String ADMIN = "ADMIN";
    public static final String TEAM_MEMBER = "TEAM_MEMBER";

    private UserRole() {
    }

    public static boolean isValid(String role) {
        return ADMIN.equals(role) || TEAM_MEMBER.equals(role);
    }
}
