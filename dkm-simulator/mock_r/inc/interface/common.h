#ifndef INC_INTERFACE_COMMON_H_
#define INC_INTERFACE_COMMON_H_

#include <cstddef>

// Wire IDs used in MsgHeader::sender_id / receiver_id. Mirrors the module
// IDs the real DKM and its PCIe peers use.
enum class ModuleId : std::size_t
{
    RDP = 1,
    RSP = 2,
    RSM = 3,
    CRM = 4,
};

struct MsgHeader
{
    std::size_t sender_id;
    std::size_t receiver_id;
    std::size_t msg_id;
    std::size_t timestamp;
    std::size_t msg_length;
};

#endif /* INC_INTERFACE_COMMON_H_ */