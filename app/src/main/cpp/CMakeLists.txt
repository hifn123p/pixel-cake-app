cmake_minimum_required(VERSION 3.22.1)

# P1b raw bridge：在本 CMake 工程中集成 LibRaw 静态库，实现 ARW 全量解码。
project(rawbridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---- P1b-1：LibRaw 全量 RAW 解码（静态链接，关掉无关依赖） ----
# LibRaw-cmake 默认把 LIBRAW_PATH 指向自身目录；用 FORCE 覆盖为 LibRaw 源码目录。
set(LIBRAW_PATH "${CMAKE_CURRENT_SOURCE_DIR}/third_party/LibRaw" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENMP            OFF CACHE BOOL "" FORCE)
set(ENABLE_LCMS              OFF CACHE BOOL "" FORCE)
set(ENABLE_JASPER            OFF CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES          OFF CACHE BOOL "" FORCE)
set(ENABLE_RAWSPEED          OFF CACHE BOOL "" FORCE)
set(LIBRAW_INSTALL           OFF CACHE BOOL "" FORCE)
# 关闭 CMake 对 zlib/jpeg/lcms2 的自动 find_package，避免 NDK 下找不到或链接目标缺失导致配置失败
set(CMAKE_DISABLE_FIND_PACKAGE_ZLIB  TRUE CACHE BOOL "" FORCE)
set(CMAKE_DISABLE_FIND_PACKAGE_JPEG  TRUE CACHE BOOL "" FORCE)
set(CMAKE_DISABLE_FIND_PACKAGE_LCMS2 TRUE CACHE BOOL "" FORCE)
add_subdirectory(third_party/LibRaw-cmake)

find_library(log-lib log)

add_library(rawbridge SHARED raw_bridge.cpp)

# 链接 LibRaw 静态库（raw 目标由 LibRaw-cmake 提供）与 Android log
target_link_libraries(rawbridge raw ${log-lib})
