# 轻微 MCP JNI 原生库构建配置
# 生成 libqingwei_mcp_jni.so，供 DeviceNode.kt 通过 System.loadLibrary("qingwei_mcp_jni") 加载

cmake_minimum_required(VERSION 3.22.1)

project("qingwei_mcp_jni")

# Pinned, hash-checked source ships with the repo; native configuration needs no download.
include(FetchContent)
set(CAPSTONE_ARCHITECTURE_DEFAULT OFF CACHE BOOL "" FORCE)
set(CAPSTONE_ARM64_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_CSTOOL OFF CACHE BOOL "" FORCE)
set(CAPSTONE_INSTALL OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_Declare(capstone
    URL "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/capstone-5.0.6.tar.gz"
    URL_HASH SHA256=ef6950c8276293a407fe374e99c95aa95faf25d022cc65974a52a01b79f5b4b3)
FetchContent_MakeAvailable(capstone)

# 构建共享库
add_library(${PROJECT_NAME} SHARED
    kernelai_jni.cpp
    disassembler_jni.cpp
)

# 链接 Android 日志库 (liblog)
# 用于 __android_log_print 输出
find_library(log-lib log)

target_link_libraries(${PROJECT_NAME}
    ${log-lib}
    capstone
)

# 编译选项
target_compile_options(${PROJECT_NAME} PRIVATE
    -Wall
    -Wextra
    -fvisibility=hidden
)
