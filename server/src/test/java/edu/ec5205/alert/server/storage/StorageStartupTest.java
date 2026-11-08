package edu.ec5205.alert.server.storage;

import edu.ec5205.alert.server.ServerMain;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class StorageStartupTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"directory", "blocked-parent", "corrupt-file", "blank-path"})
    void storageFailureIsReportedBeforeAcceptingConnections(String scenario) throws Exception {
        Path databasePath = directory;
        if (scenario.equals("blocked-parent")) {
            Path blocker = directory.resolve("parent-is-file");
            Files.writeString(blocker, "Not a directory");
            databasePath = blocker.resolve("alerts.db");
        } else if (scenario.equals("corrupt-file")) {
            databasePath = directory.resolve("invalid.db");
            Files.writeString(databasePath, "This is not a SQLite database");
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath, ServerMain.class.getName()).redirectErrorStream(true);
        builder.environment().put("EMERGENCY_ADMIN_USERNAME", "startup-placeholder-admin");
        builder.environment().put("EMERGENCY_ADMIN_PASSWORD", "startup-placeholder-password");
        builder.environment().put("EMERGENCY_DB_PATH", scenario.equals("blank-path") ? "  " : databasePath.toString());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS), "Storage failure should exit promptly");
            assertEquals(1, process.exitValue());
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(output.contains("Storage startup error"), output);
            assertFalse(output.contains("waiting for a connection"));
            assertFalse(output.contains("startup-placeholder-admin"));
            assertFalse(output.contains("startup-placeholder-password"));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
