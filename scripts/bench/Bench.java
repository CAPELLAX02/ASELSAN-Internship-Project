import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Benchmark harness for the DKM gateway. No build step and no dependencies --
 * run it straight from source:
 *
 * <pre>
 *   java Bench.java gen   &lt;file&gt; &lt;bytes&gt; [msgPerSecond]
 *   java Bench.java peer  &lt;host&gt; &lt;rsp&gt; &lt;rsm&gt; &lt;crm&gt; [--reply-hz N] [--report-ms N]
 *   java Bench.java viz   &lt;ws://host:port/ws/viz&gt; &lt;seconds&gt;
 * </pre>
 *
 * <p>It stands in for the DKM rather than simulating one: it connects out as a
 * client exactly once per link, frames the stream on {@code msg_length} alone,
 * and drains as fast as the socket will give it up. Nothing here decodes a
 * payload, so what it measures is the gateway's send path and not this
 * program's ability to keep up with it.
 *
 * <p>Numbers are reported from both ends -- the gateway's own counters and this
 * peer's independent byte count. One measurement is a claim; two that agree are
 * evidence.
 */
public final class Bench {

    // Wire layout, from interface-schema.json. Little-endian, 8-byte size_t.
    static final int HEADER = 40;
    static final int OFF_SENDER = 0;
    static final int OFF_RECEIVER = 8;
    static final int OFF_MSG_ID = 16;
    static final int OFF_TIMESTAMP = 24;
    static final int OFF_MSG_LENGTH = 32;

    static final long RDP = 1, RSP = 2, RSM = 3, CRM = 4;

    static final int DETECTION_REPORT = 192;
    static final int BEAM_REPORT = 72;
    static final int PREDICTION = 96;
    static final int MEASUREMENT_REPORT = 64;

    /** Above this a latency reading is a corrupt frame, not a slow one. */
    static final double PLAUSIBLE_LATENCY_MS = 60_000;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Histogram.selfTest();
        switch (args[0]) {
            case "gen" -> generate(args);
            case "peer" -> peer(args);
            case "viz" -> viz(args);
            default -> usage();
        }
    }

    static void usage() {
        System.err.println("""
                Bench — DKM gateway benchmark harness

                  gen  <file> <bytes> [msgPerSecond]     write a stimulus binary of about <bytes>
                  peer <host> <rsp> <rsm> <crm> [opts]   stand in for the DKM and drain all three links
                  viz  <wsUrl> <seconds> [--hgrm F] [--json F]   measure visualization frame latency

                peer options:
                  --reply-hz N     send N MeasurementReports per second back on RSM (default 0)
                  --report-ms N    print a progress line every N ms (default 1000)
                  --until-idle S   finish S seconds after the stream goes quiet
                  --hgrm FILE      write the lateness distribution (HdrHistogram format)
                  --json FILE      write the run summary as one JSON object
                  --quiet          only print the final summary
                """);
    }


    // ------------------------------------------------------------ histogram

    /**
     * Latency recorder with bounded error, so a percentile is defensible.
     *
     * <p>Keeping every sample and sorting is exact, but it needs every sample in
     * memory -- and a run that has to cap its sample count is exactly how a
     * benchmark quietly starts under-reporting its own tail. This records into
     * log-linear buckets instead: constant memory, no allocation after
     * construction, and a worst-case error under 0,1% of the value recorded.
     *
     * <p>The layout is HdrHistogram's, and {@link #writeHgrm} emits
     * HdrHistogram's percentile-distribution format, so any reading can be
     * re-plotted with the standard tools instead of being taken on trust. The
     * class is here rather than on the classpath because it is a hundred lines
     * and this harness deliberately runs from source with no build step.
     */
    static final class Histogram {
        /** 2^11 sub-buckets: relative error at most 2^-10, under one part in a thousand. */
        private static final int PRECISION_BITS = 11;
        private static final int SUB = 1 << PRECISION_BITS;
        private static final int HALF = SUB / 2;

        final long[] counts;
        private long total;
        private long min = Long.MAX_VALUE;
        private long max;
        private long rejected;

        Histogram(long highestTrackable) {
            int buckets = 1;
            while (((long) SUB << (buckets - 1)) < highestTrackable) buckets++;
            counts = new long[SUB + buckets * HALF];
        }

        /**
         * Records one value. A negative reading is a broken clock or a corrupt
         * frame, not a fast one, so it is counted separately and never folded
         * into a percentile.
         */
        void record(long value) {
            if (value < 0) {
                rejected++;
                return;
            }
            int at = indexOf(value);
            if (at >= counts.length) {
                rejected++;
                return;
            }
            counts[at]++;
            total++;
            if (value < min) min = value;
            if (value > max) max = value;
        }

        private static int indexOf(long value) {
            if (value < SUB) return (int) value;
            int bucket = (63 - Long.numberOfLeadingZeros(value)) - PRECISION_BITS + 1;
            int sub = (int) (value >>> bucket);
            return SUB + (bucket - 1) * HALF + (sub - HALF);
        }

        static long valueAt(int index) {
            if (index < SUB) return index;
            int bucket = (index - SUB) / HALF + 1;
            int sub = (index - SUB) % HALF + HALF;
            return ((long) sub) << bucket;
        }

        /** Folds an already-bucketed count in, for merging per-link histograms. */
        void add(long value, long times) {
            if (times <= 0) return;
            counts[indexOf(value)] += times;
            total += times;
            if (value < min) min = value;
            if (value > max) max = value;
        }

        long count()      { return total; }
        long rejected()   { return rejected; }
        long min()        { return total == 0 ? 0 : min; }
        long max()        { return max; }

        long percentile(double fraction) {
            if (total == 0) return 0;
            long wanted = Math.max(1, (long) Math.ceil(fraction * total));
            long seen = 0;
            for (int i = 0; i < counts.length; i++) {
                seen += counts[i];
                if (seen >= wanted) return valueAt(i);
            }
            return max;
        }

        double mean() {
            if (total == 0) return 0;
            double sum = 0;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] != 0) sum += (double) counts[i] * valueAt(i);
            }
            return sum / total;
        }

        double stdDeviation() {
            if (total == 0) return 0;
            double mean = mean();
            double sum = 0;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] != 0) {
                    double d = valueAt(i) - mean;
                    sum += d * d * counts[i];
                }
            }
            return Math.sqrt(sum / total);
        }

        /**
         * HdrHistogram's percentile-distribution format. The percentile sweep
         * doubles in resolution as it approaches 1, which is what makes the tail
         * legible on a log plot -- a linear sweep spends all its rows on the
         * part of the distribution nobody argues about.
         */
        void writeHgrm(Path path, double unitScale) throws IOException {
            StringBuilder text = new StringBuilder(
                    "       Value     Percentile TotalCount 1/(1-Percentile)\n\n");
            long cumulative = 0;
            int i = 0;
            for (double halves = 1; ; halves *= 2) {
                double p = 1.0 - 1.0 / halves;
                long value = percentile(p);
                cumulative = Math.max(1, (long) Math.ceil(p * total));
                text.append(String.format(Locale.ROOT, "%12.3f %14.6f %10d %15.2f%n",
                        value / unitScale, p, cumulative, halves));
                if (halves > total || halves > 1e7 || ++i > 40) break;
            }
            text.append(String.format(Locale.ROOT, "%12.3f %14.6f %10d %15.2f%n",
                    max / unitScale, 1.0, total, 0.0));
            text.append(String.format(Locale.ROOT,
                    "#[Mean    = %12.3f, StdDeviation   = %12.3f]%n"
                            + "#[Max     = %12.3f, Total count    = %12d]%n"
                            + "#[Buckets = %12d, SubBuckets     = %12d]%n",
                    mean() / unitScale, stdDeviation() / unitScale,
                    max / unitScale, total, counts.length / HALF, SUB));
            Files.writeString(path, text.toString());
        }

        /** Round-trips every bucket boundary. Cheap enough to run on every start. */
        static void selfTest() {
            Histogram h = new Histogram(3_600_000_000_000L);
            long worst = 0;
            for (long v = 0; v < 1L << 42; v = v < 64 ? v + 1 : v + Math.max(1, v / 997)) {
                long back = valueAt(indexOf(v));
                if (back > v) throw new AssertionError("bucket above value at " + v + ": " + back);
                if (v >= SUB) worst = Math.max(worst, (v - back) * 100_000 / v);
                h.record(v);
            }
            if (worst > 98) throw new AssertionError("relative error too large: " + worst);
        }
    }

    // ---------------------------------------------------------------- gen

    /**
     * Writes a stimulus binary of roughly {@code targetBytes}, mixing the three
     * links in the proportions a real capture has: mostly detections, a beam
     * report announcing each one, and a prediction track alongside.
     */
    static void generate(String[] args) throws IOException {
        if (args.length < 3) {
            usage();
            return;
        }
        Path path = Path.of(args[1]);
        long targetBytes = parseSize(args[2]);
        double messagesPerSecond = args.length > 3 ? Double.parseDouble(args[3]) : 10_000;

        // One cycle: 8 detections, 1 beam report, 1 prediction.
        int cycleBytes = 8 * DETECTION_REPORT + BEAM_REPORT + PREDICTION;
        long cycles = Math.max(1, targetBytes / cycleBytes);

        byte[] buffer = new byte[1 << 20];
        ByteBuffer out = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        long messages = 0;
        long bytes = 0;
        long index = 0;

        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (OutputStream file = Files.newOutputStream(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            for (long cycle = 0; cycle < cycles; cycle++) {
                long beamId = cycle % 4096;
                double heading = (cycle % 628) / 100.0;

                if (out.remaining() < cycleBytes) {
                    file.write(buffer, 0, out.position());
                    out.clear();
                }

                beamReport(out, timestamp(index++, messagesPerSecond), beamId, cycle % 2, heading);
                messages++;
                for (int d = 0; d < 8; d++) {
                    detectionReport(out, timestamp(index++, messagesPerSecond), beamId, heading,
                            200 + (cycle % 800));
                    messages++;
                }
                prediction(out, timestamp(index++, messagesPerSecond), 1 + (cycle % 32), heading,
                            200 + (cycle % 800));
                messages++;
                bytes += cycleBytes;
            }
            file.write(buffer, 0, out.position());
        }

        System.out.printf(Locale.ROOT,
                "%s yazildi (%,d mesaj, %s): %s%n  kayitli sure: %.1f s, %,.0f mesaj/s%n",
                humanBytes(bytes), messages, humanBytes(bytes), path.toAbsolutePath(),
                messages / messagesPerSecond, messagesPerSecond);
    }

    static long timestamp(long index, double messagesPerSecond) {
        return (long) (index * 1000.0 / messagesPerSecond);
    }

    static void header(ByteBuffer out, long sender, long msgId, long timestamp, long length) {
        int base = out.position();
        out.putLong(base + OFF_SENDER, sender);
        out.putLong(base + OFF_RECEIVER, RDP);
        out.putLong(base + OFF_MSG_ID, msgId);
        out.putLong(base + OFF_TIMESTAMP, timestamp);
        out.putLong(base + OFF_MSG_LENGTH, length);
    }

    static void detectionReport(ByteBuffer out, long timestamp, long beamId, double heading, double distance) {
        int base = out.position();
        header(out, RSP, 1, timestamp, DETECTION_REPORT);
        out.putLong(base + 40, beamId);
        out.putLong(base + 48, timestamp);
        out.putLong(base + 56, 3);
        for (int i = 0; i < 8; i++) {
            out.putDouble(base + 64 + i * 16, i < 3 ? distance + i * 5 : 0);
            out.putDouble(base + 72 + i * 16, i < 3 ? heading + i * 0.01 : 0);
        }
        out.position(base + DETECTION_REPORT);
    }

    static void beamReport(ByteBuffer out, long timestamp, long beamId, long beamType, double heading) {
        int base = out.position();
        header(out, RSM, 1, timestamp, BEAM_REPORT);
        out.putLong(base + 40, beamId);
        out.putLong(base + 48, timestamp);
        out.putLong(base + 56, beamType);
        out.putDouble(base + 64, heading);
        out.position(base + BEAM_REPORT);
    }

    static void prediction(ByteBuffer out, long timestamp, long trackId, double heading, double distance) {
        int base = out.position();
        header(out, CRM, 1, timestamp, PREDICTION);
        out.putLong(base + 40, trackId);
        out.putDouble(base + 48, distance);
        out.putDouble(base + 56, heading);
        out.putDouble(base + 64, distance * Math.cos(heading));
        out.putDouble(base + 72, distance * Math.sin(heading));
        out.putDouble(base + 80, 12.5);
        out.putDouble(base + 88, -4.0);
        out.position(base + PREDICTION);
    }

    // --------------------------------------------------------------- peer

    static void peer(String[] args) throws Exception {
        if (args.length < 5) {
            usage();
            return;
        }
        String host = args[1];
        int[] ports = { Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]) };
        String[] names = { "RSP", "RSM", "CRM" };

        int replyHz = 0;
        long reportMillis = 1000;
        boolean quiet = false;
        double idleSeconds = 0;
        Path hgrm = null;
        Path json = null;
        for (int i = 5; i < args.length; i++) {
            switch (args[i]) {
                case "--reply-hz" -> replyHz = Integer.parseInt(args[++i]);
                case "--report-ms" -> reportMillis = Long.parseLong(args[++i]);
                case "--until-idle" -> idleSeconds = Double.parseDouble(args[++i]);
                case "--hgrm" -> hgrm = Path.of(args[++i]);
                case "--json" -> json = Path.of(args[++i]);
                case "--quiet" -> quiet = true;
                default -> System.err.println("ignoring unknown option " + args[i]);
            }
        }

        LinkStats[] links = { new LinkStats(), new LinkStats(), new LinkStats() };
        AtomicLong[] bytes = { links[0].bytes, links[1].bytes, links[2].bytes };
        AtomicLong[] messages = { links[0].messages, links[1].messages, links[2].messages };
        AtomicLong firstByteNanos = new AtomicLong();
        AtomicLong lastByteNanos = new AtomicLong();
        AtomicLong firstTimestamp = new AtomicLong(Long.MIN_VALUE);

        SocketChannel[] channels = new SocketChannel[3];
        for (int i = 0; i < 3; i++) {
            channels[i] = SocketChannel.open();
            channels[i].socket().setTcpNoDelay(true);
            channels[i].socket().setReceiveBufferSize(4 << 20);
            channels[i].connect(new InetSocketAddress(host, ports[i]));
            System.out.printf("%s baglandi: %s:%d%n", names[i], host, ports[i]);
        }

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            Thread thread = new Thread(() -> drain(channels[index], links[index],
                    firstByteNanos, lastByteNanos, firstTimestamp), "drain-" + names[i]);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }

        final int replyRate = replyHz;
        if (replyRate > 0) {
            Thread replier = new Thread(() -> reply(channels[1], replyRate), "reply-RSM");
            replier.setDaemon(true);
            replier.start();
        }

        long previousBytes = 0;
        long previousNanos = System.nanoTime();
        long idleSince = 0;
        final long idleNanos = (long) (idleSeconds * 1e9);
        boolean interactive = idleNanos == 0;
        if (interactive) {
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    summary(names, bytes, messages, firstByteNanos.get(), lastByteNanos.get())));
        }

        while (true) {
            Thread.sleep(reportMillis);
            long now = System.nanoTime();
            long total = bytes[0].get() + bytes[1].get() + bytes[2].get();
            if (total > previousBytes) {
                idleSince = 0;
                if (!quiet) {
                    double seconds = (now - previousNanos) / 1e9;
                    System.out.printf(Locale.ROOT, "  %s/s   toplam %s   %,d mesaj%n",
                            humanBytes((long) ((total - previousBytes) / seconds)),
                            humanBytes(total),
                            messages[0].get() + messages[1].get() + messages[2].get());
                }
            } else if (!interactive && total > 0) {
                // Nothing new since the last tick. The run is over once the
                // stream has been quiet long enough that a stall cannot be
                // mistaken for the end of it.
                if (idleSince == 0) idleSince = now;
                if (now - idleSince >= idleNanos) break;
            }
            previousBytes = total;
            previousNanos = now;
        }

        summary(names, bytes, messages, firstByteNanos.get(), lastByteNanos.get());
        report(names, links, firstByteNanos.get(), lastByteNanos.get(), hgrm, json);
    }

    /**
     * Writes the run out in forms something else can read: a percentile
     * distribution per link and one JSON object for the ladder to collect.
     */
    static void report(String[] names, LinkStats[] links, long firstNanos, long lastNanos,
                       Path hgrm, Path json) throws IOException {
        Histogram late = merged(links, link -> link.late);
        Histogram early = merged(links, link -> link.early);
        Histogram jitter = rebaselined(early, late);

        long totalBytes = 0, totalMessages = 0;
        for (LinkStats link : links) {
            totalBytes += link.bytes.get();
            totalMessages += link.messages.get();
        }
        double seconds = Math.max(1e-9, (lastNanos - firstNanos) / 1e9);

        System.out.printf(Locale.ROOT,
                "  program sapmasi, en hizli transite gore (ms):"
                        + " p50 %.3f  p95 %.3f  p99 %.3f  p999 %.3f  azami %.3f%n"
                        + "    ilk mesaja gore: %,d erken, %,d gec (gec p99 %.1f ms)%n",
                jitter.percentile(0.50) / 1000.0, jitter.percentile(0.95) / 1000.0,
                jitter.percentile(0.99) / 1000.0, jitter.percentile(0.999) / 1000.0,
                jitter.max() / 1000.0,
                early.count(), late.count(), late.percentile(0.99) / 1000.0);

        if (hgrm != null) jitter.writeHgrm(hgrm, 1000.0);   // milliseconds in the file
        if (json != null) {
            StringBuilder perLink = new StringBuilder();
            for (int i = 0; i < links.length; i++) {
                perLink.append(String.format(Locale.ROOT,
                        "%s{\"link\":\"%s\",\"bytes\":%d,\"messages\":%d,\"deviationP99Micros\":%d}",
                        i == 0 ? "" : ",", names[i], links[i].bytes.get(),
                        links[i].messages.get(), links[i].deviation.percentile(0.99)));
            }
            Files.writeString(json, String.format(Locale.ROOT, """
                    {"seconds":%.6f,"bytes":%d,"messages":%d,
                     "bytesPerSecond":%.0f,"messagesPerSecond":%.0f,
                     "jitterMicros":%s,
                     "lateMicros":%s,
                     "earlyMicros":%s,
                     "links":[%s]}
                    """,
                    seconds, totalBytes, totalMessages,
                    totalBytes / seconds, totalMessages / seconds,
                    describe(jitter), describe(late), describe(early), perLink));
        }
    }

    /**
     * Re-expresses every deviation as excess over the fastest transit seen.
     *
     * <p>The origin is the first message to arrive, which makes every reading
     * relative to whatever that one message cost -- and the first message pays
     * for the first flush, so the whole run then looks uniformly early by that
     * amount. That offset is an artefact of where the clock was started, not
     * something the gateway did.
     *
     * <p>Baselining on the fastest observed transit removes it: zero now means
     * "as good as this machine ever managed", and every percentile above it is
     * jitter that has to be explained. It is the same convention ping reports
     * its minimum under, and for the same reason.
     */
    static Histogram rebaselined(Histogram early, Histogram late) {
        Histogram excess = new Histogram(600_000_000L);
        long fastest = early.count() > 0 ? -early.max() : late.min();
        for (int i = 0; i < early.counts.length; i++) {
            if (early.counts[i] != 0) excess.add(-Histogram.valueAt(i) - fastest, early.counts[i]);
        }
        for (int i = 0; i < late.counts.length; i++) {
            if (late.counts[i] != 0) excess.add(Histogram.valueAt(i) - fastest, late.counts[i]);
        }
        return excess;
    }

    /** Folds the three links' histograms into one, bucket for bucket. */
    static Histogram merged(LinkStats[] links, Function<LinkStats, Histogram> pick) {
        Histogram all = new Histogram(600_000_000L);
        for (LinkStats link : links) {
            Histogram one = pick.apply(link);
            for (int i = 0; i < one.counts.length; i++) {
                if (one.counts[i] != 0) all.add(Histogram.valueAt(i), one.counts[i]);
            }
        }
        return all;
    }

    static String describe(Histogram h) {
        return String.format(Locale.ROOT,
                "{\"p50\":%d,\"p95\":%d,\"p99\":%d,\"p999\":%d,\"max\":%d,"
                        + "\"mean\":%.1f,\"stdDev\":%.1f,\"count\":%d,\"rejected\":%d}",
                h.percentile(0.50), h.percentile(0.95), h.percentile(0.99), h.percentile(0.999),
                h.max(), h.mean(), h.stdDeviation(), h.count(), h.rejected());
    }

    /**
     * What one link measured. The histogram is written only by that link's own
     * drain thread, so it needs no synchronisation; the counters are atomic
     * because the reporting loop reads them while the drain runs.
     */
    static final class LinkStats {
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong messages = new AtomicLong();
        /**
         * Deviation from the recorded schedule, in microseconds, split by
         * direction and also kept as one absolute distribution.
         *
         * <p>Both directions are real. Late means the gateway could not keep
         * up. Early means it ran ahead, which a coalescing pacer does by
         * design: it flushes a batch when the clock reaches the batch, so every
         * message in that batch except the first goes out before its own time.
         * A single signed number would average the two into a figure that
         * looks excellent and describes nothing.
         */
        final Histogram deviation = new Histogram(600_000_000L);
        final Histogram late = new Histogram(600_000_000L);
        final Histogram early = new Histogram(600_000_000L);
    }

    /**
     * Frames on {@code msg_length} alone, counts, and measures how late each
     * message was against the timeline it was recorded on.
     *
     * <p>The lateness reading is the point of this method. Raw throughput says
     * how many bytes went past; it cannot say whether the recording was
     * reproduced. Every header carries the time the message was meant to go
     * out, so the peer can compare "when it actually arrived, measured from the
     * first message" against "when it was supposed to, measured from the same
     * message" without trusting anything the gateway reports about itself.
     * That comparison does not skip the samples that arrive during a stall,
     * which is exactly the omission that makes most latency numbers flattering.
     *
     * <p>Bodies are never copied anywhere: what this measures is the gateway's
     * send path, not this program's ability to parse one.
     */
    static void drain(SocketChannel channel, LinkStats stats,
                      AtomicLong firstByteNanos, AtomicLong lastByteNanos,
                      AtomicLong firstTimestamp) {
        final AtomicLong bytes = stats.bytes;
        final AtomicLong messages = stats.messages;
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 << 20).order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (true) {
                int read = channel.read(buffer);
                if (read < 0) {
                    return;
                }
                if (read == 0) {
                    continue;
                }
                long now = System.nanoTime();
                firstByteNanos.compareAndSet(0, now);
                lastByteNanos.set(now);
                bytes.addAndGet(read);

                buffer.flip();
                while (buffer.remaining() >= HEADER) {
                    long length = buffer.getLong(buffer.position() + OFF_MSG_LENGTH);
                    if (length < HEADER || length > (1 << 24)) {
                        System.err.println("framing lost: msg_length=" + length);
                        return;
                    }
                    if (buffer.remaining() < length) {
                        break;
                    }
                    long scheduled = buffer.getLong(buffer.position() + OFF_TIMESTAMP);
                    buffer.position(buffer.position() + (int) length);
                    messages.incrementAndGet();

                    // The first message to arrive anywhere fixes both origins,
                    // so lateness is drift from the recording rather than the
                    // cost of starting a run.
                    firstTimestamp.compareAndSet(Long.MIN_VALUE, scheduled);
                    long origin = firstByteNanos.get();
                    long base = firstTimestamp.get();
                    if (origin != 0 && base != Long.MIN_VALUE) {
                        long deviation = (now - origin) / 1000 - (scheduled - base) * 1000;
                        stats.deviation.record(Math.abs(deviation));
                        if (deviation < 0) {
                            stats.early.record(-deviation);
                        } else {
                            stats.late.record(deviation);
                        }
                    }
                }
                buffer.compact();
            }
        } catch (IOException e) {
            // Peer closed, or the run ended.
        }
    }

    /** Sends MeasurementReports back on RSM, so the capture path is exercised too. */
    static void reply(SocketChannel rsm, int hz) {
        ByteBuffer message = ByteBuffer.allocateDirect(MEASUREMENT_REPORT).order(ByteOrder.LITTLE_ENDIAN);
        long intervalNanos = 1_000_000_000L / Math.max(hz, 1);
        long next = System.nanoTime();
        long sequence = 0;
        try {
            while (true) {
                message.clear();
                message.putLong(OFF_SENDER, RDP);
                message.putLong(OFF_RECEIVER, RSM);
                message.putLong(OFF_MSG_ID, 5);
                message.putLong(OFF_TIMESTAMP, System.currentTimeMillis());
                message.putLong(OFF_MSG_LENGTH, MEASUREMENT_REPORT);
                message.putLong(40, sequence);
                message.putDouble(48, 100 + (sequence % 900));
                message.putDouble(56, (sequence % 628) / 100.0);
                message.position(0).limit(MEASUREMENT_REPORT);
                while (message.hasRemaining()) {
                    rsm.write(message);
                }
                sequence++;
                next += intervalNanos;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleep);
                } else {
                    next = System.nanoTime();
                }
            }
        } catch (IOException | InterruptedException e) {
            // Link closed or interrupted; the summary still prints.
        }
    }

    static void summary(String[] names, AtomicLong[] bytes, AtomicLong[] messages,
                        long firstNanos, long lastNanos) {
        long totalBytes = 0;
        long totalMessages = 0;
        System.out.println();
        System.out.println("── istemci ozeti ─────────────────────────────");
        for (int i = 0; i < names.length; i++) {
            System.out.printf(Locale.ROOT, "  %-4s %12s  %,12d mesaj%n",
                    names[i], humanBytes(bytes[i].get()), messages[i].get());
            totalBytes += bytes[i].get();
            totalMessages += messages[i].get();
        }
        double seconds = firstNanos == 0 ? 0 : (lastNanos - firstNanos) / 1e9;
        System.out.printf(Locale.ROOT, "  %-4s %12s  %,12d mesaj%n", "tum", humanBytes(totalBytes), totalMessages);
        if (seconds > 0.01) {
            System.out.printf(Locale.ROOT,
                    "%n  %.3f s icinde alindi%n  throughput   %s/s%n  mesaj hizi   %,.0f mesaj/s%n",
                    seconds, humanBytes((long) (totalBytes / seconds)), totalMessages / seconds);
        }
    }

    // ---------------------------------------------------------------- viz

    /**
     * Measures the visualization stream from the browser's side of the socket:
     * every frame carries the wall clock the server stamped it with, so the
     * difference on arrival is the server-to-browser half of the wire-to-pixel
     * budget. The remaining half is one animation frame, which the console
     * reports itself.
     */
    static void viz(String[] args) throws Exception {
        if (args.length < 3) {
            usage();
            return;
        }
        URI uri = URI.create(args[1]);
        long seconds = Long.parseLong(args[2]);
        Path hgrm = null;
        Path json = null;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--hgrm" -> hgrm = Path.of(args[++i]);
                case "--json" -> json = Path.of(args[++i]);
                default -> System.err.println("ignoring unknown option " + args[i]);
            }
        }

        // Recorded in microseconds even though the wire stamp is a millisecond,
        // so that this histogram and the peer's are the same unit and can sit
        // in the same table without a conversion nobody can see.
        final Histogram latencyHistogram = new Histogram(600_000_000L);
        final Object lock = new Object();
        AtomicLong frames = new AtomicLong();
        AtomicLong samples = new AtomicLong();
        AtomicLong dropped = new AtomicLong();
        AtomicLong implausible = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);

        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                        ByteBuffer frame = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        if (frame.remaining() >= 24 && frame.getInt(frame.position()) == 0x444B4D56) {
                            int base = frame.position();
                            samples.addAndGet(frame.getInt(base + 8));
                            dropped.set(frame.getInt(base + 12));
                            double latency = System.currentTimeMillis() - frame.getDouble(base + 16);
                            // A frame cannot plausibly be negative or a minute old.
                            // Anything outside that is a corrupt read, not a
                            // measurement, and averaging it in would poison the
                            // percentiles -- so it is counted, not silently kept.
                            if (latency >= 0 && latency <= PLAUSIBLE_LATENCY_MS) {
                                synchronized (lock) { latencyHistogram.record((long) (latency * 1000)); }
                            } else {
                                implausible.incrementAndGet();
                            }
                            frames.incrementAndGet();
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        System.err.println("viz socket failed: " + error);
                        done.countDown();
                    }
                })
                .get(5, TimeUnit.SECONDS);

        System.out.printf("%s olculuyor, %d s...%n", uri, seconds);
        done.await(seconds, TimeUnit.SECONDS);
        socket.abort();

        System.out.println();
        System.out.println("── gorsellestirme akisi ──────────────────────");
        System.out.printf(Locale.ROOT, "  kare     %,d / %d s (%.1f/s)%n",
                frames.get(), seconds, frames.get() / (double) seconds);
        System.out.printf(Locale.ROOT, "  ornek    %,d (%,.0f/s)%n",
                samples.get(), samples.get() / (double) seconds);
        System.out.printf(Locale.ROOT, "  dusen    %,d (yukarida)%n", dropped.get());
        if (implausible.get() > 0) {
            System.out.printf(Locale.ROOT,
                    "  %,d kare makul olmayan bir zaman damgasi bildirdi ve haric tutuldu%n",
                    implausible.get());
        }
        if (latencyHistogram.count() > 0) {
            System.out.printf(Locale.ROOT,
                    "  sunucudan tarayiciya       p50 %.1f ms   p95 %.1f ms   p99 %.1f ms   azami %.1f ms%n",
                    latencyHistogram.percentile(0.50) / 1000.0,
                    latencyHistogram.percentile(0.95) / 1000.0,
                    latencyHistogram.percentile(0.99) / 1000.0,
                    latencyHistogram.max() / 1000.0);
        } else {
            System.out.println("  hic kare gelmedi. Kosu devam ediyor mu?");
        }

        if (hgrm != null) latencyHistogram.writeHgrm(hgrm, 1000.0);
        if (json != null) {
            Files.writeString(json, String.format(Locale.ROOT, """
                    {"seconds":%d,"frames":%d,"samples":%d,"serverDropped":%d,"implausible":%d,
                     "latencyMicros":{"p50":%d,"p95":%d,"p99":%d,"p999":%d,"max":%d,
                                      "mean":%.1f,"stdDev":%.1f,"count":%d}}
                    """,
                    seconds, frames.get(), samples.get(), dropped.get(), implausible.get(),
                    latencyHistogram.percentile(0.50), latencyHistogram.percentile(0.95),
                    latencyHistogram.percentile(0.99), latencyHistogram.percentile(0.999),
                    latencyHistogram.max(), latencyHistogram.mean(),
                    latencyHistogram.stdDeviation(), latencyHistogram.count()));
        }
    }


    // -------------------------------------------------------------- utils

    static long parseSize(String raw) {
        String text = raw.trim().toUpperCase(Locale.ROOT);
        long multiplier = 1;
        if (text.endsWith("G")) {
            multiplier = 1L << 30;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("M")) {
            multiplier = 1L << 20;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("K")) {
            multiplier = 1L << 10;
            text = text.substring(0, text.length() - 1);
        }
        return (long) (Double.parseDouble(text) * multiplier);
    }

    static String humanBytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        String[] units = { "KiB", "MiB", "GiB", "TiB" };
        double scaled = value;
        int unit = -1;
        while (scaled >= 1024 && unit < units.length - 1) {
            scaled /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f %s", scaled, units[unit]);
    }
}
