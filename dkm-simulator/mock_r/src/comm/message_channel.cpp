#include "comm/message_channel.hpp"

#include <chrono>
#include <vector>

namespace comm
{

MessageChannel::MessageChannel(ModuleId local_id, ModuleId remote_id, core::MessageRecorder* recorder)
    : local_id_(local_id), remote_id_(remote_id), recorder_(recorder)
{
}

MessageChannel::~MessageChannel()
{
    disconnect();
}

bool MessageChannel::connect(const std::string& host, std::uint16_t port)
{
    disconnect();

    if (!socket_.connect(host, port))
    {
        return false;
    }

    running_        = true;
    receive_thread_ = std::thread(&MessageChannel::receive_loop, this);
    return true;
}

void MessageChannel::disconnect()
{
    running_ = false;
    socket_.close();
    if (receive_thread_.joinable())
    {
        if (receive_thread_.get_id() == std::this_thread::get_id())
        {
            // disconnect() called from within a dispatch() callback on the
            // receive thread itself: detach instead of joining, since a
            // thread cannot join itself.
            receive_thread_.detach();
        }
        else
        {
            receive_thread_.join();
        }
    }
}

void MessageChannel::skip_body(const MsgHeader& header, std::size_t count)
{
    std::vector<std::uint8_t> discard(count);
    if (!socket_.recv_all(discard.data(), discard.size()))
    {
        return;
    }
    if (recorder_)
    {
        recorder_->record_input(&header, sizeof(header));
        recorder_->record_input(discard.data(), discard.size());
    }
}

std::size_t MessageChannel::current_timestamp()
{
    using namespace std::chrono;
    return static_cast<std::size_t>(
        duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count());
}

void MessageChannel::receive_loop()
{
    while (running_)
    {
        MsgHeader header{};
        if (!socket_.recv_all(&header, sizeof(header)))
        {
            break;
        }
        dispatch(header);
    }
    running_ = false;
}

}  // namespace comm
