package edu.ec5205.alert.admin;

import edu.ec5205.alert.admin.model.AdminSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminSessionTest {

    @Test
    @DisplayName("AdminSession initializes with username and active state")
    void initializesCorrectly() {
        AdminSession session = new AdminSession("admin");
        assertEquals("admin", session.username());
        assertNotNull(session.authenticatedAt());
        assertTrue(session.isActive());
    }

    @Test
    @DisplayName("AdminSession rejects null username")
    void rejectsNullUsername() {
        assertThrows(NullPointerException.class, () -> new AdminSession(null));
    }

    @Test
    @DisplayName("AdminSession can be invalidated")
    void invalidationWorks() {
        AdminSession session = new AdminSession("security_officer");
        assertTrue(session.isActive());
        session.invalidate();
        assertFalse(session.isActive());
    }

    @Test
    @DisplayName("AdminSession toString does not expose secrets")
    void toStringRedaction() {
        AdminSession session = new AdminSession("admin_user");
        String str = session.toString();
        assertTrue(str.contains("admin_user"));
        assertTrue(str.contains("active=true"));
        assertFalse(str.toLowerCase().contains("password"));
        assertFalse(str.toLowerCase().contains("secret"));
    }
}

