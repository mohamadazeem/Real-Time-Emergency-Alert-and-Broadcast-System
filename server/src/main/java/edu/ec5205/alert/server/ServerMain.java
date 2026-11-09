package edu.ec5205.alert.server;

import edu.ec5205.alert.server.storage.SqliteDatabase;
import edu.ec5205.alert.server.storage.AlertRepository;
import edu.ec5205.alert.server.storage.ClientRepository;
import edu.ec5205.alert.server.storage.AcknowledgementRepository;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.SynchronousQueue;

/** Bounded concurrent JSON server with receiver registration and admin login. */
public final class ServerMain {
    private static final int PORT = 5050;

    private ServerMain() {}

    public static void main(String[] args) {
        AdminCredentials credentials;
        ServerLimits limits;
        ConnectionRegistry registry = new ConnectionRegistry();
        try {
            credentials = AdminCredentials.fromEnvironment(System.getenv());
            limits = ServerLimits.fromEnvironment(System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("Server configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }
        AlertCreationService alertCreation;
        ClientRepository clients;
        AcknowledgementService acknowledgements;
        HistoryService history;
        AcknowledgementQueryService acknowledgementQueries;
        try {
            SqliteDatabase database = new SqliteDatabase(SqliteDatabase.pathFromEnvironment(System.getenv()));
            AlertRepository alerts = new AlertRepository(database);
            alertCreation = new AlertCreationService(alerts, registry);
            clients = new ClientRepository(database);
            acknowledgements = new AcknowledgementService(alerts, new AcknowledgementRepository(database), registry);
            history = new HistoryService(alerts);
            acknowledgementQueries = new AcknowledgementQueryService(alerts, new AcknowledgementRepository(database));
        } catch (IOException | SQLException | IllegalArgumentException e) {
            System.err.println("Storage startup error: " + e.getMessage());
            System.exit(1);
            return;
        }
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            ExecutorService executor = connectionExecutor(limits);
            Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();
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
                    ClientConnection connection = new ClientConnection(socket, limits.outgoingQueueCapacity(), closed -> {
                        registry.unregister(closed);
                        connections.remove(closed);
                    });
                    connections.add(connection);
                    try {
                        executor.execute(new ClientHandler(connection, registry, credentials, alertCreation, clients, acknowledgements, history, acknowledgementQueries, () -> {}));
                    } catch (RejectedExecutionException e) {
                        System.err.println("Rejected connection from " + socket.getRemoteSocketAddress()
                                + ": server capacity exhausted or shutting down.");
                        connection.close();
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

    static ThreadPoolExecutor connectionExecutor(ServerLimits limits) {
        return new ThreadPoolExecutor(limits.maxConnections(), limits.maxConnections(), 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    }

    private static void shutdown(ServerSocket listener, ExecutorService executor, Set<ClientConnection> connections) {
        try {
            listener.close();
        } catch (IOException e) {
            System.err.println("Listener close error: " + e.getMessage());
        }
        // Interrupting workers alone does not release blocking socket reads.
        executor.shutdownNow();
        List<ClientConnection> closing = List.copyOf(connections);
        closing.forEach(ClientConnection::close);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            for (ClientConnection connection : closing) {
                long remaining = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (!connection.awaitWriter(remaining)) System.err.println("Client writer did not terminate during shutdown.");
            }
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                System.err.println("Executor did not terminate within five seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
