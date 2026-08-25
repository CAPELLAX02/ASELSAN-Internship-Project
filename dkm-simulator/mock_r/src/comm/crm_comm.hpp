#ifndef SRC_COMM_CRM_COMM_HPP_
#define SRC_COMM_CRM_COMM_HPP_

#include <functional>

#include "comm/message_channel.hpp"
#include "core/message_recorder.hpp"
#include "interface/crm.h"

// TCP link to the CRM peer. CRM currently exposes a single message type
// (Prediction); on_prediction fires on the receive thread whenever one
// arrives.
class CrmComm : public comm::MessageChannel
{
public:
    explicit CrmComm(core::MessageRecorder* recorder = nullptr);
    ~CrmComm() override;

    std::function<void(const Prediction&)> on_prediction;

protected:
    void dispatch(const MsgHeader& header) override;
};

#endif /* SRC_COMM_CRM_COMM_HPP_ */
