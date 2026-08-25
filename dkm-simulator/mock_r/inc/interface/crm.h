#ifndef INC_INTERFACE_CRM_H_
#define INC_INTERFACE_CRM_H_

#include "interface/common.h"
#include "interface/def.h"

struct Prediction
{
    static constexpr std::size_t kMsgId = 1;

    MsgHeader header{};
    std::size_t track_id = 0;  // Identifies the tracked object -- repeated Predictions with the
                                // same track_id are the same object over time (see mock_r's
                                // PredictionBuffer, and the simulator's track/"connected points"
                                // visualization).

    double distance;
    double heading;
    double pos_x;
    double pos_y;
    double vel_x;
    double vel_y;
};

#endif /* INC_INTERFACE_CRM_H_ */