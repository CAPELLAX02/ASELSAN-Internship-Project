// Generates a binary file of raw wire-format messages that a peer simulator
// can replay to mock_r, standing in for the hand-captured input binaries the
// real DKM workflow uses.
//
// Output format is byte-identical to what mock_r's own MessageRecorder
// writes to input.bin: messages are simply concatenated, each one a raw
// MsgHeader followed by its payload, with no extra length prefix or
// delimiter (message boundaries come from sizeof(T) once msg_id identifies
// the type, same as mock_r's own read_body<T>()).
//
// This file interleaves messages from all three peer links (RSP/RSM/CRM) in
// one stream, same as mock_r's input.bin does. mock_r itself only ever
// receives each message on the one TCP socket belonging to its sender, so a
// simulator replaying this file must demux by header.sender_id (RSP=2,
// RSM=3, CRM=4 -- see ModuleId in interface/common.h) and send each message
// out on the matching socket; this generator doesn't (and can't, from a
// flat file) know which sockets exist.
//
// This is a hardcoded sequence, not a schema/config-driven engine -- add or
// edit write_msg(...) calls in main() directly to build other scenarios.

#include <cstddef>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <string>

#include "interface/common.h"
#include "interface/crm.h"
#include "interface/def.h"
#include "interface/rsm.h"
#include "interface/rsp.h"

namespace
{

    // Stand-in for the millisecond timestamps MessageChannel::send() fills in on
    // the real side. Spaced 500ms apart so the gap between consecutive messages
    // means something time-wise -- the c_sim replays messages using the delta
    // between these values, scaled by its speed multiplier.
    std::size_t next_timestamp()
    {
        static std::size_t t = 1000;
        const std::size_t value = t;
        t += 500;
        return value;
    }

    // Fills header.sender_id/receiver_id/msg_id/msg_length/timestamp the same
    // way MessageChannel::send() does on the real peer side (mock_r is always
    // the receiver, ModuleId::RDP), then appends the raw struct bytes to `out`.
    template <typename T>
    void write_msg(std::ofstream &out, T msg, ModuleId sender)
    {
        msg.header.sender_id = static_cast<std::size_t>(sender);
        msg.header.receiver_id = static_cast<std::size_t>(ModuleId::RDP);
        msg.header.msg_id = T::kMsgId;
        msg.header.msg_length = sizeof(T);
        msg.header.timestamp = next_timestamp();
        out.write(reinterpret_cast<const char *>(&msg), sizeof(T));
    }

} // namespace

int main(int argc, char **argv)
{
    const std::string output_path = (argc > 1) ? argv[1] : "input.bin";

    std::ofstream out(output_path, std::ios::binary | std::ios::trunc);
    if (!out)
    {
        std::cerr << "Failed to open " << output_path << " for writing\n";
        return 1;
    }

    // --- RSM: gate/reporting area setup, then two beams -------------------

    GateAreaMsg gate{};
    gate.start_distance = 500.0;
    gate.end_distance = 600.0;
    gate.start_heading = 1.0;
    gate.end_heading = 1.2;
    write_msg(out, gate, ModuleId::RSM);

    ReportingAreaMsg reporting_area{};
    reporting_area.start_x = -1000.0;
    reporting_area.end_x = 1000.0;
    reporting_area.start_y = -1000.0;
    reporting_area.end_y = 1000.0;
    write_msg(out, reporting_area, ModuleId::RSM);

    ReadCommand read_command{};
    write_msg(out, read_command, ModuleId::RSM);

    // beam_type 0 -> processing averages all of this beam's detection inputs.
    BeamReport beam1{};
    beam1.beam_id = 1;
    beam1.beam_timestamp = 2000;
    beam1.beam_type = 0;
    beam1.beam_heading = 0.5;
    write_msg(out, beam1, ModuleId::RSM);

    // beam_type 1 -> processing picks whichever detection input is closest
    // to the average, instead of averaging them.
    BeamReport beam2{};
    beam2.beam_id = 2;
    beam2.beam_timestamp = 2100;
    beam2.beam_type = 1;
    beam2.beam_heading = 0.8;
    write_msg(out, beam2, ModuleId::RSM);

    // --- RSP: detections referencing the beams above, plus a jammer report

    // Averages to a point outside the gate area above and inside the
    // reporting area, so mock_r should emit a MeasurementReport for this one.
    DetectionReport det1{};
    det1.beam_id = 1;
    det1.detection_timestamp = 2050;
    det1.detection_count = 3;
    det1.detections[0] = {90.0, 0.35};
    det1.detections[1] = {100.0, 0.40};
    det1.detections[2] = {110.0, 0.45};
    write_msg(out, det1, ModuleId::RSP);

    // Same idea for beam 2, exercising the closest-to-average path instead
    // of the plain-average one.
    DetectionReport det2{};
    det2.beam_id = 2;
    det2.detection_timestamp = 2150;
    det2.detection_count = 3;
    det2.detections[0] = {200.0, 0.75};
    det2.detections[1] = {205.0, 0.80};
    det2.detections[2] = {260.0, 0.95};
    write_msg(out, det2, ModuleId::RSP);

    // beam_id 99 was never announced via BeamReport, so processing should
    // drop this one (returns nullopt, no MeasurementReport out).
    DetectionReport det_unknown_beam{};
    det_unknown_beam.beam_id = 99;
    det_unknown_beam.detection_timestamp = 2200;
    det_unknown_beam.detection_count = 1;
    det_unknown_beam.detections[0] = {50.0, 0.1};
    write_msg(out, det_unknown_beam, ModuleId::RSP);

    JammerReport jammer{};
    jammer.beam_id = 1;
    jammer.jammer_timestamp = 2300;
    write_msg(out, jammer, ModuleId::RSP);

    // --- CRM ----------------------------------------------------------------

    Prediction prediction{};
    prediction.track_id = 1;
    prediction.distance = 150.0;
    prediction.heading = 0.5;
    prediction.pos_x = 130.0;
    prediction.pos_y = 75.0;
    prediction.vel_x = 1.5;
    prediction.vel_y = -0.5;
    write_msg(out, prediction, ModuleId::CRM);

    out.close();
    std::cout << "Wrote sequence to " << output_path << "\n";
    return 0;
}
