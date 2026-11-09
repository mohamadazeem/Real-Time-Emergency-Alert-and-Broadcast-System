package edu.ec5205.alert.admin.network;

import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Manages the TCP socket connection between the Admin client and the server.
 * <p>
 * Enforces UTF-8 newline-delimited JSON framing (max 65,536 bytes per frame),
 * maintains a single dedicated background reader thread, ensures synchronized
 * non-interleaving outgoing writes, and supports both asynchronous event dispatching
 * and synchronous request-response queries.
 */
public final class AdminConnection implements AutoCloseable {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 5050;
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;
    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();
    private final List<PendingResponse> pendingResponses = new CopyOnWriteArrayList<>();

    private Socket socket;
    private BufferedInputStream inputStream;
    private OutputStream outputStream;
    private Thread readerThread;

    public AdminConnection() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public AdminConnection(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host cannot be null or blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535: " + port);
        }
        this.host = host.trim();
        this.port = port;
    }

    /**
     * Connects to the server with default timeout.
     */
    public void connect() throws IOException {
        connect(DEFAULT_CONNECT_TIMEOUT_MS);
    }

    /**
     * Connects to the server with specified timeout in milliseconds.
     */
    public synchronized void connect(int timeoutMs) throws IOException {
        if (isConnected()) {
            throw new IllegalStateException("Already connected to " + host + ":" + port);
        }
        closed.set(false);

        Socket newSocket = new Socket();
        try {
            newSocket.connect(new InetSocketAddress(host, port), timeoutMs);
            newSocket.setKeepAlive(true);
            newSocket.setTcpNoDelay(true);

            InputStream rawIn = newSocket.getInputStream();
            this.inputStream = new BufferedInputStream(rawIn);
            this.outputStream = newSocket.getOutputStream();
            this.socket = newSocket;

            startReaderThread();
        } catch (IOException e) {
            try {
                newSocket.close();
            } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * Starts the dedicated single reader thread for incoming frames.
     */
    private void startReaderThread() {
        readerThread = new Thread(this::readLoop, "admin-net-reader-" + host + ":" + port);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            while (!closed.get() && socket != null && !socket.isClosed()) {
                ProtocolMessage message;
                try {
                    message = codec.read(inputStream);
                } catch (EOFException e) {
                    // Incomplete frame before EOF or sudden socket drop
                    if (!closed.get()) {
                        notifyError(e);
                    }
                    break;
                } catch (ProtocolException e) {
                    // Frame exceeded 65536 bytes, malformed UTF-8, or invalid JSON
                    if (!closed.get()) {
                        notifyError(e);
                    }
                    break;
                }

                if (message == null) {
                    // Clean EOF between frames
                    break;
                }

                dispatchIncomingMessage(message);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                notifyError(e);
            }
        } finally {
            close();
        }
    }

    private void dispatchIncomingMessage(ProtocolMessage message) {
        // First check pending request-response futures
        for (PendingResponse pending : pendingResponses) {
            if (pending.matcher().test(message)) {
                pendingResponses.remove(pending);
                pending.future().complete(message);
                break;
            }
        }

        // Notify general listeners
        for (MessageListener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (Throwable t) {
                System.err.println("Uncaught exception in MessageListener: " + t.getMessage());
            }
        }
    }

    private void notifyError(Throwable error) {
        for (PendingResponse pending : pendingResponses) {
            pending.future().completeExceptionally(error);
        }
        pendingResponses.clear();

        for (MessageListener listener : listeners) {
            try {
                listener.onError(error);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Sends a protocol message over the TCP socket.
     * Guaranteed to be synchronized so writes from different threads do not interleave.
     */
    public void send(ProtocolMessage message) throws IOException {
        Objects.requireNonNull(message, "message cannot be null");
        if (!isConnected()) {
            throw new IOException("Cannot send message: Not connected to server (" + host + ":" + port + ")");
        }

        synchronized (writeLock) {
            codec.write(outputStream, message);
        }
    }

    /**
     * Sends a request message and blocks waiting for a matching response until the timeout expires.
     */
    public ProtocolMessage sendAndAwait(
            ProtocolMessage request,
            Predicate<ProtocolMessage> responseMatcher,
            Duration timeout) throws IOException, TimeoutException, InterruptedException, ProtocolException {

        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(responseMatcher, "responseMatcher cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");

        CompletableFuture<ProtocolMessage> future = new CompletableFuture<>();
        PendingResponse pending = new PendingResponse(responseMatcher, future);
        pendingResponses.add(pending);

        try {
            send(request);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingResponses.remove(pending);
            future.cancel(true);
            throw new TimeoutException("Timed out waiting for server response (" + timeout.toMillis() + " ms)");
        } catch (ExecutionException e) {
            pendingResponses.remove(pending);
            Throwable cause = e.getCause();
            if (cause instanceof ProtocolException pe) throw pe;
            if (cause instanceof IOException ioe) throw ioe;
            throw new IOException("Failed while awaiting response: " + cause.getMessage(), cause);
        } catch (CancellationException e) {
            pendingResponses.remove(pending);
            throw new IOException("Request was cancelled", e);
        }
    }

    public void addListener(MessageListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(MessageListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public boolean isConnected() {
        return !closed.get() && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public boolean isClosed() {
        return closed.get() || socket == null || socket.isClosed();
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            // Fail all pending futures
            IOException closeException = new IOException("Connection was closed");
            for (PendingResponse pending : pendingResponses) {
                pending.future().completeExceptionally(closeException);
            }
            pendingResponses.clear();

            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }

            for (MessageListener listener : listeners) {
                try {
                    listener.onClosed();
                } catch (Throwable ignored) {}
            }
        }
    }

    private record PendingResponse(Predicate<ProtocolMessage> matcher, CompletableFuture<ProtocolMessage> future) {}
}

