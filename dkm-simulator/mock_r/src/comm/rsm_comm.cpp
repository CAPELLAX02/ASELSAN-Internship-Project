#include "comm/rsm_comm.hpp"

RsmComm::RsmComm(core::MessageRecorder* recorder) : comm::MessageChannel(ModuleId::RDP, ModuleId::RSM, recorder)
{
}

RsmComm::~RsmComm()
{
    disconnect();
}

void RsmComm::dispatch(const MsgHeader& header)
{
    switch (header.msg_id)
    {
        case BeamReport::kMsgId:
        {
            BeamReport msg = read_body<BeamReport>(header);
            if (on_beam_report)
            {
                on_beam_report(msg);
            }
            break;
        }
        case GateAreaMsg::kMsgId:
        {
            GateAreaMsg msg = read_body<GateAreaMsg>(header);
            if (on_gate_area_msg)
            {
                on_gate_area_msg(msg);
            }
            break;
        }
        case ReportingAreaMsg::kMsgId:
        {
            ReportingAreaMsg msg = read_body<ReportingAreaMsg>(header);
            if (on_reporting_area_msg)
            {
                on_reporting_area_msg(msg);
            }
            break;
        }
        case ReadCommand::kMsgId:
        {
            ReadCommand msg = read_body<ReadCommand>(header);
            if (on_read_command)
            {
                on_read_command(msg);
            }
            break;
        }
        case MeasurementReport::kMsgId:
        {
            MeasurementReport msg = read_body<MeasurementReport>(header);
            if (on_measurement_report)
            {
                on_measurement_report(msg);
            }
            break;
        }
        default:
            skip_body(header, body_size(header));
            break;
    }
}
