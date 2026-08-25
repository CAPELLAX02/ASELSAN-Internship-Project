#ifndef SRC_COMM_RSM_COMM_HPP_
#define SRC_COMM_RSM_COMM_HPP_

#include <functional>

#include "comm/message_channel.hpp"
#include "core/message_recorder.hpp"
#include "interface/rsm.h"

// TCP link to the RSM peer. Callbacks fire on the receive thread whenever a
// message of that type arrives.
class RsmComm : public comm::MessageChannel
{
public:
    explicit RsmComm(core::MessageRecorder* recorder = nullptr);
    ~RsmComm() override;

    std::function<void(const BeamReport&)> on_beam_report;
    std::function<void(const GateAreaMsg&)> on_gate_area_msg;
    std::function<void(const ReportingAreaMsg&)> on_reporting_area_msg;
    std::function<void(const ReadCommand&)> on_read_command;
    std::function<void(const MeasurementReport&)> on_measurement_report;

protected:
    void dispatch(const MsgHeader& header) override;
};

#endif /* SRC_COMM_RSM_COMM_HPP_ */
