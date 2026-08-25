#ifndef SRC_COMM_RSP_COMM_HPP_
#define SRC_COMM_RSP_COMM_HPP_

#include <functional>

#include "comm/message_channel.hpp"
#include "core/message_recorder.hpp"
#include "interface/rsp.h"

// TCP link to the RSP peer. Callbacks fire on the receive thread whenever a
// message of that type arrives.
class RspComm : public comm::MessageChannel
{
public:
    explicit RspComm(core::MessageRecorder* recorder = nullptr);
    ~RspComm() override;

    std::function<void(const DetectionReport&)> on_detection_report;
    std::function<void(const JammerReport&)> on_jammer_report;

protected:
    void dispatch(const MsgHeader& header) override;
};

#endif /* SRC_COMM_RSP_COMM_HPP_ */
