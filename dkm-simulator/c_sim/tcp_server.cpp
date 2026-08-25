#include "tcp_server.hpp"

#include <algorithm>
#include <mutex>

#ifdef _WIN32
    #include <ws2tcpip.h>
    // clang-format off
    #include <winsock2.h>
    // clang-format on
#else
    #include <arpa/inet.h>
    #include <netdb.h>
    #include <netinet/in.h>
    #include <sys/socket.h>
    #include <unistd.h>

    using SOCKET = int;
    constexpr int INVALID_SOCKET = -1;
    constexpr int SOCKET_ERROR = -1;
    // :: kullanarak global POSIX close fonksiyonunu işaret ediyoruz, 
    // sınıfın kendi close() fonksiyonuyla çakışmasını engelliyoruz.
    #define closesocket ::close
#endif

namespace
{

void ensure_winsock_initialized()
{
#ifdef _WIN32
    static std::once_flag once;
    std::call_once(once,
                   []
                   {
                       WSADATA wsa_data{};
                       WSAStartup(MAKEWORD(2, 2), &wsa_data);
                       // WSACleanup() is intentionally never called, same reasoning as
                       // mock_r's tcp_socket.cpp: sockets here live for the process
                       // lifetime.
                   });
#endif
}

}  // namespace

TcpConnection::~TcpConnection()
{
    close();
}

void TcpConnection::close()
{
    if (handle_ != kInvalidHandle)
    {
        closesocket(static_cast<SOCKET>(handle_));
        handle_ = kInvalidHandle;
    }
}

bool TcpConnection::send_all(const void* data, std::size_t len)
{
    if (!is_open())
    {
        return false;
    }

    const auto* bytes      = static_cast<const char*>(data);
    std::size_t total_sent = 0;
    while (total_sent < len)
    {
        const int chunk = static_cast<int>(std::min<std::size_t>(len - total_sent, 1u << 20));
        const int sent  = ::send(static_cast<SOCKET>(handle_), bytes + total_sent, chunk, 0);
        if (sent == SOCKET_ERROR)
        {
            close();
            return false;
        }
        total_sent += static_cast<std::size_t>(sent);
    }
    return true;
}

bool TcpConnection::recv_all(void* data, std::size_t len)
{
    if (!is_open())
    {
        return false;
    }

    auto* bytes            = static_cast<char*>(data);
    std::size_t total_read = 0;
    while (total_read < len)
    {
        const int chunk    = static_cast<int>(std::min<std::size_t>(len - total_read, 1u << 20));
        const int received = ::recv(static_cast<SOCKET>(handle_), bytes + total_read, chunk, 0);
        if (received <= 0)
        {
            close();
            return false;
        }
        total_read += static_cast<std::size_t>(received);
    }
    return true;
}

TcpServer::TcpServer()
{
    ensure_winsock_initialized();
}

TcpServer::~TcpServer()
{
    close();
}

bool TcpServer::listen(const std::string& host, std::uint16_t port)
{
    close();

    addrinfo hints{};
    hints.ai_family   = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    hints.ai_flags    = AI_PASSIVE;

    addrinfo* result           = nullptr;
    const std::string port_str = std::to_string(port);
    const char* node           = host.empty() ? nullptr : host.c_str();
    if (getaddrinfo(node, port_str.c_str(), &hints, &result) != 0)
    {
        return false;
    }

    SOCKET sock = INVALID_SOCKET;
    for (addrinfo* p = result; p != nullptr; p = p->ai_next)
    {
        sock = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (sock == INVALID_SOCKET)
        {
            continue;
        }

        int reuse = 1;
        setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, (const char*)&reuse, sizeof(reuse));

        if (::bind(sock, p->ai_addr, static_cast<int>(p->ai_addrlen)) == 0 && ::listen(sock, SOMAXCONN) == 0)
        {
            break;
        }
        closesocket(sock);
        sock = INVALID_SOCKET;
    }
    freeaddrinfo(result);

    if (sock == INVALID_SOCKET)
    {
        return false;
    }

    handle_ = static_cast<Handle>(sock);
    return true;
}

std::unique_ptr<TcpConnection> TcpServer::accept()
{
    if (handle_ == kInvalidHandle)
    {
        return nullptr;
    }

    const SOCKET client = ::accept(static_cast<SOCKET>(handle_), nullptr, nullptr);
    if (client == INVALID_SOCKET)
    {
        return nullptr;
    }

    return std::unique_ptr<TcpConnection>(new TcpConnection(static_cast<TcpConnection::Handle>(client)));
}

void TcpServer::close()
{
    if (handle_ != kInvalidHandle)
    {
        closesocket(static_cast<SOCKET>(handle_));
        handle_ = kInvalidHandle;
    }
}