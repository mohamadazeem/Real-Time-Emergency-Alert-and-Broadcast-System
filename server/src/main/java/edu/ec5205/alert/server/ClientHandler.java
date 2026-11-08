package edu.ec5205.alert.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Handles one text line and owns the accepted client's resources. */
public final class ClientHandler implements Runnable {
    private final Socket socket;
    private final Runnable onFinished;

    public ClientHandler(Socket socket, Runnable onFinished) {
        this.socket = socket;
        this.onFinished = onFinished;
    }

    @Override
    public void run() {
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String message = reader.readLine();
            if (message == null) {
                System.out.println("Client disconnected before sending a line: " + socket.getRemoteSocketAddress());
                return;
            }

            System.out.println("Received: " + message);
            writer.write("Server received: " + message);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            System.err.println("Client I/O error from " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
        } finally {
            onFinished.run();
        }
    }
}
