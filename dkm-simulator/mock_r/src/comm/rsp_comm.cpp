#include "comm/rsp_comm.hpp"

RspComm::RspComm(core::MessageRecorder* recorder) : comm::MessageChannel(ModuleId::RDP, ModuleId::RSP, recorder)
{
}

RspComm::~RspComm()
{
    disconnect();
}

void RspComm::dispatch(const MsgHeader& header)
{
    switch (header.msg_id)
    {
        case DetectionReport::kMsgId:
        {
            DetectionReport msg = read_body<DetectionReport>(header);
            if (on_detection_report)
            {
                on_detection_report(msg);
            }
            break;
        }
        case JammerReport::kMsgId:
        {
            JammerReport msg = read_body<JammerReport>(header);
            if (on_jammer_report)
            {
                on_jammer_report(msg);
            }
            break;
        }
        default:
            skip_body(header, body_size(header) + sizeof(MsgHeader));
            break;
    }
}
