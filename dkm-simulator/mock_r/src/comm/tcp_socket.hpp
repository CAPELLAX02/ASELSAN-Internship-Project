#ifndef SRC_COMM_TCP_SOCKET_HPP_
#define SRC_COMM_TCP_SOCKET_HPP_

#include <cstddef>
#include <cstdint>
#include <string>

namespace comm
{

// Thin RAII wrapper around a Winsock TCP client socket. Blocking send/recv
// only; message framing is handled by the caller (see MessageChannel).
//
// Deliberately keeps <winsock2.h> out of this header (it drags in windows.h
// and its macro soup) by storing the SOCKET as an opaque handle.
class TcpSocket
{
public:
    TcpSocket();
    ~TcpSocket();

    TcpSocket(const TcpSocket&)            = delete;
    TcpSocket& operator=(const TcpSocket&) = delete;

    // Connects to host:port. Returns false on failure.
    bool connect(const std::string& host, std::uint16_t port);

    // Closes the connection if open. Safe to call when already closed, and
    // safe to call concurrently with a recv_all()/send_all() in progress on
    // another thread to unblock it.
    void close();

    bool is_open() const { return handle_ != kInvalidHandle; }

    // Sends exactly len bytes. Returns false on error/disconnect (the
    // connection is closed in that case).
    bool send_all(const void* data, std::size_t len);

    // Reads exactly len bytes. Returns false on error, disconnect, or a
    // clean peer shutdown before len bytes arrived (the connection is
    // closed in that case).
    bool recv_all(void* data, std::size_t len);

private:
    using Handle                          = std::uintptr_t;
    static constexpr Handle kInvalidHandle = static_cast<Handle>(~static_cast<Handle>(0));

    Handle handle_ = kInvalidHandle;
};

}  // namespace comm

#endif /* SRC_COMM_TCP_SOCKET_HPP_ */
