package edu.ec5205.alert.server;

import edu.ec5205.alert.common.Messages.Login;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Startup configuration only; never include credential values in diagnostics. */
public final class AdminCredentials {
    private final String username;
    private final byte[] password;

    private AdminCredentials(String username, String password) {
        this.username = username;
        this.password = password.getBytes(StandardCharsets.UTF_8);
    }

    public static AdminCredentials fromEnvironment(Map<String, String> environment) {
        String username = environment.get("EMERGENCY_ADMIN_USERNAME");
        String password = environment.get("EMERGENCY_ADMIN_PASSWORD");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Set non-blank EMERGENCY_ADMIN_USERNAME and EMERGENCY_ADMIN_PASSWORD environment variables.");
        }
        return new AdminCredentials(username, password);
    }

    public boolean matches(Login login) {
        return username.equals(login.username()) && MessageDigest.isEqual(
                password, login.password().getBytes(StandardCharsets.UTF_8));
    }

    @Override public String toString() { return "AdminCredentials[redacted]"; }
}
