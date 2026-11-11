package edu.ec5205.alert.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ClientMainTest {
    @Test void headlessDefaultExplainsConsoleAndDoesNotCreateAnIdentity(@TempDir Path directory) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path identity = directory.resolve("should-not-be-created.json");
        Process process = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", cp,
                ClientMain.class.getName(), "--id-file", identity.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(output.contains("--console")); assertTrue(output.contains("graphical desktop"));
            assertFalse(Files.exists(identity));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }
}
