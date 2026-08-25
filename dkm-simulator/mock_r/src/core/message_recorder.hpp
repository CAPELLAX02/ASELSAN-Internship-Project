#ifndef SRC_CORE_MESSAGE_RECORDER_HPP_
#define SRC_CORE_MESSAGE_RECORDER_HPP_

#include <cstddef>
#include <fstream>
#include <mutex>
#include <string>

namespace core
{

// Appends the raw wire bytes of every message this process sends and
// receives to a pair of binary files, mirroring the real target's
// input/output capture so a mock_r session can be replayed/inspected with
// the same tooling used for real DKM runs.
//
// One instance is shared across all of a process's MessageChannels
// (RSP/RSM/CRM), so the two files hold a single interleaved timeline per
// direction -- input.bin is everything received from any peer, output.bin
// is everything sent to any peer -- matching how the real module's capture
// works. Thread-safe: each channel's receive thread and any processing
// thread that calls send() may record concurrently.
class MessageRecorder
{
public:
    // Opens (truncating) input_path and output_path for binary writing.
    // Throws std::runtime_error if either file can't be opened.
    MessageRecorder(const std::string& input_path, const std::string& output_path);

    MessageRecorder(const MessageRecorder&)            = delete;
    MessageRecorder& operator=(const MessageRecorder&) = delete;

    // Records the raw bytes of a message received from a peer, exactly as
    // they arrived off the wire.
    void record_input(const void* data, std::size_t len);

    // Records the raw bytes of a message sent to a peer, exactly as they
    // went out on the wire.
    void record_output(const void* data, std::size_t len);

private:
    static void write(std::ofstream& file, std::mutex& mutex, const void* data, std::size_t len);

    std::ofstream input_file_;
    std::ofstream output_file_;
    std::mutex input_mutex_;
    std::mutex output_mutex_;
};

}  // namespace core

#endif /* SRC_CORE_MESSAGE_RECORDER_HPP_ */
