#ifndef INC_INTERFACE_COMMON_H_
#define INC_INTERFACE_COMMON_H_

#include <cstddef>

// Wire IDs used in MsgHeader::sender_id / receiver_id. Mirrors the module
// IDs the real DKM and its PCIe peers use.
enum class ModuleId : std::size_t
{
    RDP = 4,
    RSP = 3,
    RSM = 1,
    CRM = 2,
};

struct MsgHeader
{
    std::int8_t sender_id;
    std::int8_t receiver_id;
    std::uint16_t msg_id;
    std::uint32_t msg_length;
    std::uint64_t timestamp;
};

#endif /* INC_INTERFACE_COMMON_H_ */