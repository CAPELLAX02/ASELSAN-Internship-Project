#include "comm/crm_comm.hpp"

CrmComm::CrmComm(core::MessageRecorder* recorder) : comm::MessageChannel(ModuleId::RDP, ModuleId::CRM, recorder)
{
}

CrmComm::~CrmComm()
{
    disconnect();
}

void CrmComm::dispatch(const MsgHeader& header)
{
    switch (header.msg_id)
    {
        case Prediction::kMsgId:
        {
            Prediction msg = read_body<Prediction>(header);
            if (on_prediction)
            {
                on_prediction(msg);
            }
            break;
        }
        default:
            skip_body(header, body_size(header) + sizeof(MsgHeader));
            break;
    }
}
