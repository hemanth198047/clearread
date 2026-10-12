package com.clearread.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMembershipRoleTest {

    @Test
    void seededAdminAndDemoAreAdmins() {
        assertThat(DefaultMembershipRole.resolve("admin", null)).isEqualTo("admin");
        assertThat(DefaultMembershipRole.resolve("demo", "viewer")).isEqualTo("admin");
        assertThat(DefaultMembershipRole.resolve("Admin", null)).isEqualTo("admin");
    }

    @Test
    void seededUserIsAuthor() {
        assertThat(DefaultMembershipRole.resolve("user", null)).isEqualTo("author");
        assertThat(DefaultMembershipRole.resolve("user", "admin")).isEqualTo("author");
    }

    @Test
    void selfRegisteredUsesInviteOrAuthor() {
        assertThat(DefaultMembershipRole.resolve("alice", null)).isEqualTo("author");
        assertThat(DefaultMembershipRole.resolve("alice", "viewer")).isEqualTo("viewer");
        assertThat(DefaultMembershipRole.resolve(null, "admin")).isEqualTo("admin");
    }
}
