#include "core/message_recorder.hpp"

#include <stdexcept>

namespace core
{

MessageRecorder::MessageRecorder(const std::string& input_path, const std::string& output_path)
    : input_file_(input_path, std::ios::binary | std::ios::trunc),
      output_file_(output_path, std::ios::binary | std::ios::trunc)
{
    if (!input_file_.is_open())
    {
        throw std::runtime_error("MessageRecorder: failed to open input file: " + input_path);
    }
    if (!output_file_.is_open())
    {
        throw std::runtime_error("MessageRecorder: failed to open output file: " + output_path);
    }
}

void MessageRecorder::record_input(const void* data, std::size_t len)
{
    write(input_file_, input_mutex_, data, len);
}

void MessageRecorder::record_output(const void* data, std::size_t len)
{
    write(output_file_, output_mutex_, data, len);
}

void MessageRecorder::write(std::ofstream& file, std::mutex& mutex, const void* data, std::size_t len)
{
    std::lock_guard<std::mutex> lock(mutex);
    file.write(reinterpret_cast<const char*>(data), static_cast<std::streamsize>(len));
    file.flush();
}

}  // namespace core
