#ifndef SRC_CORE_BEAM_REPORT_BUFFER_HPP_
#define SRC_CORE_BEAM_REPORT_BUFFER_HPP_

#include <array>
#include <cstddef>
#include <mutex>
#include <optional>

#include "interface/rsm.h"

namespace core
{

// Holds the most recent BeamReport seen for each of the last kCapacity
// beam_ids. beam_id starts at 1 and increases by exactly 1 per beam, so a
// beam's slot is simply beam_id % kCapacity -- no map needed. Once more
// than kCapacity beams have arrived, the oldest slot is silently
// overwritten and that beam is considered stale/expired.
class BeamReportBuffer
{
public:
    static constexpr std::size_t kCapacity = 100;

    // Called from RSM's receive thread as each BeamReport arrives.
    void put(const BeamReport& report)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        slots_[report.beam_id % kCapacity] = report;
    }

    // Called from Detection/Jammer processing threads. Returns nullopt if
    // beam_id was never received, or has since been evicted (kCapacity or
    // more newer beams have arrived since).
    std::optional<BeamReport> get(std::size_t beam_id) const
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const BeamReport& slot = slots_[beam_id % kCapacity];
        if (slot.beam_id != beam_id)
        {
            return std::nullopt;
        }
        return slot;
    }

private:
    mutable std::mutex mutex_;
    std::array<BeamReport, kCapacity> slots_{};
};

}  // namespace core

#endif /* SRC_CORE_BEAM_REPORT_BUFFER_HPP_ */
