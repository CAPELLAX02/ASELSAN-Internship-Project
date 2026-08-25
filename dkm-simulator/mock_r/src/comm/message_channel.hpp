#ifndef SRC_COMM_MESSAGE_CHANNEL_HPP_
#define SRC_COMM_MESSAGE_CHANNEL_HPP_

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

#include "comm/tcp_socket.hpp"
#include "core/message_recorder.hpp"
#include "interface/common.h"

namespace comm
{

// Base class for a single TCP link to one peer module (RSP, RSM, or CRM),
// standing in for what is a PCIe link on the real target. Owns the
// connection, addresses and timestamps outgoing messages, and runs a
// background thread that frames incoming bytes by MsgHeader and hands each
// message to a derived class's dispatch() for type-specific decoding.
//
// Every message struct in inc/interface is currently byte-identical to its
// wire form (no separate domain type yet), so send()/read_body() move raw
// struct bytes directly -- no field-by-field (de)serialization.
//
// If constructed with a non-null recorder, every message actually sent or
// received on this channel is appended to the recorder's input/output
// binaries, byte-exact -- see core::MessageRecorder. The same recorder
// instance is normally shared across all of a process's channels so the
// capture reflects one interleaved timeline per direction.
//
// Derived classes MUST call disconnect() in their own destructor. The
// receive thread calls the virtual dispatch(); once ~MessageChannel starts
// running the derived part of the object is already gone, so relying on
// the base destructor alone to stop the thread first is not safe.
class MessageChannel
{
public:
    MessageChannel(ModuleId local_id, ModuleId remote_id, core::MessageRecorder* recorder = nullptr);
    virtual ~MessageChannel();

    MessageChannel(const MessageChannel&)            = delete;
    MessageChannel& operator=(const MessageChannel&) = delete;

    // Connects to the peer and starts the receive thread. Returns false on
    // connect failure.
    bool connect(const std::string& host, std::uint16_t port);

    // Stops the receive thread and closes the connection. Safe to call when
    // already disconnected, and safe to call from within a dispatch()
    // callback.
    void disconnect();

    bool is_connected() const { return socket_.is_open(); }

    // Sends a raw interface message. header.sender_id, receiver_id, msg_id,
    // msg_length, and timestamp are filled in here -- callers only need to
    // set the payload fields (and may leave header default-constructed).
    template <typename T>
    bool send(T msg)
    {
        msg.header.sender_id   = static_cast<std::size_t>(local_id_);
        msg.header.receiver_id = static_cast<std::size_t>(remote_id_);
        msg.header.msg_id      = T::kMsgId;
        msg.header.msg_length  = sizeof(T);
        msg.header.timestamp   = current_timestamp();

        std::lock_guard<std::mutex> lock(send_mutex_);
        if (!socket_.send_all(&msg, sizeof(T)))
        {
            return false;
        }
        if (recorder_)
        {
            recorder_->record_output(&msg, sizeof(T));
        }
        return true;
    }

protected:
    // Reads the remainder of a T (everything after MsgHeader) off the wire
    // and returns the fully populated message. `header` must already hold
    // the bytes read for this message's header. Only valid to call from
    // within dispatch(), on the receive thread.
    template <typename T>
    T read_body(const MsgHeader& header)
    {
        T msg{};
        msg.header = header;
        if (socket_.recv_all(reinterpret_cast<std::uint8_t*>(&msg) + sizeof(MsgHeader),
                              sizeof(T) - sizeof(MsgHeader)) &&
            recorder_)
        {
            recorder_->record_input(&msg, sizeof(T));
        }
        return msg;
    }

    // Discards `count` unread payload bytes, e.g. to resync the stream past
    // a message whose msg_id isn't recognized. `header` is the header
    // already read for this message; if a recorder is attached, it and the
    // discarded bytes are still recorded, so the capture reflects every
    // message received -- not just ones this dispatch() understands.
    void skip_body(const MsgHeader& header, std::size_t count);

    // header.msg_length minus the header, clamped to 0 -- use this (not
    // header.msg_length directly) when sizing a skip_body() call, since
    // msg_length comes off the wire and a malformed/truncated value must
    // not underflow into a huge skip.
    static std::size_t body_size(const MsgHeader& header)
    {
        return header.msg_length > sizeof(MsgHeader) ? header.msg_length - sizeof(MsgHeader) : 0;
    }

    // Called on the receive thread for every message header read off the
    // wire. Implementations switch on header.msg_id, pull the matching body
    // with read_body<T>(), and invoke their typed callback; the default
    // case should call skip_body(header.msg_length - sizeof(MsgHeader)).
    virtual void dispatch(const MsgHeader& header) = 0;

private:
    static std::size_t current_timestamp();
    void receive_loop();

    ModuleId local_id_;
    ModuleId remote_id_;
    core::MessageRecorder* recorder_;
    TcpSocket socket_;
    std::mutex send_mutex_;
    std::thread receive_thread_;
    std::atomic<bool> running_{false};
};

}  // namespace comm

#endif /* SRC_COMM_MESSAGE_CHANNEL_HPP_ */
