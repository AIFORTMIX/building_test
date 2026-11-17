/**
 * 轻微 MCP JNI 原生层
 *
 * 提供 Java/Kotlin 与内核驱动 ioctl 之间的桥接。
 * 通过 /dev/qingwei_mcp 设备节点与内核模块通信。
 *
 * 对应 Kotlin 类: com.kernelai.app.driver.DeviceNode
 */

#include <jni.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <android/log.h>

#define LOG_TAG "qingwei_mcp_jni"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
constexpr size_t kMaxPacketBytes = 40 + 16 * 1024 * 1024;
constexpr unsigned int kWriteMemIoctl = _IOC(_IOC_READ | _IOC_WRITE, 'M', 3, 104);

// The Android ABI is little-endian. Use memcpy for packed, unaligned fields.
bool packetFits(unsigned int command, const jbyte *buffer, size_t size) {
    const size_t header = _IOC_SIZE(command);
    if (_IOC_TYPE(command) != 'M' || header == 0 || size < header) {
        return false;
    }

    size_t capacityOffset;
    switch (_IOC_NR(command)) {
        case 11:  // process list
        case 12:  // module list
        case 13:  // VMA list
        case 17:  // hardware breakpoint list
        case 21:  // call stack
        case 22:  // thread list
            capacityOffset = 24;
            break;
        case 20:  // memory search
            capacityOffset = 560;
            break;
        case 23:  // raw read
            capacityOffset = 32;
            break;
        default:
            return true;
    }

    if (header < capacityOffset + sizeof(uint64_t)) {
        return false;
    }
    uint64_t capacity;
    memcpy(&capacity, buffer + capacityOffset, sizeof(capacity));
    return capacity <= size - header;
}
}  // namespace

// ============================================================
// nativeOpen(path: String): Int
// 以 O_RDWR 模式打开设备节点，返回文件描述符
// ============================================================
extern "C"
JNIEXPORT jint JNICALL
Java_com_kernelai_app_driver_DeviceNode_nativeOpen(
        JNIEnv *env,
        jobject /* this */,
        jstring path) {

    if (path == nullptr) {
        return -EINVAL;
    }
    const char *pathChars = env->GetStringUTFChars(path, nullptr);
    if (pathChars == nullptr) {
        LOGE("nativeOpen: 无法获取路径字符串 (OutOfMemory)");
        return -ENOMEM;
    }

    LOGI("nativeOpen: 正在打开设备节点 '%s'", pathChars);

    int fd = ::open(pathChars, O_RDWR | O_CLOEXEC);
    const int openError = fd < 0 ? errno : 0;
    if (fd < 0) {
        LOGE("nativeOpen: 打开 '%s' 失败, errno=%d (%s)",
             pathChars, errno, strerror(errno));
    } else {
        LOGI("nativeOpen: 成功打开设备, fd=%d", fd);
    }

    env->ReleaseStringUTFChars(path, pathChars);
    return fd < 0 ? -openError : fd;
}

// ============================================================
// nativeClose(fd: Int): Int
// 关闭文件描述符
// ============================================================
extern "C"
JNIEXPORT jint JNICALL
Java_com_kernelai_app_driver_DeviceNode_nativeClose(
        JNIEnv * /* env */,
        jobject /* this */,
        jint fd) {

    LOGI("nativeClose: 正在关闭 fd=%d", fd);

    int ret = ::close(fd);
    if (ret < 0) {
        LOGE("nativeClose: 关闭 fd=%d 失败, errno=%d (%s)",
             fd, errno, strerror(errno));
    } else {
        LOGI("nativeClose: 成功关闭 fd=%d", fd);
    }

    return ret;
}

// ============================================================
// nativeIoctl(fd: Int, command: Int, arg: ByteArray): Int
//
// 执行 ioctl 系统调用。
// arg 字节数组同时作为输入和输出缓冲区：
//   1. 将 Java 字节数组内容拷贝到本地缓冲区
//   2. 将本地缓冲区指针传递给内核 ioctl
//   3. ioctl 返回后，将修改后的缓冲区内容写回 Java 字节数组
//
// 返回值: 非负值为驱动结果，负值为 -errno。
// ============================================================
extern "C"
JNIEXPORT jint JNICALL
Java_com_kernelai_app_driver_DeviceNode_nativeIoctl(
        JNIEnv *env,
        jobject /* this */,
        jint fd,
        jint command,
        jbyteArray arg) {

    if (fd < 0) {
        return -EBADF;
    }
    if (arg == nullptr) {
        return -EINVAL;
    }

    const jsize len = env->GetArrayLength(arg);
    if (len <= 0 || static_cast<size_t>(len) > kMaxPacketBytes) {
        return -EINVAL;
    }

    std::unique_ptr<jbyte, decltype(&free)> buffer(
        static_cast<jbyte *>(malloc(len)), &free);
    if (!buffer) {
        LOGE("nativeIoctl: 内存分配失败 (size=%d)", len);
        return -ENOMEM;
    }

    env->GetByteArrayRegion(arg, 0, len, buffer.get());
    const auto request = static_cast<unsigned int>(command);
    if (env->ExceptionCheck()) {
        return -EFAULT;
    }
    if (!packetFits(request, buffer.get(), len)) {
        return -EINVAL;
    }

    int ret = ::ioctl(fd, request, buffer.get());
    if (ret < 0) {
        const int error = errno;
        ret = -error;
        LOGE("nativeIoctl: ioctl 失败, cmd=0x%X, errno=%d (%s)",
             request, error, strerror(error));
        // A partial write returns EIO and reports the committed byte count.
        if (request != kWriteMemIoctl || error != EIO) {
            return ret;
        }
    }

    env->SetByteArrayRegion(arg, 0, len, buffer.get());
    return env->ExceptionCheck() ? -EFAULT : ret;
}

// ============================================================
// JNI_OnLoad: 动态注册（备用方案）
//
// 当前使用静态注册（Java_ 前缀命名约定），
// 此处保留 JNI_OnLoad 用于版本检查和未来扩展。
// ============================================================
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void * /* reserved */) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv 失败");
        return JNI_ERR;
    }

    LOGI("轻微 MCP JNI 库已加载 (JNI_VERSION_1_6)");
    return JNI_VERSION_1_6;
}
