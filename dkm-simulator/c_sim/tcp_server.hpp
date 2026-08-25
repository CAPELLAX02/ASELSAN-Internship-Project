#ifndef SIM_TCP_SERVER_HPP_
#define SIM_TCP_SERVER_HPP_

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

// Blocking Winsock TCP server-side socket pair, standing in for the peer
// side of mock_r's MessageChannel::connect() -- mock_r only ever connects
// out as a client (see mock_r/src/comm/tcp_socket.cpp), so something has to
// listen. Mirrors that class's style: opaque handle, blocking
// send_all()/recv_all() only, framing left to the caller.

// A single accepted client connection.
class TcpConnection
{
public:
    ~TcpConnection();

    TcpConnection(const TcpConnection&)            = delete;
    TcpConnection& operator=(const TcpConnection&) = delete;

    bool is_open() const { return handle_ != kInvalidHandle; }
    void close();

    // Sends exactly len bytes. Returns false on error/disconnect (the
    // connection is closed in that case).
    bool send_all(const void* data, std::size_t len);

    // Reads exactly len bytes. Returns false on error, disconnect, or a
    // clean peer shutdown before len bytes arrived (the connection is
    // closed in that case).
    bool recv_all(void* data, std::size_t len);

private:
    friend class TcpServer;
    using Handle                          = std::uintptr_t;
    static constexpr Handle kInvalidHandle = static_cast<Handle>(~static_cast<Handle>(0));

    explicit TcpConnection(Handle handle) : handle_(handle) {}

    Handle handle_ = kInvalidHandle;
};

// Listens on a port and accepts client connections one at a time.
class TcpServer
{
public:
    TcpServer();
    ~TcpServer();

    TcpServer(const TcpServer&)            = delete;
    TcpServer& operator=(const TcpServer&) = delete;

    // Binds and listens on host:port. Returns false on failure.
    bool listen(const std::string& host, std::uint16_t port);

    // Blocks until a client connects. Returns nullptr on error.
    std::unique_ptr<TcpConnection> accept();

    void close();

private:
    using Handle                          = std::uintptr_t;
    static constexpr Handle kInvalidHandle = static_cast<Handle>(~static_cast<Handle>(0));

    Handle handle_ = kInvalidHandle;
};

#endif /* SIM_TCP_SERVER_HPP_ */
