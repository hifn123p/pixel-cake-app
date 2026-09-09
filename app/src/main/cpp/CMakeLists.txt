cmake_minimum_required(VERSION 3.22.1)

# P1b raw bridge：当前仅占位 JNI，用于先验证 NDK/CMake/CI 管线；
# 下一提交（P1b-1/2）将在此 add_subdirectory(libraw) 并链接 LibRaw 实现 ARW 全量解码。
project(rawbridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(rawbridge SHARED raw_bridge.cpp)

find_library(log-lib log)
target_link_libraries(rawbridge ${log-lib})
