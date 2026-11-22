#ifndef QINGWEI_API_H
#define QINGWEI_API_H

#include <stdint.h>
#include <stddef.h>
#include "qingwei_uapi.h"

#ifdef __cplusplus
extern "C" {
#endif

// 设备节点路径（与内核模块一致）
#define QINGWEI_DEVICE "/dev/qingwei_fd"

// 初始化：打开设备，返回文件描述符，失败返回 -1
int qingwei_open(void);

// 关闭设备
void qingwei_close(int fd);
/* Duplicates the connection for a worker. Close with qingwei_close. */
int qingwei_dup(int fd);
int qingwei_get_caps(int fd, qw_caps_t *caps);
int qingwei_clear_hook(int fd);
/* Bind the connection's target explicitly. dup() descriptors share that target.
 * Legacy modules are supported by sending the saved target on each command. */
int qingwei_bind_target(int fd, int pid, const char *pkg_name);

// --------------------------------------------------------------
// 核心读写函数（参数均需传入设备 fd）
// --------------------------------------------------------------

// 1. 获取指定 so 的基地址
//    - 若 pid > 0，则直接使用该 pid；若 pid <= 0，则使用 pkg_name 自动查找进程
//    - 成功返回基址，失败返回 0（注意：基址可能为 0，所以失败时 errno 会被设置）
unsigned long qingwei_get_base(int fd, int pid, const char *pkg_name, const char *so_name);

// 2. 直接读取内存（任意大小）
//    - 成功返回 0，失败返回 -1
int qingwei_read_mem(int fd, unsigned long addr, void *buf, size_t size);
int qingwei_read_mem_pkg(int fd, const char *pkg_name, unsigned long addr, void *buf, size_t size);

typedef mem_batch_item_t qingwei_batch_item_t;

typedef module_info_t qingwei_module_info_t;

int qingwei_read_batch_pkg(int fd, const char *pkg_name,
                           qingwei_batch_item_t *items, uint32_t count,
                           void *out_buf, size_t out_size);

int qingwei_get_module_info(int fd, qingwei_module_info_t *info);

// 3. 直接写入内存（任意大小）
//    - 成功返回 0，失败返回 -1（注意：地址必须可写，否则返回 -EPERM）
int qingwei_write_mem(int fd, unsigned long addr, const void *buf, size_t size);

// 4. 指针链读取：从 addr 开始，依次解引用 offset_count 个 64 位指针，
//    最后一层加上 offsets[offset_count-1] 偏移，然后读取 size 字节到 buf
//    - 成功返回 0，失败返回 -1
int qingwei_read_ptr(int fd, unsigned long addr,
                     const unsigned long *offsets, int offset_count,
                     void *buf, size_t size);
int qingwei_read_ptr_pkg(int fd, const char *pkg_name, unsigned long addr,
                         const unsigned long *offsets, int offset_count,
                         void *buf, size_t size);

// --------------------------------------------------------------
// 便捷封装（常用类型）
// --------------------------------------------------------------

// 读取 8 字节指针
uint64_t qingwei_read_pointer(int fd, unsigned long addr);
uint64_t qingwei_read_pointer_pkg(int fd, const char *pkg_name, unsigned long addr);

// 读取 4 字节无符号整数
uint32_t qingwei_read_u32(int fd, unsigned long addr);
uint32_t qingwei_read_u32_pkg(int fd, const char *pkg_name, unsigned long addr);

// 读取 4 字节浮点数
float qingwei_read_float(int fd, unsigned long addr);
float qingwei_read_float_pkg(int fd, const char *pkg_name, unsigned long addr);

// 读取字符串（最多 size-1 个字符，自动添加 '\0'）
int qingwei_read_string(int fd, unsigned long addr, char *buf, size_t size);

// 写入 4 字节无符号整数
int qingwei_write_u32(int fd, unsigned long addr, uint32_t val);

// 写入 4 字节浮点数
int qingwei_write_float(int fd, unsigned long addr, float val);

// ====== 硬件断点 (HWBP) ======

// 设置硬件执行断点
//   bp_addr:     断点地址（如 libUE4.so + 0x09D68B94）
//   blr_x8_addr: BLR X8 指令地址（模式匹配结果）
//   blr_x9_addr: BLR X9 指令地址（模式匹配结果）
//   成功返回 0，失败返回 -errno
int qingwei_set_hw_bp(int fd, int pid, const char *pkg_name,
                       unsigned long bp_addr,
                       unsigned long blr_x8_addr,
                       unsigned long blr_x9_addr);

// 更新 BLR 地址（断点不变，用于重试场景）
//   成功返回 0，失败返回 -errno
int qingwei_update_blr_addrs(int fd, unsigned long blr_x8_addr,
                              unsigned long blr_x9_addr);

// 查询坐标快照（按对象地址）
//   成功返回 0（命中），-2 = 暂无快照，-14 = 缓冲区错误
int qingwei_query_snapshot(int fd, const char *pkg_name, unsigned long obj_addr,
                            float *x, float *y, float *z);

// HWBP 断点统计信息
typedef hwbp_stats_t qingwei_hwbp_stats_t;

// 获取 HWBP 断点统计信息
//   成功返回 0，失败返回 -1
int qingwei_get_hwbp_stats(int fd, qingwei_hwbp_stats_t *stats);

/* 通用 hook：mode 0=坐标hook 2=通用快照hook（记录用户指定寄存器） */
typedef decoder_snap_t qingwei_dec_snap_t;
#define QINGWEI_DEC_SNAP_MAX 512
/* 设置通用硬件断点：成功返0，失败返回负 errno（如 -ENOSYS/-ESRCH/-EINVAL） */
int qingwei_set_hook(int fd, int pid, unsigned long bp_addr, int mode,
	const int *regs, int reg_count);
int qingwei_read_dec_snap(int fd, qingwei_dec_snap_t *snaps,
	uint32_t max, uint32_t *count);

// ====== TLS + PAC 密钥读取（无 ptrace） ======

// TLS + PAC 密钥数据结构
typedef tls_pac_packet_t qingwei_tls_pac_t;

// 从内核 task_struct 直接读取 TLS 和 PACGA 密钥（无需 ptrace）
//   pid > 0: 直接使用 PID; pid <= 0: 使用 pkg_name 自动查找
//   成功返回 0，失败返回 -1
int qingwei_read_tls_pac(int fd, int pid, const char *pkg_name, qingwei_tls_pac_t *out);

// 内核态进程查找（通过 for_each_process，无需 /proc 或 pidof）
//   成功返回 PID (> 0)，失败返回 -1
int qingwei_find_pid(int fd, const char *pkg_name);

// 指针链便捷读取：读取最终指向的 4 字节无符号整数
uint32_t qingwei_read_ptr_u32(int fd, unsigned long addr,
                              const unsigned long *offsets, int offset_count);

// 指针链便捷读取：读取最终指向的 8 字节指针
uint64_t qingwei_read_ptr_pointer(int fd, unsigned long addr,
                                  const unsigned long *offsets, int offset_count);

// 枚举目标进程已加载的 .so 模块列表
//   pid > 0: 直接使用 PID; pid <= 0: 使用 pkg_name 自动查找
//   out_buf: 输出缓冲区，格式 "module_name@0xbase\n" 逐行排列
//   buf_size: 缓冲区大小（建议 8192）
//   返回模块数量（>0 成功），-1 失败
int qingwei_enum_modules(int fd, int pid, const char *pkg_name,
                         char *out_buf, size_t buf_size);

#ifdef __cplusplus
}
#endif

// 坐标收发截取（内核 netfilter 抓包）
#define QW_CAPTURE_MAX_PAYLOAD 2048
typedef qw_capture_pkt_t qingwei_capture_pkt_t;

typedef qw_capture_read_t qingwei_capture_read_t;

typedef qw_capture_stats_t qingwei_capture_stats_t;

#ifdef __cplusplus
extern "C" {
#endif
int qingwei_capture_start(int fd, int pid);
int qingwei_capture_stop(int fd);
int qingwei_capture_read(int fd, qingwei_capture_pkt_t *out, uint32_t max_pkts, uint32_t *count);
int qingwei_capture_stats(int fd, qingwei_capture_stats_t *st);
#ifdef __cplusplus
}
#endif

#endif /* QINGWEI_API_H */
