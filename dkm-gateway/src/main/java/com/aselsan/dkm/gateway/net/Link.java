package com.aselsan.dkm.gateway.net;

import com.aselsan.dkm.gateway.schema.ModuleDef;
import io.netty.buffer.ByteBuf;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One TCP link to one peer module, standing in for that module's PCIe link on
 * the real target.
 *
 * <p>This side is the <em>server</em>. The DKM is the client, it connects out
 * exactly once at startup, and it does not retry -- so the port has to be bound
 * before the DKM process starts or that link never comes up for the rest of the
 * run (§4, FR-16/FR-17).
 *
 * <p>Reads happen on the link's Vert.x event loop and are never allowed to
 * block. Writes come from the playback pacer thread; Netty channel writes are
 * safe from any thread, and backpressure is handled by parking the pacer on
 * {@link #awaitDrain} rather than by dropping or buffering without bound --
 * stimulus fidelity matters more than never stalling.
 */
public final class Link {

    public final int index;
    public final ModuleDef module;
    public final String host;
    public final int port;

    final LongAdder bytesIn = new LongAdder();
    final LongAdder bytesOut = new LongAdder();
    final LongAdder messagesIn = new LongAdder();
    final LongAdder messagesOut = new LongAdder();
    final LongAdder writeStalls = new LongAdder();

    private final ReentrantLock drainLock = new ReentrantLock();
    private final Condition drained = drainLock.newCondition();
    private final AtomicLong drainGeneration = new AtomicLong();

    volatile LinkState state = LinkState.DOWN;
    volatile String detail = "not started";
    volatile long connectedAtMillis;
    volatile NetServer server;
    volatile NetSocket socket;
    volatile String peerAddress;

    FrameSplitter splitter;

    Link(int index, ModuleDef module, String host, int port) {
        this.index = index;
        this.module = module;
        this.host = host;
        this.port = port;
    }

    public String name() {
        return module.name();
    }

    public long moduleId() {
        return module.id();
    }

    public LinkState state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public String peerAddress() {
        return peerAddress;
    }

    public boolean isConnected() {
        return socket != null && state == LinkState.CONNECTED;
    }

    public long bytesIn() { return bytesIn.sum(); }
    public long bytesOut() { return bytesOut.sum(); }
    public long messagesIn() { return messagesIn.sum(); }
    public long messagesOut() { return messagesOut.sum(); }
    public long writeStalls() { return writeStalls.sum(); }
    public long connectedAtMillis() { return connectedAtMillis; }

    public int pendingInboundBytes() {
        FrameSplitter s = splitter;
        return s == null ? 0 : s.pendingBytes();
    }

    // ---- send path ------------------------------------------------------

    /**
     * Writes a pre-encoded slice of the replay arena. Takes ownership of
     * {@code slice}: Netty releases it once the write completes, which is also
     * what keeps the arena alive exactly as long as any write still references
     * it.
     */
    public boolean write(ByteBuf slice, int messageCount) {
        NetSocket s = socket;
        if (s == null) {
            slice.release();
            return false;
        }
        int length = slice.readableBytes();
        try {
            s.write(Buffer.buffer(slice));
        } catch (RuntimeException e) {
            return false;
        }
        bytesOut.add(length);
        messagesOut.add(messageCount);
        return true;
    }

    public boolean writeQueueFull() {
        NetSocket s = socket;
        return s != null && s.writeQueueFull();
    }

    /**
     * Parks the calling (pacer) thread until the socket's write queue drains
     * below its high-water mark, or the timeout elapses. Returns false on
     * timeout or if the link went away.
     */
    public boolean awaitDrain(long timeoutMillis) {
        NetSocket s = socket;
        if (s == null) {
            return false;
        }
        writeStalls.increment();
        long generation = drainGeneration.get();
        drainLock.lock();
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (drainGeneration.get() == generation && socket != null && writeQueueFull()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                drained.awaitNanos(remaining);
            }
            return socket != null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            drainLock.unlock();
        }
    }

    void signalDrain() {
        drainGeneration.incrementAndGet();
        drainLock.lock();
        try {
            drained.signalAll();
        } finally {
            drainLock.unlock();
        }
    }

}
