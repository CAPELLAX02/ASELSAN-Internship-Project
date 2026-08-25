#include "comm/tcp_socket.hpp"

#include <algorithm>
#include <mutex>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <sys/socket.h>
#include <sys/types.h>
#include <netdb.h>
#include <unistd.h>
#endif

namespace comm
{
namespace
{

#ifdef _WIN32

void ensure_socket_initialized()
{
    static std::once_flag once;
    std::call_once(once, [] {
        WSADATA wsa_data{};
        WSAStartup(MAKEWORD(2, 2), &wsa_data);
    });
}

using NativeSocket = SOCKET;
constexpr NativeSocket kInvalidSocket = INVALID_SOCKET;

void close_socket(NativeSocket socket)
{
    closesocket(socket);
}

#else

void ensure_socket_initialized()
{
    // POSIX sockets require no global initialization.
}

using NativeSocket = int;
constexpr NativeSocket kInvalidSocket = -1;

void close_socket(NativeSocket socket)
{
    ::close(socket);
}

#endif

} // namespace

TcpSocket::TcpSocket()
{
    ensure_socket_initialized();
}

TcpSocket::~TcpSocket()
{
    close();
}

bool TcpSocket::connect(const std::string& host, std::uint16_t port)
{
    close();

    addrinfo hints{};
    hints.ai_family   = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    addrinfo* result = nullptr;
    const std::string port_str = std::to_string(port);

    if (getaddrinfo(host.c_str(), port_str.c_str(), &hints, &result) != 0)
    {
        return false;
    }

    NativeSocket sock = kInvalidSocket;

    for (addrinfo* p = result; p != nullptr; p = p->ai_next)
    {
        sock = socket(
            p->ai_family,
            p->ai_socktype,
            p->ai_protocol);

        if (sock == kInvalidSocket)
        {
            continue;
        }

#ifdef _WIN32
        const int address_len = static_cast<int>(p->ai_addrlen);
#else
        const socklen_t address_len = static_cast<socklen_t>(p->ai_addrlen);
#endif

        if (::connect(sock, p->ai_addr, address_len) == 0)
        {
            break;
        }

        close_socket(sock);
        sock = kInvalidSocket;
    }

    freeaddrinfo(result);

    if (sock == kInvalidSocket)
    {
        return false;
    }

    handle_ = static_cast<Handle>(sock);
    return true;
}

void TcpSocket::close()
{
    if (handle_ != kInvalidHandle)
    {
        close_socket(static_cast<NativeSocket>(handle_));
        handle_ = kInvalidHandle;
    }
}

bool TcpSocket::send_all(const void* data, std::size_t len)
{
    if (!is_open())
    {
        return false;
    }

    const auto* bytes = static_cast<const char*>(data);
    std::size_t total_sent = 0;

    while (total_sent < len)
    {
        const int chunk = static_cast<int>(
            std::min<std::size_t>(len - total_sent, 1u << 20));

#ifdef _WIN32
        const int sent = ::send(
            static_cast<SOCKET>(handle_),
            bytes + total_sent,
            chunk,
            0);

        if (sent == SOCKET_ERROR)
#else
        const ssize_t sent = ::send(
            static_cast<int>(handle_),
            bytes + total_sent,
            chunk,
            0);

        if (sent < 0)
#endif
        {
            close();
            return false;
        }

        total_sent += static_cast<std::size_t>(sent);
    }

    return true;
}

bool TcpSocket::recv_all(void* data, std::size_t len)
{
    if (!is_open())
    {
        return false;
    }

    auto* bytes = static_cast<char*>(data);
    std::size_t total_read = 0;

    while (total_read < len)
    {
        const int chunk = static_cast<int>(
            std::min<std::size_t>(len - total_read, 1u << 20));

#ifdef _WIN32
        const int received = ::recv(
            static_cast<SOCKET>(handle_),
            bytes + total_read,
            chunk,
            0);
#else
        const ssize_t received = ::recv(
            static_cast<int>(handle_),
            bytes + total_read,
            chunk,
            0);
#endif

        if (received <= 0)
        {
            close();
            return false;
        }

        total_read += static_cast<std::size_t>(received);
    }

    return true;
}

} // namespace comm



// #include "comm/tcp_socket.hpp"

// #include <algorithm>
// #include <mutex>
// #include <ws2tcpip.h>
// // clang-format off
// #include <winsock2.h>
// // clang-format on

// namespace comm
// {

// namespace
// {

// void ensure_winsock_initialized()
// {
//     static std::once_flag once;
//     std::call_once(once,
//                    []
//                    {
//                        WSADATA wsa_data{};
//                        WSAStartup(MAKEWORD(2, 2), &wsa_data);
//                        // WSACleanup() is intentionally never called: sockets in this mock
//                        // live for the process lifetime, and there is no safe point to tear
//                        // Winsock down before every TcpSocket has been destroyed.
//                    });
// }

// } // namespace

// TcpSocket::TcpSocket()
// {
//     ensure_winsock_initialized();
// }

// TcpSocket::~TcpSocket()
// {
//     close();
// }

// bool TcpSocket::connect(const std::string& host, std::uint16_t port)
// {
//     close();

//     addrinfo hints{};
//     hints.ai_family   = AF_INET;
//     hints.ai_socktype = SOCK_STREAM;
//     hints.ai_protocol = IPPROTO_TCP;

//     addrinfo* result           = nullptr;
//     const std::string port_str = std::to_string(port);
//     if (getaddrinfo(host.c_str(), port_str.c_str(), &hints, &result) != 0)
//     {
//         return false;
//     }

//     SOCKET sock = INVALID_SOCKET;
//     for (addrinfo* p = result; p != nullptr; p = p->ai_next)
//     {
//         sock = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
//         if (sock == INVALID_SOCKET)
//         {
//             continue;
//         }
//         if (::connect(sock, p->ai_addr, static_cast<int>(p->ai_addrlen)) == 0)
//         {
//             break;
//         }
//         closesocket(sock);
//         sock = INVALID_SOCKET;
//     }
//     freeaddrinfo(result);

//     if (sock == INVALID_SOCKET)
//     {
//         return false;
//     }

//     handle_ = static_cast<Handle>(sock);
//     return true;
// }

// void TcpSocket::close()
// {
//     if (handle_ != kInvalidHandle)
//     {
//         closesocket(static_cast<SOCKET>(handle_));
//         handle_ = kInvalidHandle;
//     }
// }

// bool TcpSocket::send_all(const void* data, std::size_t len)
// {
//     if (!is_open())
//     {
//         return false;
//     }

//     const auto* bytes      = static_cast<const char*>(data);
//     std::size_t total_sent = 0;
//     while (total_sent < len)
//     {
//         const int chunk = static_cast<int>(std::min<std::size_t>(len - total_sent, 1u << 20));
//         const int sent  = ::send(static_cast<SOCKET>(handle_), bytes + total_sent, chunk, 0);
//         if (sent == SOCKET_ERROR)
//         {
//             close();
//             return false;
//         }
//         total_sent += static_cast<std::size_t>(sent);
//     }
//     return true;
// }

// bool TcpSocket::recv_all(void* data, std::size_t len)
// {
//     if (!is_open())
//     {
//         return false;
//     }

//     auto* bytes            = static_cast<char*>(data);
//     std::size_t total_read = 0;
//     while (total_read < len)
//     {
//         const int chunk    = static_cast<int>(std::min<std::size_t>(len - total_read, 1u << 20));
//         const int received = ::recv(static_cast<SOCKET>(handle_), bytes + total_read, chunk, 0);
//         if (received <= 0)
//         {
//             close();
//             return false;
//         }
//         total_read += static_cast<std::size_t>(received);
//     }
//     return true;
// }

// } // namespace comm
