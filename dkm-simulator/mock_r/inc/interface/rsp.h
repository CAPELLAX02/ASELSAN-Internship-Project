#ifndef INC_INTERFACE_RSP_H_
#define INC_INTERFACE_RSP_H_

#include <array>

#include "interface/common.h"
#include "interface/def.h"

struct Detection
{
    double distance;
    double heading;
};

struct DetectionReport
{
    static constexpr std::uint16_t kMsgId = 1;

    MsgHeader header{};
    std::size_t beam_id             = 0;
    std::size_t detection_timestamp = 0;
    std::size_t detection_count     = 0;
    std::array<Detection, MAXIMUM_DETECTIONS_PER_BEAM> detections{};
};

struct JammerReport
{
    static constexpr std::uint16_t kMsgId = 2;

    MsgHeader header{};
    std::size_t beam_id          = 0;
    std::size_t jammer_timestamp = 0;
};

#endif /* INC_INTERFACE_RSP_H_ */