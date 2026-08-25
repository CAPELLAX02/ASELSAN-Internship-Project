cmake_minimum_required(VERSION 3.11)
project(bin_gen CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Only the interface structs are needed -- pull them straight from mock_r's
# public headers so this project can never drift from the real wire format.
include_directories(${CMAKE_SOURCE_DIR}/../mock_r/inc)

add_executable(${PROJECT_NAME} main.cpp)
