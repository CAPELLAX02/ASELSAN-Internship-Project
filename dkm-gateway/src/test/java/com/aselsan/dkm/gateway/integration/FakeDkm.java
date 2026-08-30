package com.aselsan.dkm.gateway.integration;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for the DKM in an end-to-end test: connects out to the simulator's
 * three listening ports exactly once, the way {@code MessageChannel::connect()}
 * does, and frames what arrives using {@code msg_length} alone.
 *
 * <p>Deliberately the client. If this were a server, the test would pass while
 * the real topology was backwards -- which is exactly the mistake the earlier
 * drafts of the requirements made.
 */
public final class FakeDkm implements AutoCloseable {

    /** One received message, with when it arrived, so ordering can be asserted. */
    public record Received(String link, long msgId, long timestamp, byte[] bytes, long arrivedNanos) {
    }

    private final int headerSize;
    private final int msgIdOffset;
    private final int timestampOffset;
    private final int msgLengthOffset;

    private final List<Socket> sockets = new ArrayList<>();
    private final List<Thread> readers = new ArrayList<>();
    private final CopyOnWriteArrayList<Received> received = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    public FakeDkm(int headerSize, int msgIdOffset, int timestampOffset, int msgLengthOffset) {
        this.headerSize = headerSize;
        this.msgIdOffset = msgIdOffset;
        this.timestampOffset = timestampOffset;
        this.msgLengthOffset = msgLengthOffset;
    }

    public Socket connect(String linkName, String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 3000);
        sockets.add(socket);

        Thread reader = new Thread(() -> read(linkName, socket), "fake-dkm-" + linkName);
        reader.setDaemon(true);
        reader.start();
        readers.add(reader);
        return socket;
    }

    private void read(String linkName, Socket socket) {
        try (InputStream raw = socket.getInputStream()) {
            DataInputStream in = new DataInputStream(raw);
            byte[] header = new byte[headerSize];
            while (running) {
                in.readFully(header);
                long length = readUnsigned(header, msgLengthOffset);
                byte[] message = new byte[(int) length];
                System.arraycopy(header, 0, message, 0, headerSize);
                in.readFully(message, headerSize, (int) length - headerSize);
                received.add(new Received(linkName, readUnsigned(message, msgIdOffset),
                        readUnsigned(message, timestampOffset), message, System.nanoTime()));
            }
        } catch (IOException e) {
            // Peer closed, or the test finished. Either way there is nothing to do.
        }
    }

    /** Sends a raw message back, the way the DKM's own {@code send()} does. */
    public void send(Socket socket, byte[] message) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(message);
        out.flush();
    }

    private static long readUnsigned(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Received> receivedOn(String link) {
        return received.stream().filter(r -> r.link().equals(link)).toList();
    }

    public boolean awaitMessages(int count, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (received.size() >= count) {
                return true;
            }
            Thread.sleep(10);
        }
        return received.size() >= count;
    }

    @Override
    public void close() {
        running = false;
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing is best-effort; the test is already over.
            }
        }
    }
}
