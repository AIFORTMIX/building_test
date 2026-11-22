/* SPDX-License-Identifier: GPL-2.0 WITH Linux-syscall-note */
#ifndef QINGWEI_UAPI_H
#define QINGWEI_UAPI_H
#include <linux/types.h>
#include <linux/ioctl.h>

/* Fixed ARM64 legacy layout, also used by rebuilt 32-bit clients. Never put
 * pointers, size_t, long or implicit padding into this wire protocol. */
typedef struct {
    __s32 pid;
    char pkg_name[64];
    __u32 reserved0;
    __aligned_u64 addr, size, offsets[8];
    __s32 offset_count;
    __u32 reserved1;
    __aligned_u64 user_buf;
} mem_packet_t;
typedef struct {
    __aligned_u64 addr;
    __u32 size, out_offset;
    __s32 status;
    __u32 reserved;
} mem_batch_item_t;
typedef struct {
    __s32 pid;
    char pkg_name[64];
    __u32 count, item_size, reserved;
    __aligned_u64 items_buf, out_buf, out_size;
} mem_batch_packet_t;
typedef struct {
    __aligned_u64 cpu_time_ns, call_count, mem_bytes;
} module_info_t;
typedef struct {
    __aligned_u64 hit_count, bp_addr;
    __s32 snapshot_count, active;
} hwbp_stats_t;
typedef struct {
    __aligned_u64 tls, apia_lo, apia_hi, apib_lo, apib_hi;
    __aligned_u64 apda_lo, apda_hi, apdb_lo, apdb_hi, apga_lo, apga_hi;
    __s32 pac_valid, reserved;
} tls_pac_packet_t;
typedef struct {
    __aligned_u64 ts_ns, pc, regs[8];
    __u32 reg_count, mode;
} decoder_snap_t;
typedef struct {
    __aligned_u64 bp_addr;
    __u32 mode, reg_count, regs[8];
    __s32 ret;
    __u32 reserved;
} hw_bp_cfg_t;
#define QW_CAPTURE_MAX_PAYLOAD 2048
#define QW_CAPTURE_RING 256
typedef struct {
    __aligned_u64 ts_ns;
    __u32 direction, len;
    __u8 payload[QW_CAPTURE_MAX_PAYLOAD];
} qw_capture_pkt_t;
typedef struct {
    __u32 max_pkts, reserved0;
    __aligned_u64 out_buf;
    __u32 count_out, reserved1;
} qw_capture_read_t;
typedef struct {
    __u32 enabled;
    __s32 target_pid;
    __u16 learned_local_port, reserved;
    __u32 ring_count;
    __aligned_u64 total_seen, stat_udp_v4, stat_udp_v6, stat_pid_hit;
} qw_capture_stats_t;

#define QW_ABI_VERSION 2
#define QW_FEATURE_BATCH_LARGE (1U << 0)
#define QW_FEATURE_HWBP        (1U << 1)
#define QW_FEATURE_CAPTURE     (1U << 2)
#define QW_FEATURE_PER_FILE    (1U << 3)
#define QW_FEATURE_BIND_TARGET (1U << 4)
#define QW_BATCH_MAX_ITEMS 512U
#define QW_BATCH_MAX_SIZE (2U * 1024U * 1024U)
#define QW_READ_MAX_SIZE (16U * 1024U * 1024U)
typedef struct {
    __u32 abi_version, features, max_items, max_out_size;
    __u32 max_item_size, reserved[3];
} qw_caps_t;
#define MEM_IOCTL_MAGIC 'Q'
#define CMD_GET_BASE        _IOWR(MEM_IOCTL_MAGIC, 1, mem_packet_t)
#define CMD_READ_MEM        _IOWR(MEM_IOCTL_MAGIC, 2, mem_packet_t)
#define CMD_WRITE_MEM       _IOWR(MEM_IOCTL_MAGIC, 3, mem_packet_t)
#define CMD_READ_PTR        _IOWR(MEM_IOCTL_MAGIC, 4, mem_packet_t)
#define CMD_READ_BATCH      _IOWR(MEM_IOCTL_MAGIC, 5, mem_batch_packet_t)
#define CMD_GET_MODULE_INFO _IOR(MEM_IOCTL_MAGIC, 6, module_info_t)
#define CMD_SET_HW_BP       _IOW(MEM_IOCTL_MAGIC, 7, mem_packet_t)
#define CMD_SET_BLR_ADDRS   _IOW(MEM_IOCTL_MAGIC, 8, mem_packet_t)
#define CMD_QUERY_SNAPSHOT  _IOWR(MEM_IOCTL_MAGIC, 9, mem_packet_t)
#define CMD_GET_HWBP_STATS  _IOR(MEM_IOCTL_MAGIC, 10, hwbp_stats_t)
#define CMD_FIND_PID        _IOWR(MEM_IOCTL_MAGIC, 11, mem_packet_t)
#define CMD_READ_TLS_PAC    _IOWR(MEM_IOCTL_MAGIC, 12, mem_packet_t)
#define CMD_ENUM_MODULES    _IOWR(MEM_IOCTL_MAGIC, 13, mem_packet_t)
#define CMD_CAPTURE_START  _IOWR(MEM_IOCTL_MAGIC, 14, mem_packet_t)
#define CMD_CAPTURE_STOP   _IO(MEM_IOCTL_MAGIC, 15)
#define CMD_CAPTURE_READ   _IOWR(MEM_IOCTL_MAGIC, 16, qw_capture_read_t)
#define CMD_CAPTURE_STATS  _IOR(MEM_IOCTL_MAGIC, 17, qw_capture_stats_t)
#define CMD_READ_DEC_SNAP   _IOWR(MEM_IOCTL_MAGIC, 18, mem_packet_t)
#define CMD_SET_HOOK_CFG    _IOWR(MEM_IOCTL_MAGIC, 19, mem_packet_t)
#define CMD_GET_CAPS        _IOR(MEM_IOCTL_MAGIC, 20, qw_caps_t)
#define CMD_CLEAR_HOOK      _IO(MEM_IOCTL_MAGIC, 21)
#define CMD_BIND_TARGET     _IOWR(MEM_IOCTL_MAGIC, 22, mem_packet_t)

/* Keep both userspace and embedded single-file kernel copies ABI checked. */
#ifdef __cplusplus
#define QW_ABI_ASSERT(c) static_assert(c, #c)
#else
#define QW_ABI_ASSERT(c) _Static_assert(c, #c)
#endif
QW_ABI_ASSERT(sizeof(mem_packet_t) == 168);
QW_ABI_ASSERT(__builtin_offsetof(mem_packet_t, user_buf) == 160);
QW_ABI_ASSERT(sizeof(mem_batch_item_t) == 24);
QW_ABI_ASSERT(sizeof(mem_batch_packet_t) == 104);
QW_ABI_ASSERT(sizeof(qw_caps_t) == 32);
QW_ABI_ASSERT(sizeof(hw_bp_cfg_t) == 56);
QW_ABI_ASSERT(sizeof(decoder_snap_t) == 88);
QW_ABI_ASSERT(sizeof(tls_pac_packet_t) == 96);
QW_ABI_ASSERT(CMD_READ_MEM == 0xc0a85102U);
QW_ABI_ASSERT(CMD_BIND_TARGET == 0xc0a85116U);
#undef QW_ABI_ASSERT
#endif
