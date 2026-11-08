package edu.ec5205.alert.server;

import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.ProtocolMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One bounded mailbox and one socket writer. Producers never perform socket writes. */
public final class ClientConnection implements AutoCloseable {
    private final Socket socket;
    private final BlockingQueue<ProtocolMessage> outgoing;
    private final Consumer<ClientConnection> onClosed;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final Thread writer;
    private volatile boolean finishing;
    private volatile UUID receiverId;

    public ClientConnection(Socket socket, int queueCapacity, Consumer<ClientConnection> onClosed) {
        this.socket = socket;
        this.outgoing = new ArrayBlockingQueue<>(queueCapacity);
        this.onClosed = onClosed;
        this.writer = new Thread(this::writeMessages, "client-writer-" + socket.getRemoteSocketAddress());
    }
    public Socket socket() { return socket; }
    public boolean isClosed() { return closed.get(); }
    UUID receiverId() { return receiverId; }
    void bindReceiver(UUID id) { receiverId = id; }

    public void startWriter() {
        if (started.compareAndSet(false, true) && !closed.get()) writer.start();
    }

    /** Failed offers abort this connection; they never wait for queue space. */
    public boolean send(ProtocolMessage message) {
        if (closed.get() || finishing) return false;
        if (outgoing.offer(message)) return true;
        System.err.println("Outgoing queue full; disconnecting " + socket.getRemoteSocketAddress());
        close();
        return false;
    }

    private void writeMessages() {
        JsonMessageCodec codec = new JsonMessageCodec();
        try (OutputStream output = socket.getOutputStream()) {
            while (!closed.get()) {
                ProtocolMessage message;
                try {
                    message = outgoing.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (closed.get()) break;
                    continue;
                }
                if (message != null) codec.write(output, message);
                if (finishing && outgoing.isEmpty()) break;
            }
        } catch (IOException | RuntimeException e) {
            if (!closed.get()) System.err.println("Client writer failed for " + socket.getRemoteSocketAddress());
        } finally {
            close();
        }
    }

    /** Give final ERROR replies a bounded drain; force-close a stalled writer. */
    public void finish() {
        finishing = true;
        try {
            awaitWriter(1000);
            close();
            awaitWriter(1000);
        } catch (InterruptedException e) {
            close();
            Thread.currentThread().interrupt();
        }
    }
    public boolean awaitWriter(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis > 0 && Thread.currentThread() != writer) writer.join(timeoutMillis);
        return !writer.isAlive();
    }

    /** Immediate abort on overflow, writer failure, rejection, or shutdown. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            socket.close(); // Releases blocked reads and writes.
        } catch (IOException e) {
            System.err.println("Client close failed for " + socket.getRemoteSocketAddress());
        } finally {
            outgoing.clear();
            writer.interrupt();
            onClosed.accept(this);
        }
    }
}
