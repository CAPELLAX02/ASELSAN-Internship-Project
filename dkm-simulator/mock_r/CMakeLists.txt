cmake_minimum_required(VERSION 3.11)
project(mock_r C CXX)

# Set C standard
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Default to Debug for single-config generators (Makefiles/Ninja).
if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Debug CACHE STRING "Build type" FORCE)
endif()

# Logger config (compile-time)
option(ENABLE_LOGGING "Compile logging code" ON)
option(LOG_DEFAULT_CONSOLE "Enable console sink by default" ON)
option(LOG_DEFAULT_FILE "Enable file sink by default" ON)
set(LOG_FILE_PATH "app.log" CACHE STRING "Default log file path")

# Include directories
include_directories(${CMAKE_SOURCE_DIR}/inc)
include_directories(${CMAKE_SOURCE_DIR}/src)

# Link directories
link_directories(${CMAKE_SOURCE_DIR}/lib)

# Add executable
add_executable(${PROJECT_NAME}
    main.cpp
    src/comm/tcp_socket.cpp
    src/comm/message_channel.cpp
    src/comm/crm_comm.cpp
    src/comm/rsp_comm.cpp
    src/comm/rsm_comm.cpp
    src/core/message_recorder.cpp
    src/core/processing.cpp
)

if(WIN32)
    target_link_libraries(${PROJECT_NAME} PRIVATE ws2_32)
endif()

target_compile_definitions(${PROJECT_NAME} PRIVATE
    $<$<BOOL:${ENABLE_LOGGING}>:ENABLE_LOGGING=1>
    $<$<NOT:$<BOOL:${ENABLE_LOGGING}>>:ENABLE_LOGGING=0>
    $<$<BOOL:${LOG_DEFAULT_CONSOLE}>:LOG_DEFAULT_CONSOLE=1>
    $<$<NOT:$<BOOL:${LOG_DEFAULT_CONSOLE}>>:LOG_DEFAULT_CONSOLE=0>
    $<$<BOOL:${LOG_DEFAULT_FILE}>:LOG_DEFAULT_FILE=1>
    $<$<NOT:$<BOOL:${LOG_DEFAULT_FILE}>>:LOG_DEFAULT_FILE=0>
    LOG_FILE_PATH_DEFAULT="${LOG_FILE_PATH}"
)
