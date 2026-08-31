package com.luky.nexusmind.service;

import com.luky.nexusmind.model.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationPermissionTest {
    @Test
    void onlySuperAdminCanBypassAnotherUsersPrivateSpace() {
        assertFalse(DocumentPermissionPolicy.canAccessPrivateDocument("owner", "admin", "ADMIN"));
        assertTrue(DocumentPermissionPolicy.canAccessPrivateDocument("owner", "root", "SUPER_ADMIN"));
        assertTrue(DocumentPermissionPolicy.canAccessPrivateDocument("owner", "owner", "USER"));
        assertTrue(User.Role.ADMIN.isAdministrator());
        assertTrue(User.Role.SUPER_ADMIN.isAdministrator());
        assertFalse(User.Role.USER.isAdministrator());
    }
}
