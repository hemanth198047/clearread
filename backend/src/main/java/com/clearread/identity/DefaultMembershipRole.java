package com.clearread.identity;

/**
 * Resolves the first-login workspace role for a newly provisioned user.
 * Seeded Keycloak demo usernames map to fixed roles; otherwise prefer a pending invite, else author.
 */
public final class DefaultMembershipRole {

    private DefaultMembershipRole() {
    }

    /**
     * @param preferredUsername OIDC preferred_username (may be null)
     * @param inviteRole        role from a matching pending invite, or null
     */
    public static String resolve(String preferredUsername, String inviteRole) {
        String username = preferredUsername == null ? "" : preferredUsername.trim().toLowerCase();
        return switch (username) {
            case "admin", "demo" -> "admin";
            case "user" -> "author";
            default -> {
                if (inviteRole != null && !inviteRole.isBlank()) {
                    yield inviteRole;
                }
                yield "author";
            }
        };
    }
}
