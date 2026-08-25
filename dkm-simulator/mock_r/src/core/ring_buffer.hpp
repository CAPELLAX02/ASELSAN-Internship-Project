#ifndef SRC_CORE_RING_BUFFER_HPP_
#define SRC_CORE_RING_BUFFER_HPP_

#include <array>
#include <cstddef>
#include <mutex>
#include <vector>

namespace core
{

// Holds up to kCapacity of the most recently pushed values of T, with no id
// to key on -- used for area definitions (GateAreaMsg/ReportingAreaMsg, more
// than one can be active at once) as well as plain retained history
// (JammerReport/Prediction, buffered ahead of processing not implemented
// yet). The oldest entry is dropped once the buffer is full.
template <typename T, std::size_t kCapacity>
class RingBuffer
{
public:
    // Called from a comm channel's receive thread as each message arrives.
    void push(const T& value)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        entries_[write_pos_] = value;
        write_pos_           = (write_pos_ + 1) % kCapacity;
        if (count_ < kCapacity)
        {
            ++count_;
        }
    }

    // A copy of every currently held entry, oldest first.
    std::vector<T> snapshot() const
    {
        std::lock_guard<std::mutex> lock(mutex_);
        std::vector<T> result;
        result.reserve(count_);
        const std::size_t oldest_pos = (write_pos_ + kCapacity - count_) % kCapacity;
        for (std::size_t i = 0; i < count_; ++i)
        {
            result.push_back(entries_[(oldest_pos + i) % kCapacity]);
        }
        return result;
    }

private:
    mutable std::mutex mutex_;
    std::array<T, kCapacity> entries_{};
    std::size_t write_pos_ = 0;
    std::size_t count_     = 0;
};

}  // namespace core

#endif /* SRC_CORE_RING_BUFFER_HPP_ */
