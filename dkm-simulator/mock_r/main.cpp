#include <iostream>
#include <thread>

#include "comm/crm_comm.hpp"
#include "comm/rsm_comm.hpp"
#include "comm/rsp_comm.hpp"
#include "core/beam_report_buffer.hpp"
#include "core/message_queue.hpp"
#include "core/message_recorder.hpp"
#include "core/processing.hpp"

// Placeholder host/port for each peer -- one TCP link per module, standing
// in for that module's PCIe link on the real target. Update to match
// wherever the RSP/RSM/CRM simulators actually listen.
namespace
{
constexpr const char* kRspHost   = "127.0.0.1";
constexpr std::uint16_t kRspPort = 5001;

constexpr const char* kRsmHost   = "127.0.0.1";
constexpr std::uint16_t kRsmPort = 5002;

constexpr const char* kCrmHost   = "127.0.0.1";
constexpr std::uint16_t kCrmPort = 5003;

// Recording output, mirroring the real target's input/output capture --
// one interleaved pair across all three peer links. Update to wherever
// captures for this run should land.
constexpr const char* kInputRecordingPath  = "input.bin";
constexpr const char* kOutputRecordingPath = "output.bin";
} // namespace

// ---------------------------------------------------------------------
// Threading model:
//
//   - RspComm/RsmComm/CrmComm each already run their own internal receive
//     thread (see MessageChannel::receive_loop) that does nothing but pull
//     bytes off its socket and hand complete messages to a callback --
//     these are the 3 "interface" threads, always blocked in recv(), never
//     doing real processing work.
//   - Detection, Jammer, and Prediction each get their own dedicated
//     processing thread below. An interface callback only ever pushes the
//     message onto that data path's MessageQueue and returns immediately,
//     so a slow processing pass can never stall a receive thread (and, for
//     RSP, can't block the *other* message type sharing that connection).
//     The processing thread blocks on the queue's condition variable and
//     wakes the instant something arrives -- no polling.
//   - BeamReport/GateAreaMsg/ReportingAreaMsg from RSM aren't triggers for
//     any processing thread; they're state Detection/Jammer processing
//     looks up while handling their own triggers, so RSM's callbacks just
//     write into the shared buffers below and return.
// ---------------------------------------------------------------------

int main()
{
    core::MessageRecorder recorder(kInputRecordingPath, kOutputRecordingPath);

    RspComm rsp(&recorder);
    RsmComm rsm(&recorder);
    CrmComm crm(&recorder);

    core::BeamReportBuffer beams;
    core::GateAreaBuffer gates;
    core::ReportingAreaBuffer reporting_areas;
    core::JammerBuffer jammer_buffer;
    core::PredictionBuffer prediction_buffer;

    core::MessageQueue<DetectionReport> detection_queue;
    core::MessageQueue<JammerReport> jammer_queue;
    core::MessageQueue<Prediction> prediction_queue;

    rsp.on_detection_report = [&](const DetectionReport& msg) { detection_queue.push(msg); };
    rsp.on_jammer_report    = [&](const JammerReport& msg) { jammer_queue.push(msg); };

    rsm.on_beam_report        = [&](const BeamReport& msg) { beams.put(msg); };
    rsm.on_gate_area_msg      = [&](const GateAreaMsg& msg) { gates.push(msg); };
    rsm.on_reporting_area_msg = [&](const ReportingAreaMsg& msg) { reporting_areas.push(msg); };

    crm.on_prediction = [&](const Prediction& msg) { prediction_queue.push(msg); };

    if (!rsp.connect(kRspHost, kRspPort))
    {
        std::cerr << "RSP: failed to connect to " << kRspHost << ":" << kRspPort << "\n";
    }
    if (!rsm.connect(kRsmHost, kRsmPort))
    {
        std::cerr << "RSM: failed to connect to " << kRsmHost << ":" << kRsmPort << "\n";
    }
    if (!crm.connect(kCrmHost, kCrmPort))
    {
        std::cerr << "CRM: failed to connect to " << kCrmHost << ":" << kCrmPort << "\n";
    }

    std::thread detection_thread(
        [&]
        {
            while (auto msg = detection_queue.pop())
            {
                auto measurement = core::process_detection_report(*msg, beams, gates, reporting_areas);
                if (measurement.has_value())
                {
                    rsm.send(*measurement);
                }
            }
        });

    std::thread jammer_thread(
        [&]
        {
            while (auto msg = jammer_queue.pop())
            {
                core::process_jammer_report(*msg, jammer_buffer);
            }
        });

    std::thread prediction_thread(
        [&]
        {
            while (auto msg = prediction_queue.pop())
            {
                core::process_prediction(*msg, prediction_buffer);
            }
        });

    detection_thread.join();
    jammer_thread.join();
    prediction_thread.join();

    return 0;
}
