#include "core/processing.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace core
{

namespace
{

// Average distance/heading across the first `count` entries of detections.
Detection average_detection(const std::array<Detection, MAXIMUM_DETECTIONS_PER_BEAM>& detections,
                             std::size_t count)
{
    double sum_distance = 0.0;
    double sum_heading  = 0.0;
    for (std::size_t i = 0; i < count; ++i)
    {
        sum_distance += detections[i].distance;
        sum_heading += detections[i].heading;
    }
    return Detection{sum_distance / static_cast<double>(count),
                      sum_heading / static_cast<double>(count)};
}

// The entry among the first `count` detections closest to `average`
// (squared Euclidean distance in raw distance/heading units).
Detection closest_to(const std::array<Detection, MAXIMUM_DETECTIONS_PER_BEAM>& detections,
                      std::size_t count, const Detection& average)
{
    std::size_t best_index  = 0;
    double best_dist_sq     = std::numeric_limits<double>::max();
    for (std::size_t i = 0; i < count; ++i)
    {
        const double d_distance = detections[i].distance - average.distance;
        const double d_heading  = detections[i].heading - average.heading;
        const double dist_sq    = d_distance * d_distance + d_heading * d_heading;
        if (dist_sq < best_dist_sq)
        {
            best_dist_sq = dist_sq;
            best_index   = i;
        }
    }
    return detections[best_index];
}

bool inside_gate_area(const GateAreaMsg& gate, double distance, double heading)
{
    return distance >= gate.start_distance && distance <= gate.end_distance &&
           heading >= gate.start_heading && heading <= gate.end_heading;
}

bool inside_reporting_area(const ReportingAreaMsg& area, double x, double y)
{
    return x >= area.start_x && x <= area.end_x && y >= area.start_y && y <= area.end_y;
}

}  // namespace

std::optional<MeasurementReport> process_detection_report(const DetectionReport& msg,
                                                            const BeamReportBuffer& beams,
                                                            const GateAreaBuffer& gates,
                                                            const ReportingAreaBuffer& reporting_areas)
{
    const std::size_t count = std::min(msg.detection_count, MAXIMUM_DETECTIONS_PER_BEAM);
    if (count == 0)
    {
        return std::nullopt;
    }

    const auto beam = beams.get(msg.beam_id);
    if (!beam.has_value())
    {
        return std::nullopt;
    }

    const Detection average = average_detection(msg.detections, count);
    // beam_type == 1 selects the closest input to the average; anything
    // else (including the documented 0 case) averages all inputs.
    const Detection output = (beam->beam_type == 1) ? closest_to(msg.detections, count, average)
                                                      : average;

    // Must fall outside every currently-defined gate area.
    for (const GateAreaMsg& gate : gates.snapshot())
    {
        if (inside_gate_area(gate, output.distance, output.heading))
        {
            return std::nullopt;
        }
    }

    // With no reporting areas defined, every location is valid. Otherwise
    // must fall inside at least one. Reporting areas are in x/y, so the
    // polar (distance, heading) output is converted assuming heading is in
    // radians.
    const std::vector<ReportingAreaMsg> reporting_area_list = reporting_areas.snapshot();
    if (!reporting_area_list.empty())
    {
        const double x = output.distance * std::cos(output.heading);
        const double y = output.distance * std::sin(output.heading);

        const bool inside_any =
            std::any_of(reporting_area_list.begin(), reporting_area_list.end(),
                        [&](const ReportingAreaMsg& area) { return inside_reporting_area(area, x, y); });
        if (!inside_any)
        {
            return std::nullopt;
        }
    }

    MeasurementReport result{};
    result.measurement_timestamp = msg.detection_timestamp;
    result.distance               = output.distance;
    result.heading                = output.heading;
    return result;
}

void process_jammer_report(const JammerReport& msg, JammerBuffer& jammer_buffer)
{
    jammer_buffer.push(msg);
}

void process_prediction(const Prediction& msg, PredictionBuffer& prediction_buffer)
{
    prediction_buffer.push(msg);
}

}  // namespace core
