package edu.ec5205.alert.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded concurrent JSON server; currently implements receiver registration only. */
public final class ServerMain {
    private static final int PORT = 5050;

    private ServerMain() {}

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            ExecutorService executor = new ThreadPoolExecutor(
                    4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4),
                    new ThreadPoolExecutor.AbortPolicy());
            Set<Socket> connections = ConcurrentHashMap.newKeySet();
            ConnectionRegistry registry = new ConnectionRegistry();
            Thread shutdownHook = new Thread(
                    () -> shutdown(serverSocket, executor, connections), "server-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                while (true) {
                    System.out.println("Server is listening on port " + PORT + " and waiting for a connection...");

                    Socket socket;
                    try {
                        socket = serverSocket.accept();
                    } catch (IOException e) {
                        if (!serverSocket.isClosed()) {
                            System.err.println("Listener accept error on port " + PORT + ": " + e.getMessage());
                        }
                        break;
                    }

                    System.out.println("Accepted connection from " + socket.getRemoteSocketAddress());
                    connections.add(socket);
                    try {
                        executor.execute(new ClientHandler(socket, registry, () -> connections.remove(socket)));
                    } catch (RejectedExecutionException e) {
                        System.err.println("Rejected connection from " + socket.getRemoteSocketAddress()
                                + ": server capacity exhausted or shutting down.");
                        closeSocket(socket);
                        connections.remove(socket);
                    }
                }
            } finally {
                shutdown(serverSocket, executor, connections);
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException e) {
                    // JVM shutdown is already running the hook.
                }
            }
        } catch (IOException e) {
            System.err.println("Listener I/O error on port " + PORT + ": " + e.getMessage());
        }
    }

    private static void shutdown(ServerSocket listener, ExecutorService executor, Set<Socket> connections) {
        try {
            listener.close();
        } catch (IOException e) {
            System.err.println("Listener close error: " + e.getMessage());
        }
        // Interrupting workers alone does not release blocking socket reads.
        executor.shutdownNow();
        connections.forEach(ServerMain::closeSocket);
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("Executor did not terminate within five seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            System.err.println("Client close error from " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
        }
    }
}
