#ifndef SRC_CORE_MESSAGE_QUEUE_HPP_
#define SRC_CORE_MESSAGE_QUEUE_HPP_

#include <condition_variable>
#include <deque>
#include <mutex>
#include <optional>
#include <utility>

namespace core
{

// Thread-safe hand-off queue from a comm channel's receive thread to a
// dedicated processing thread, so the receive thread never blocks on
// processing (it just pushes and goes back to reading the socket).
template <typename T>
class MessageQueue
{
public:
    void push(T value)
    {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            queue_.push_back(std::move(value));
        }
        cv_.notify_one();
    }

    // Blocks until a message is available (returns it) or stop() has been
    // called and the queue has drained (returns nullopt).
    std::optional<T> pop()
    {
        std::unique_lock<std::mutex> lock(mutex_);
        cv_.wait(lock, [this] { return !queue_.empty() || stopped_; });
        if (queue_.empty())
        {
            return std::nullopt;
        }
        T value = std::move(queue_.front());
        queue_.pop_front();
        return value;
    }

    void stop()
    {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopped_ = true;
        }
        cv_.notify_all();
    }

private:
    std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<T> queue_;
    bool stopped_ = false;
};

}  // namespace core

#endif /* SRC_CORE_MESSAGE_QUEUE_HPP_ */
