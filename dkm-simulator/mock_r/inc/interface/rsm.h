#ifndef INC_INTERFACE_RSM_H_
#define INC_INTERFACE_RSM_H_

#include "interface/common.h"
#include "interface/def.h"

struct BeamReport
{
    static constexpr std::size_t kMsgId = 1;

    MsgHeader header{};
    std::size_t beam_id        = 0;
    std::size_t beam_timestamp = 0;
    std::size_t beam_type      = 0;

    double beam_heading = 0.0;
};

struct GateAreaMsg
{
    static constexpr std::size_t kMsgId = 2;

    MsgHeader header{};
    double start_distance = 0.0;
    double end_distance   = 0.0;
    double start_heading  = 0.0;
    double end_heading    = 0.0;
};

struct ReportingAreaMsg
{
    static constexpr std::size_t kMsgId = 3;

    MsgHeader header{};
    double start_x = 0.0;
    double end_x   = 0.0;
    double start_y = 0.0;
    double end_y   = 0.0;
};

struct ReadCommand
{
    static constexpr std::size_t kMsgId = 4;

    MsgHeader header{};
};

struct MeasurementReport
{
    static constexpr std::size_t kMsgId = 5;

    MsgHeader header{};
    std::size_t measurement_timestamp = 0;

    double distance;
    double heading;
};

#endif /* INC_INTERFACE_RSM_H_ */