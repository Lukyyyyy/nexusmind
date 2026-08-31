package com.luky.nexusmind.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentPermissionPolicyTest {

    @Test
    void recognizesPrivateOrganizationTags() {
        assertTrue(DocumentPermissionPolicy.isPrivateOrgTag("PRIVATE_alice"));
        assertFalse(DocumentPermissionPolicy.isPrivateOrgTag("engineering"));
        assertFalse(DocumentPermissionPolicy.isPrivateOrgTag(null));
    }

    @Test
    void privateSpaceUploadsAreNeverPublic() {
        assertFalse(DocumentPermissionPolicy.resolveUploadVisibility("PRIVATE_alice", true));
        assertTrue(DocumentPermissionPolicy.resolveUploadVisibility("engineering", true));
    }

    @Test
    void privateDocumentsAreLimitedToOwnerAndSuperAdmin() {
        assertTrue(DocumentPermissionPolicy.canAccessPrivateDocument("alice", "alice", "USER"));
        assertFalse(DocumentPermissionPolicy.canAccessPrivateDocument("alice", "root", "ADMIN"));
        assertTrue(DocumentPermissionPolicy.canAccessPrivateDocument("alice", "root", "SUPER_ADMIN"));
        assertFalse(DocumentPermissionPolicy.canAccessPrivateDocument("alice", "bob", "USER"));
    }
}
