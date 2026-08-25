// Peer simulator for mock_r: listens on the RSP/RSM/CRM ports mock_r
// connects out to (see mock_r/main.cpp), replays a bin_gen-produced message
// file to each link at a speed-scaled pace, and prints whatever mock_r sends
// back on any of them.
//
// mock_r's MessageChannel::connect() attempts a connection exactly once and
// gives up on failure (no retry) -- this simulator must already be listening
// on all three ports before mock_r is started, or those links will simply
// never come up.
//
// Usage: c_sim.exe <messages.bin> [speed] [host]
//   messages.bin  file produced by bin_gen (or a captured input.bin) --
//                 sender_id in each message's header decides which link
//                 (RSP/RSM/CRM) it's replayed on.
//   speed         playback speed multiplier, default 1.0. Applied to each
//                 message's header.timestamp offset from the earliest
//                 timestamp in the whole file: 2.0 sends twice as fast, 0.5
//                 half as fast.
//   host          interface to bind, default 127.0.0.1.
//
// All three links share one replay clock keyed off header.timestamp (set by
// bin_gen as one global counter across every message, regardless of link) --
// not a per-link "delay since the last message on this link" clock. Structs
// like DetectionReport reference a beam_id that must have already arrived as
// a BeamReport on a *different* link, so pacing each link purely off its own
// previous send would let a fast-moving link race ahead of a slow one and
// silently break that cross-link ordering.

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "interface/common.h"
#include "interface/crm.h"
#include "interface/rsm.h"
#include "interface/rsp.h"

#include "tcp_server.hpp"

namespace
{

    struct LinkConfig
    {
        const char *name;
        ModuleId module;
        std::uint16_t port;
    };

    // Matches mock_r/main.cpp's kRspPort/kRsmPort/kCrmPort and each Comm
    // subclass's (local, remote) ModuleId pair.
    constexpr std::array<LinkConfig, 3> kLinks{{
        {"RSP", ModuleId::RSP, 5001},
        {"RSM", ModuleId::RSM, 5002},
        {"CRM", ModuleId::CRM, 5003},
    }};

    struct RawMessage
    {
        MsgHeader header{};
        std::vector<std::uint8_t> bytes; // full message on the wire: header + body
    };

    std::mutex g_log_mutex;

    void log(const std::string &link, const char *arrow, const std::string &text)
    {
        using namespace std::chrono;
        const auto now = system_clock::now();
        const auto ms = duration_cast<milliseconds>(now.time_since_epoch()).count() % 1000;
        const std::time_t sec = system_clock::to_time_t(now);
        std::tm tm{};
        localtime_s(&tm, &sec);

        std::lock_guard<std::mutex> lock(g_log_mutex);
        std::cout << std::put_time(&tm, "%H:%M:%S") << '.' << std::setw(3) << std::setfill('0') << ms << "  " << link
                  << " " << arrow << " " << text << '\n';
    }

    [[noreturn]] void fail(const std::string &msg)
    {
        std::cerr << "Error: " << msg << '\n';
        std::exit(1);
    }

    // Splits `path` into its constituent messages using only header.msg_length
    // (the full on-wire size of each message, header included -- see
    // MessageChannel::send()), so this needs no per-type size table and stays
    // correct even for message types this file doesn't otherwise know about.
    std::vector<RawMessage> load_messages(const std::string &path)
    {
        std::ifstream in(path, std::ios::binary);
        if (!in)
        {
            fail("failed to open " + path);
        }

        std::vector<RawMessage> result;
        while (true)
        {
            MsgHeader header{};
            in.read(reinterpret_cast<char *>(&header), sizeof(header));
            const std::streamsize got = in.gcount();
            if (got == 0)
            {
                break;
            }
            if (got != sizeof(header))
            {
                fail(path + ": truncated message header at end of file");
            }
            if (header.msg_length < sizeof(header))
            {
                fail(path + ": message claims msg_length smaller than a header");
            }

            RawMessage msg;
            msg.header = header;
            msg.bytes.resize(header.msg_length);
            std::memcpy(msg.bytes.data(), &header, sizeof(header));

            const std::size_t body_len = header.msg_length - sizeof(header);
            if (body_len > 0)
            {
                in.read(reinterpret_cast<char *>(msg.bytes.data() + sizeof(header)),
                        static_cast<std::streamsize>(body_len));
                if (static_cast<std::size_t>(in.gcount()) != body_len)
                {
                    fail(path + ": truncated message body");
                }
            }
            result.push_back(std::move(msg));
        }
        return result;
    }

    // --- Per-message-type pretty printing ---------------------------------------

    std::string format(const BeamReport &m)
    {
        std::ostringstream ss;
        ss << "BeamReport{beam_id=" << m.beam_id << " beam_timestamp=" << m.beam_timestamp
           << " beam_type=" << m.beam_type << " beam_heading=" << m.beam_heading << "}";
        return ss.str();
    }

    std::string format(const GateAreaMsg &m)
    {
        std::ostringstream ss;
        ss << "GateAreaMsg{start_distance=" << m.start_distance << " end_distance=" << m.end_distance
           << " start_heading=" << m.start_heading << " end_heading=" << m.end_heading << "}";
        return ss.str();
    }

    std::string format(const ReportingAreaMsg &m)
    {
        std::ostringstream ss;
        ss << "ReportingAreaMsg{start_x=" << m.start_x << " end_x=" << m.end_x << " start_y=" << m.start_y
           << " end_y=" << m.end_y << "}";
        return ss.str();
    }

    std::string format(const ReadCommand &)
    {
        return "ReadCommand{}";
    }

    std::string format(const MeasurementReport &m)
    {
        std::ostringstream ss;
        ss << "MeasurementReport{measurement_timestamp=" << m.measurement_timestamp << " distance=" << m.distance
           << " heading=" << m.heading << "}";
        return ss.str();
    }

    std::string format(const DetectionReport &m)
    {
        std::ostringstream ss;
        ss << "DetectionReport{beam_id=" << m.beam_id << " detection_timestamp=" << m.detection_timestamp
           << " detection_count=" << m.detection_count << " detections=[";
        const std::size_t count = std::min<std::size_t>(m.detection_count, m.detections.size());
        for (std::size_t i = 0; i < count; ++i)
        {
            if (i != 0)
            {
                ss << ", ";
            }
            ss << "(" << m.detections[i].distance << ", " << m.detections[i].heading << ")";
        }
        ss << "]}";
        return ss.str();
    }

    std::string format(const JammerReport &m)
    {
        std::ostringstream ss;
        ss << "JammerReport{beam_id=" << m.beam_id << " jammer_timestamp=" << m.jammer_timestamp << "}";
        return ss.str();
    }

    std::string format(const Prediction &m)
    {
        std::ostringstream ss;
        ss << "Prediction{track_id=" << m.track_id << " distance=" << m.distance << " heading=" << m.heading
           << " pos_x=" << m.pos_x << " pos_y=" << m.pos_y << " vel_x=" << m.vel_x << " vel_y=" << m.vel_y << "}";
        return ss.str();
    }

    template <typename T>
    std::string format_bytes(const std::uint8_t *bytes)
    {
        T msg{};
        std::memcpy(&msg, bytes, sizeof(T));
        return format(msg);
    }

    // Interprets a raw message as the type its (module, msg_id) implies -- the
    // same routing each xxx_comm.cpp's dispatch() does -- for display purposes
    // only. Anything unrecognized, or whose length doesn't match the type its
    // msg_id implies, prints generically rather than being cast.
    std::string describe(ModuleId module, std::size_t msg_id, const std::uint8_t *bytes, std::size_t len)
    {
        switch (module)
        {
        case ModuleId::RSP:
            switch (msg_id)
            {
            case DetectionReport::kMsgId:
                if (len == sizeof(DetectionReport))
                    return format_bytes<DetectionReport>(bytes);
                break;
            case JammerReport::kMsgId:
                if (len == sizeof(JammerReport))
                    return format_bytes<JammerReport>(bytes);
                break;
            }
            break;
        case ModuleId::RSM:
            switch (msg_id)
            {
            case BeamReport::kMsgId:
                if (len == sizeof(BeamReport))
                    return format_bytes<BeamReport>(bytes);
                break;
            case GateAreaMsg::kMsgId:
                if (len == sizeof(GateAreaMsg))
                    return format_bytes<GateAreaMsg>(bytes);
                break;
            case ReportingAreaMsg::kMsgId:
                if (len == sizeof(ReportingAreaMsg))
                    return format_bytes<ReportingAreaMsg>(bytes);
                break;
            case ReadCommand::kMsgId:
                if (len == sizeof(ReadCommand))
                    return format_bytes<ReadCommand>(bytes);
                break;
            case MeasurementReport::kMsgId:
                if (len == sizeof(MeasurementReport))
                    return format_bytes<MeasurementReport>(bytes);
                break;
            }
            break;
        case ModuleId::CRM:
            switch (msg_id)
            {
            case Prediction::kMsgId:
                if (len == sizeof(Prediction))
                    return format_bytes<Prediction>(bytes);
                break;
            }
            break;
        default:
            break;
        }

        std::ostringstream ss;
        ss << "Unknown{msg_id=" << msg_id << " len=" << len << "}";
        return ss.str();
    }

    // --- Per-link send/receive loops, one pair of threads per accepted link ----

    using Clock = std::chrono::steady_clock;

    void send_loop(const std::string &link_name, ModuleId module, TcpConnection &conn,
                   const std::vector<RawMessage> &messages, double speed, std::size_t replay_epoch_timestamp,
                   Clock::time_point replay_start)
    {
        for (const RawMessage &msg : messages)
        {
            const long long offset_ms =
                std::max<long long>(0, static_cast<long long>(msg.header.timestamp) -
                                           static_cast<long long>(replay_epoch_timestamp));
            const auto scaled_offset =
                std::chrono::milliseconds(static_cast<long long>(static_cast<double>(offset_ms) / speed));
            std::this_thread::sleep_until(replay_start + scaled_offset);

            if (!conn.send_all(msg.bytes.data(), msg.bytes.size()))
            {
                log(link_name, "->", "send failed (peer disconnected?) -- stopping replay");
                return;
            }
            log(link_name, "->", describe(module, msg.header.msg_id, msg.bytes.data(), msg.bytes.size()));
        }
        log(link_name, "--", "replay finished (" + std::to_string(messages.size()) + " message(s) sent)");
    }

    void receive_loop(const std::string &link_name, ModuleId module, TcpConnection &conn)
    {
        while (true)
        {
            MsgHeader header{};
            if (!conn.recv_all(&header, sizeof(header)))
            {
                log(link_name, "<-", "connection closed");
                return;
            }
            if (header.msg_length < sizeof(header))
            {
                log(link_name, "<-", "malformed header (msg_length too small) -- stopping");
                return;
            }

            std::vector<std::uint8_t> bytes(header.msg_length);
            std::memcpy(bytes.data(), &header, sizeof(header));

            const std::size_t body_len = header.msg_length - sizeof(header);
            if (body_len > 0 && !conn.recv_all(bytes.data() + sizeof(header), body_len))
            {
                log(link_name, "<-", "connection closed mid-message");
                return;
            }
            log(link_name, "<-", describe(module, header.msg_id, bytes.data(), bytes.size()));
        }
    }

} // namespace

int main(int argc, char **argv)
{
    // Every line matters for a live-monitoring tool, and this process never
    // exits on its own -- without this, fully-buffered stdout (the default
    // once it's not attached to an interactive console, e.g. redirected to a
    // file or a pipe) would never actually flush.
    std::cout.setf(std::ios::unitbuf);

    if (argc < 2)
    {
        std::cerr << "Usage: " << argv[0] << " <messages.bin> [speed] [host]\n";
        return 1;
    }
    const std::string input_path = argv[1];

    double speed = 1.0;
    if (argc > 2)
    {
        try
        {
            speed = std::stod(argv[2]);
        }
        catch (const std::exception &)
        {
            fail(std::string("invalid speed value: ") + argv[2]);
        }
    }
    if (speed <= 0.0)
    {
        fail("speed must be > 0");
    }

    const std::string host = (argc > 3) ? argv[3] : "127.0.0.1";

    std::vector<RawMessage> all_messages = load_messages(input_path);

    std::array<std::vector<RawMessage>, kLinks.size()> per_link;
    for (RawMessage &msg : all_messages)
    {
        bool matched = false;
        for (std::size_t i = 0; i < kLinks.size(); ++i)
        {
            if (msg.header.sender_id == static_cast<std::size_t>(kLinks[i].module))
            {
                per_link[i].push_back(std::move(msg));
                matched = true;
                break;
            }
        }
        if (!matched)
        {
            std::cerr << "Warning: dropping message with unrecognized sender_id=" << msg.header.sender_id << '\n';
        }
    }

    std::size_t replay_epoch_timestamp = 0;
    if (!all_messages.empty())
    {
        // Still valid after the moves above: moving a RawMessage only empties
        // its `bytes` vector, header is a small trivial struct and is copied.
        replay_epoch_timestamp = std::min_element(all_messages.begin(), all_messages.end(),
                                                  [](const RawMessage &a, const RawMessage &b)
                                                  { return a.header.timestamp < b.header.timestamp; })
                                     ->header.timestamp;
    }

    std::cout << "Loaded " << all_messages.size() << " message(s) from " << input_path << " (speed=" << speed
              << "x)\n";
    for (std::size_t i = 0; i < kLinks.size(); ++i)
    {
        std::cout << "  " << kLinks[i].name << ": " << per_link[i].size() << " message(s)\n";
    }

    std::array<TcpServer, kLinks.size()> servers;
    for (std::size_t i = 0; i < kLinks.size(); ++i)
    {
        if (!servers[i].listen(host, kLinks[i].port))
        {
            fail(std::string("failed to listen on ") + host + ":" + std::to_string(kLinks[i].port));
        }
        std::cout << "Listening for " << kLinks[i].name << " on " << host << ":" << kLinks[i].port << '\n';
    }
    std::cout << "Waiting for mock_r to connect (start it now if it isn't running -- it only tries once)...\n";

    // Kept alive for the rest of main() so send_loop/receive_loop threads
    // can hold references into it.
    std::vector<std::unique_ptr<TcpConnection>> connections;
    connections.reserve(kLinks.size());

    for (std::size_t i = 0; i < kLinks.size(); ++i)
    {
        auto conn = servers[i].accept();
        if (!conn || !conn->is_open())
        {
            fail(std::string(kLinks[i].name) + ": accept failed");
        }
        std::cout << kLinks[i].name << ": peer connected\n";
        connections.push_back(std::move(conn));
    }

    // All links start replaying together, from this one instant -- their
    // header.timestamp-derived offsets (see send_loop) are only meaningful
    // relative to each other if every link's clock starts at the same place.
    const Clock::time_point replay_start = Clock::now();

    std::vector<std::thread> threads;
    for (std::size_t i = 0; i < kLinks.size(); ++i)
    {
        TcpConnection &conn_ref = *connections[i];
        const std::string name = kLinks[i].name;
        const ModuleId module = kLinks[i].module;
        const std::vector<RawMessage> &messages = per_link[i];

        threads.emplace_back([name, module, &conn_ref]()
                             { receive_loop(name, module, conn_ref); });
        threads.emplace_back(
            [name, module, &conn_ref, &messages, speed, replay_epoch_timestamp, replay_start]()
            { send_loop(name, module, conn_ref, messages, speed, replay_epoch_timestamp, replay_start); });
    }

    for (std::thread &t : threads)
    {
        t.join();
    }

    return 0;
}
