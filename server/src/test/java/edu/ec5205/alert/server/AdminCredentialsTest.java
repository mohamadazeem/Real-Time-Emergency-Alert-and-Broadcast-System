package edu.ec5205.alert.server;

import edu.ec5205.alert.common.Messages.Login;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AdminCredentialsTest {
    private static final String USER_KEY = "EMERGENCY_ADMIN_USERNAME";
    private static final String PASSWORD_KEY = "EMERGENCY_ADMIN_PASSWORD";

    @ParameterizedTest
    @ValueSource(strings = {"username-missing", "password-missing", "username-empty", "password-empty", "username-blank", "password-blank"})
    void invalidConfigurationFailsBeforeListenerStartsWithoutExposingValues(String scenario) throws Exception {
        Map<String, String> environment = new HashMap<>(Map.of(USER_KEY, "sample-admin-value", PASSWORD_KEY, "sample-private-value"));
        String key = scenario.startsWith("username") ? USER_KEY : PASSWORD_KEY;
        if (scenario.endsWith("missing")) environment.remove(key);
        else environment.put(key, scenario.endsWith("empty") ? "" : " \t ");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AdminCredentials.fromEnvironment(environment));
        assertFalse(error.getMessage().contains("sample-admin-value"));
        assertFalse(error.getMessage().contains("sample-private-value"));

        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath, ServerMain.class.getName()).redirectErrorStream(true);
        builder.environment().remove(USER_KEY);
        builder.environment().remove(PASSWORD_KEY);
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Invalid configuration must exit promptly");
            assertEquals(1, process.exitValue());
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(output.contains("Server configuration error"));
            assertTrue(output.contains(USER_KEY));
            assertTrue(output.contains(PASSWORD_KEY));
            assertFalse(output.contains("waiting for a connection"));
            assertFalse(output.contains("sample-admin-value"));
            assertFalse(output.contains("sample-private-value"));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @Test
    void credentialsMatchExactlyWithoutTrimmingAndToStringIsRedacted() {
        AdminCredentials credentials = AdminCredentials.fromEnvironment(Map.of(USER_KEY, "sample-admin", PASSWORD_KEY, " placeholder-password "));
        assertTrue(credentials.matches(new Login("sample-admin", " placeholder-password ")));
        assertFalse(credentials.matches(new Login("sample-admin", "placeholder-password")));
        assertFalse(credentials.matches(new Login("SAMPLE-ADMIN", " placeholder-password ")));
        assertFalse(credentials.toString().contains("sample-admin"));
        assertFalse(credentials.toString().contains("placeholder-password"));
    }

    @Test
    void bothMissingAreRejectedWithoutFallbackCredentials() {
        assertThrows(IllegalArgumentException.class, () -> AdminCredentials.fromEnvironment(Map.of()));
    }
}
