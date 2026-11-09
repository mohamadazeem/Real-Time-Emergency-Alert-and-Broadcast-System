package edu.ec5205.alert.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminMainTest {

    @Test
    @DisplayName("AdminMain startup handles connection refused cleanly without crashing")
    void mainStartupConnectionRefused() throws Exception {
        int unusedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            unusedPort = s.getLocalPort();
        }

        // Simulate user typing host, port, username, password
        String simulatedInput = "localhost\n" + unusedPort + "\nadmin\nsecret\n";
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();

        try {
            System.setIn(new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));

            AdminMain.main(new String[0]);

            String stdout = capturedOut.toString(StandardCharsets.UTF_8);
            String stderr = capturedErr.toString(StandardCharsets.UTF_8);

            assertTrue(stdout.contains("Stage 1: Basic Connectivity & Admin Authentication"));
            assertTrue(stderr.contains("Connection refused") || stderr.contains("Unable to connect"));
            assertTrue(stdout.contains("Admin application terminated cleanly."));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}

