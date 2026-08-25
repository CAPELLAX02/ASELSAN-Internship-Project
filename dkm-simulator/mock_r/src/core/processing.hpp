#ifndef SRC_CORE_PROCESSING_HPP_
#define SRC_CORE_PROCESSING_HPP_

#include <cstddef>
#include <optional>

#include "core/beam_report_buffer.hpp"
#include "core/ring_buffer.hpp"
#include "interface/crm.h"
#include "interface/rsm.h"
#include "interface/rsp.h"

namespace core
{

// Placeholder capacities -- tune once real numbers are known.
constexpr std::size_t kAreaBufferCapacity       = 16;
constexpr std::size_t kJammerBufferCapacity     = 100;
constexpr std::size_t kPredictionBufferCapacity = 100;

using GateAreaBuffer      = RingBuffer<GateAreaMsg, kAreaBufferCapacity>;
using ReportingAreaBuffer = RingBuffer<ReportingAreaMsg, kAreaBufferCapacity>;
using JammerBuffer        = RingBuffer<JammerReport, kJammerBufferCapacity>;
using PredictionBuffer    = RingBuffer<Prediction, kPredictionBufferCapacity>;

// One of these runs per arriving message on its own dedicated processing
// thread (see main.cpp).

// Reduces msg's input detections to a single output detection using the
// beam's type (0 = average all inputs, 1 = the input closest to the
// average), tags it with the report's detection_timestamp, and -- if it
// passes the current gate/reporting-area checks -- returns the resulting
// MeasurementReport. Returns nullopt when no measurement should be
// produced: beam_id has no known BeamReport, the point falls inside any
// currently-defined gate area, or (when at least one reporting area is
// defined) it falls inside none of them.
std::optional<MeasurementReport> process_detection_report(const DetectionReport& msg,
                                                            const BeamReportBuffer& beams,
                                                            const GateAreaBuffer& gates,
                                                            const ReportingAreaBuffer& reporting_areas);

// Not yet implemented -- just retains msg for future processing.
void process_jammer_report(const JammerReport& msg, JammerBuffer& jammer_buffer);

// Not yet implemented -- just retains msg for future processing.
void process_prediction(const Prediction& msg, PredictionBuffer& prediction_buffer);

}  // namespace core

#endif /* SRC_CORE_PROCESSING_HPP_ */
