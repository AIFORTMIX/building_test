// SPDX-License-Identifier: GPL-2.0
#define pr_fmt(fmt) "qingwei_fd: " fmt

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/miscdevice.h>
#include <linux/uaccess.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>
#include <linux/sched/mm.h>
#include <linux/mm.h>
#include <linux/highmem.h>
#include <linux/ioctl.h>
#include <linux/version.h>
#include <linux/slab.h>
#include <linux/list.h>
#include <linux/mutex.h>
#include <linux/vmalloc.h>
#include <linux/ktime.h>
#include <linux/kprobes.h>
#include <asm/pgtable.h>
#include <linux/netfilter.h>
#include <linux/netfilter_ipv4.h>
#include <linux/ip.h>
#include <linux/udp.h>
#include <net/udp.h>
#include <net/ip.h>
#include <linux/pid.h>
#include <linux/dcache.h>
#include <linux/path.h>
#include <net/ipv6.h>
#include <linux/spinlock.h>
#ifdef CONFIG_ARM64_PTR_AUTH
#include <asm/pointer_auth.h>
#endif

#include <linux/capability.h>
#include <linux/user_namespace.h>
#include <linux/compat.h>
#include <linux/overflow.h>
#include <net/net_namespace.h>
#include <net/sock.h>

#define DEVICE_NAME "qingwei_fd"
/* BEGIN GENERATED UAPI */
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

/* END GENERATED UAPI */
/* ---------- 内核版本兼容: TLS 和 PAC 密钥字段访问 ---------- */
/* TPIDR_EL0 在 thread_struct 中的字段名随内核版本变化:
 *   < 5.10:  task->thread.tp_value
 *   >= 5.10: task->thread.uw.tp_value
 */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 10, 0)
    #define QW_GET_TLS(task) ((task)->thread.uw.tp_value)
#else
    #define QW_GET_TLS(task) ((task)->thread.tp_value)
#endif

/* PAC 密钥在 thread_struct 中的字段名随内核版本变化:
 *   5.0-5.6 (CONFIG_ARM64_PTR_AUTH): task->thread.keys.apXx.lo/hi
 *   5.7+  (CONFIG_ARM64_PTR_AUTH):   task->thread.keys_user.apXx.lo/hi
 *   不支持 CONFIG_ARM64_PTR_AUTH 时 pac_valid=0
 */
#if defined(CONFIG_ARM64_PTR_AUTH)
    #if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 7, 0)
        #define QW_HAS_PAC 1
        #define QW_PAC_FIELD(task) (&(task)->thread.keys_user)
    #else
        #define QW_HAS_PAC 1
        #define QW_PAC_FIELD(task) (&(task)->thread.keys)
    #endif
#else
    #define QW_HAS_PAC 0
#endif


/* A file owns its target and bounded reusable batch buffers. ioctl/release
 * cannot race on this state. Every operation still checks the caller's creds. */
struct qw_file {
    struct mutex lock;
    struct task_struct *task;
    int pid;
    char name[64];
    mem_batch_item_t *items;
    void *output;
    size_t output_capacity;
};
static atomic64_t g_total_cpu_ns = ATOMIC64_INIT(0);
static atomic64_t g_call_count = ATOMIC64_INIT(0);
static bool qw_authorized(void)
{
    return ns_capable(&init_user_ns, CAP_SYS_PTRACE);
}

#define QW_PIN_PAGES 64
struct qw_reader {
    struct mm_struct *mm;
    struct page *pages[QW_PIN_PAGES];
    unsigned long base;
    unsigned int count;
};
static void qw_reader_clear(struct qw_reader *r)
{
    if (r->count)
        unpin_user_pages(r->pages, r->count);
    r->count = 0;
}
static bool qw_range_valid(u64 addr, u64 len)
{
    /* Untagged userspace addresses only; never permit wrap or kernel access. */
    return len && addr < TASK_SIZE_MAX && len <= TASK_SIZE_MAX - addr;
}
static int qw_read(struct qw_reader *r, u64 addr, void *buf, size_t len)
{
    size_t done = 0;
    if (!qw_range_valid(addr, len))
        return -EFAULT;
    while (done < len) {
        unsigned long va = (unsigned long)addr + done;
        unsigned long page_va = va & PAGE_MASK;
        unsigned int index;
        size_t n;
        void *map;
        if (!r->count || page_va < r->base ||
            (page_va - r->base) / PAGE_SIZE >= r->count) {
            unsigned long want = min_t(unsigned long, QW_PIN_PAGES,
                DIV_ROUND_UP((va & ~PAGE_MASK) + len - done, PAGE_SIZE));
            long got;
            int locked = 1;
            qw_reader_clear(r);
            mmap_read_lock(r->mm);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 5, 0)
            got = pin_user_pages_remote(r->mm, page_va, want, 0, r->pages, &locked);
#else
            got = pin_user_pages_remote(r->mm, page_va, want, 0, r->pages, NULL, &locked);
#endif
            if (locked)
                mmap_read_unlock(r->mm);
            if (got <= 0)
                return got < 0 ? (int)got : -EFAULT;
            r->base = page_va;
            r->count = got;
        }
        index = (page_va - r->base) / PAGE_SIZE;
        n = min_t(size_t, len - done, PAGE_SIZE - (va & ~PAGE_MASK));
        map = kmap_local_page(r->pages[index]);
        memcpy((char *)buf + done, (char *)map + (va & ~PAGE_MASK), n);
        kunmap_local(map);
        done += n;
        if (!(done & ((256 * 1024) - 1)))
            cond_resched();
    }
    return 0;
}
/* Snapshot referenced leaders under RCU, do all faultable reads after leaving
 * RCU. No substring comm fallback: truncated comm may select another app. */
static int find_pid_by_name(const char *name)
{
    struct task_struct **tasks, *t;
    unsigned int n = 0, i;
    int result = -ESRCH, prefix = -ESRCH;
    bool overflow = false;
    size_t name_len = strnlen(name, 64);
    if (!name_len || name_len == 64)
        return -EINVAL;
    tasks = kvcalloc(8192, sizeof(*tasks), GFP_KERNEL);
    if (!tasks)
        return -ENOMEM;
    rcu_read_lock();
    for_each_process(t) {
        if (n == 8192) { overflow = true; break; }
        get_task_struct(t);
        tasks[n++] = t;
    }
    rcu_read_unlock();
    for (i = 0; i < n; ++i) {
        struct mm_struct *mm = get_task_mm(tasks[i]);
        char cmdline[256] = {0};
        if (mm) {
            struct qw_reader r = { .mm = mm };
            unsigned long start, end;
            spin_lock(&mm->arg_lock);
            start = mm->arg_start;
            end = mm->arg_end;
            spin_unlock(&mm->arg_lock);
            if (end > start && !qw_read(&r, start, cmdline,
                              min_t(size_t, end - start, sizeof(cmdline) - 1))) {
                if (!strcmp(cmdline, name))
                    result = task_pid_vnr(tasks[i]);
                else if (prefix < 0 && !strncmp(cmdline, name, name_len) &&
                         cmdline[name_len] == ':')
                    prefix = task_pid_vnr(tasks[i]);
            }
            qw_reader_clear(&r);
            mmput(mm);
        }
        cond_resched();
        if (result > 0) break;
    }
    for (i = 0; i < n; ++i)
        put_task_struct(tasks[i]);
    kvfree(tasks);
    return result > 0 ? result : overflow ? -E2BIG : prefix;
}
static struct task_struct *get_cached_task_for_target(struct qw_file *ctx,
                                                     int pid, const char *name)
{
    struct task_struct *task;
    struct pid *p;
    if (ctx->task && pid_alive(ctx->task) && READ_ONCE(ctx->task->mm) &&
        ((pid > 0 && pid == ctx->pid) || (pid <= 0 &&
         (!name[0] || !strcmp(ctx->name, name))))) {
        get_task_struct(ctx->task);
        return ctx->task;
    }
    if (pid <= 0) {
        pid = find_pid_by_name(name);
        if (pid <= 0) return ERR_PTR(pid);
    }
    p = find_get_pid(pid);
    task = p ? get_pid_task(p, PIDTYPE_PID) : NULL;
    if (p) put_pid(p);
    if (!task) return ERR_PTR(-ESRCH);
    if (ctx->task) put_task_struct(ctx->task);
    ctx->task = task;
    get_task_struct(task);
    ctx->pid = pid;
    strscpy(ctx->name, name, sizeof(ctx->name));
    return task;
}

/* Match complete file names, not "libfoo.so" inside "libfoo.so.backup".
 * Load bias accounts for the mapping's file offset. No log in the hot path. */
static bool qw_module_match(const char *path, const char *name)
{
    const char *base = strrchr(path, '/');
    base = base ? base + 1 : path;
    return !strcmp(strchr(name, '/') ? path : base, name);
}
static unsigned long get_module_base(struct task_struct *task, const char *name)
{
    struct mm_struct *mm;
    struct vm_area_struct *vma;
    char *pathbuf;
    unsigned long addr = 0, base = 0;
    if (!name[0]) return 0;
    pathbuf = kmalloc(PAGE_SIZE, GFP_KERNEL);
    if (!pathbuf) return 0;
    mm = get_task_mm(task);
    if (!mm) { kfree(pathbuf); return 0; }
    mmap_read_lock(mm);
    while ((vma = find_vma(mm, addr))) {
        if (vma->vm_file) {
            char *path = d_path(&vma->vm_file->f_path, pathbuf, PAGE_SIZE);
            if (!IS_ERR(path) && qw_module_match(path, name) &&
                vma->vm_pgoff <= (vma->vm_start >> PAGE_SHIFT)) {
                base = vma->vm_start - (vma->vm_pgoff << PAGE_SHIFT);
                break;
            }
        }
        addr = vma->vm_end;
    }
    mmap_read_unlock(mm);
    mmput(mm);
    kfree(pathbuf);
    return base;
}
static int enum_modules(struct task_struct *task, char *out, size_t capacity)
{
    struct mm_struct *mm;
    struct vm_area_struct *vma;
    char *pathbuf;
    struct file *previous = NULL;
    unsigned long addr = 0;
    size_t used = 0;
    int count = 0;
    pathbuf = kmalloc(PAGE_SIZE, GFP_KERNEL);
    if (!pathbuf) return -ENOMEM;
    mm = get_task_mm(task);
    if (!mm) { kfree(pathbuf); return -ESRCH; }
    mmap_read_lock(mm);
    while ((vma = find_vma(mm, addr))) {
        if (vma->vm_file && vma->vm_file != previous) {
            char *path = d_path(&vma->vm_file->f_path, pathbuf, PAGE_SIZE);
            if (!IS_ERR(path) && strstr(path, ".so")) {
                const char *name = strrchr(path, '/');
                size_t need;
                name = name ? name + 1 : path;
                need = strlen(name) + 21;
                if (need >= capacity - used) { count = -ENOSPC; break; }
                if (vma->vm_pgoff <= (vma->vm_start >> PAGE_SHIFT)) {
                    used += scnprintf(out + used, capacity - used, "%s@0x%lx\n",
                        name, vma->vm_start - (vma->vm_pgoff << PAGE_SHIFT));
                    ++count;
                }
            }
        }
        if (vma->vm_file) previous = vma->vm_file;
        addr = vma->vm_end;
    }
    mmap_read_unlock(mm);
    mmput(mm);
    kfree(pathbuf);
    return count;
}
#ifdef CONFIG_HAVE_HW_BREAKPOINT
#include <linux/perf_event.h>
#include <linux/hw_breakpoint.h>

/* 动态解析未导出的 HWBP 符号 */
typedef struct perf_event *(*register_user_hw_bp_fn)(struct perf_event_attr *attr,
    perf_overflow_handler_t triggered, void *context, struct task_struct *tsk);
typedef void (*unregister_hw_bp_fn)(struct perf_event *bp);

static register_user_hw_bp_fn g_register_user_hw_bp = NULL;
static unregister_hw_bp_fn g_unregister_hw_bp = NULL;

/* Optional diagnostic feature; disabled if either symbol is unavailable. */
static unsigned long resolve_symbol(const char *name)
{
    struct kprobe kp;
    unsigned long addr;

    memset(&kp, 0, sizeof(kp));
    kp.symbol_name = name;

    if (register_kprobe(&kp) < 0)
        return 0;

    addr = (unsigned long)kp.addr;
    unregister_kprobe(&kp);
    return addr;
}
#endif

/* ---------- HW Breakpoint 状态 ---------- */
#ifdef CONFIG_HAVE_HW_BREAKPOINT
static DEFINE_MUTEX(g_hw_lock);
static struct qw_file *g_hw_owner;
#define QW_HW_MAX_THREADS 1024
static struct perf_event *g_hw_bp_events[QW_HW_MAX_THREADS] = {NULL};   /* Bounded per-thread event table. */
static int g_hw_bp_event_count = 0;
static unsigned long g_hw_bp_addr = 0;
static struct task_struct *g_hw_bp_target_task = NULL;
static unsigned long g_blr_x8_addr = 0;
static unsigned long g_blr_x9_addr = 0;
static bool g_hw_bp_active = false;
static atomic64_t g_hw_bp_hit_count = ATOMIC64_INIT(0);

/* ---------- 坐标快照缓存（环形缓冲区，spinlock 保护） ---------- */
#define SNAPSHOT_CACHE_SIZE 256
#define DEC_SNAP_CACHE_SIZE 512

struct snapshot_entry {
    unsigned long obj_addr;
    u32 x_raw, y_raw, z_raw;   /* 原始 reg 值（用户态转 float） */
    unsigned long jiffies;
};
static struct snapshot_entry g_snapshots[SNAPSHOT_CACHE_SIZE];
static int g_snapshot_head = 0;
static int g_snapshot_count = 0;
static DEFINE_SPINLOCK(g_snapshot_lock);
/* 解码器 hook 状态 */
static int g_hw_bp_mode = 0;               /* 0=坐标hook 2=通用快照hook */
static uint32_t g_hw_bp_regs[8] = {0};
static int g_hw_bp_reg_count = 0;
static decoder_snap_t g_dec_snaps[DEC_SNAP_CACHE_SIZE];
static int g_dec_head = 0;
static int g_dec_count = 0;
static DEFINE_SPINLOCK(g_dec_lock);
#endif

#ifdef CONFIG_HAVE_HW_BREAKPOINT
/* ===================================================================
 * 硬件断点 (HW Breakpoint) 功能
 *
 * 原理：
 *   在目标进程中设置 ARM64 执行断点（DBGBVR/DBGBCR），
 *   当断点触发时 overflow handler 读取 pt_regs 中的寄存器值，
 *   将解密后的坐标缓存到内核环形缓冲区，供用户态通过
 *   CMD_QUERY_SNAPSHOT 查询。
 *
 * 寄存器映射（基于 sub_9D68BA0 逆向分析）：
 *   X19 = Pawn 对象地址（缓存 key）
 *   X8  = X 坐标（float，低 32 位）
 *   X9  = Y 坐标（float，低 32 位）
 *   X10 = Z 坐标（float，低 32 位）
 *
 * 注意：overflow handler 运行在中断上下文，只能使用 spinlock
 *       和原子操作，不能睡眠、不能调用 copy_to/from_user。
 * =================================================================== */

static void hw_bp_overflow_handler(struct perf_event *event,
                                    struct perf_sample_data *data,
                                    struct pt_regs *regs)
{
    unsigned long obj_addr;
    u32 raw_x, raw_y, raw_z;

    if (!regs)
        return;

    /* 模式 2：通用快照 hook —— 按用户态配置记录指定寄存器 */
    if (g_hw_bp_mode >= 2)   /* mode 2: 执行断点\uff1bmode 3: 读写断点\uff0c都记录寄存器快照 */
    {
        int i;
        unsigned long flags;
        spin_lock_irqsave(&g_dec_lock, flags);
        memset(&g_dec_snaps[g_dec_head], 0, sizeof(g_dec_snaps[g_dec_head]));
        g_dec_snaps[g_dec_head].ts_ns = ktime_get_ns();
        g_dec_snaps[g_dec_head].pc = regs->pc;
        g_dec_snaps[g_dec_head].reg_count = (uint32_t)g_hw_bp_reg_count;
        g_dec_snaps[g_dec_head].mode = g_hw_bp_mode;
        for (i = 0; i < g_hw_bp_reg_count && i < 8; i++)
            g_dec_snaps[g_dec_head].regs[i] = regs->regs[g_hw_bp_regs[i]];
        g_dec_head = (g_dec_head + 1) % DEC_SNAP_CACHE_SIZE;
        if (g_dec_count < DEC_SNAP_CACHE_SIZE)
            g_dec_count++;
        spin_unlock_irqrestore(&g_dec_lock, flags);
        atomic64_inc(&g_hw_bp_hit_count);
        return;
    }

    /* 读取寄存器：X19=Pawn, X8/X9/X10=坐标（低32位） */
    obj_addr = regs->regs[19];
    raw_x = (u32)regs->regs[8];
    raw_y = (u32)regs->regs[9];
    raw_z = (u32)regs->regs[10];

    /* 跳过无效地址 */
    if (obj_addr < 0x100000)
        return;

    /* 跳过全零坐标（内核中禁止 float 运算，用 u32 比较） */
    if (raw_x == 0 && raw_y == 0 && raw_z == 0)
        return;

    {
        unsigned long flags;
        spin_lock_irqsave(&g_snapshot_lock, flags);
        g_snapshots[g_snapshot_head].obj_addr = obj_addr;
        g_snapshots[g_snapshot_head].x_raw = raw_x;
        g_snapshots[g_snapshot_head].y_raw = raw_y;
        g_snapshots[g_snapshot_head].z_raw = raw_z;
        g_snapshots[g_snapshot_head].jiffies = jiffies;
        g_snapshot_head = (g_snapshot_head + 1) % SNAPSHOT_CACHE_SIZE;
        if (g_snapshot_count < SNAPSHOT_CACHE_SIZE)
            g_snapshot_count++;
        spin_unlock_irqrestore(&g_snapshot_lock, flags);
    }

    atomic64_inc(&g_hw_bp_hit_count);
}

/* Caller holds g_hw_lock; unregister synchronizes all callbacks. */
static void hw_bp_cleanup(void)
{
    int i;
    for (i = 0; i < g_hw_bp_event_count; i++) {
        if (g_hw_bp_events[i] && g_unregister_hw_bp)
            g_unregister_hw_bp(g_hw_bp_events[i]);
        g_hw_bp_events[i] = NULL;
    }
    g_hw_bp_event_count = 0;
    if (g_hw_bp_target_task) {
        put_task_struct(g_hw_bp_target_task);
        g_hw_bp_target_task = NULL;
    }
    g_hw_bp_active = false;
    g_hw_bp_addr = 0;
    atomic64_set(&g_hw_bp_hit_count, 0);
    g_dec_head = g_dec_count = 0;
    memset(g_dec_snaps, 0, sizeof(g_dec_snaps));
    g_blr_x8_addr = 0;
    g_blr_x9_addr = 0;

    {
        unsigned long flags;
        spin_lock_irqsave(&g_snapshot_lock, flags);
        g_snapshot_head = 0;
        g_snapshot_count = 0;
        memset(g_snapshots, 0, sizeof(g_snapshots));
        spin_unlock_irqrestore(&g_snapshot_lock, flags);
    }
}

static int hw_bp_setup(struct task_struct *task, const hw_bp_cfg_t *cfg,
                       u64 blr_x8, u64 blr_x9)
{
    struct perf_event_attr attr;
    struct task_struct **threads, *t;
    unsigned int n = 0, i;
    int ret = 0;
    bool overflow = false;
    if (!g_register_user_hw_bp || !g_unregister_hw_bp)
        return -EOPNOTSUPP;
    if (!qw_range_valid(cfg->bp_addr, 4) || (cfg->bp_addr & 3) ||
        !(cfg->mode == 0 || (cfg->mode >= 2 && cfg->mode <= 5)) || cfg->reg_count > 8)
        return -EINVAL;
    for (i = 0; i < cfg->reg_count; ++i)
        if (cfg->regs[i] > 30) return -EINVAL;
    threads = kvcalloc(QW_HW_MAX_THREADS, sizeof(*threads), GFP_KERNEL);
    if (!threads) return -ENOMEM;
    rcu_read_lock();
    for_each_thread(task->group_leader, t) {
        if (n == QW_HW_MAX_THREADS) { overflow = true; break; }
        get_task_struct(t);
        threads[n++] = t;
    }
    rcu_read_unlock();
    /* No allocation/perf registration or task final put under RCU. Fail rather
     * than silently omit some threads and report an incomplete hook as ready. */
    if (overflow || !n) { ret = overflow ? -E2BIG : -ESRCH; goto put_threads; }
    hw_bp_cleanup();
    g_hw_bp_mode = cfg->mode;
    g_hw_bp_reg_count = cfg->reg_count;
    memcpy(g_hw_bp_regs, cfg->regs, sizeof(g_hw_bp_regs));
    g_hw_bp_addr = cfg->bp_addr;
    atomic64_set(&g_hw_bp_hit_count, 0);
    hw_breakpoint_init(&attr);
    attr.disabled = 0;
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 13, 0)
    /* The perf core reaps inherited events when the parent is released. */
    attr.inherit = 1;
    attr.inherit_thread = 1;
#endif
    attr.bp_addr = cfg->bp_addr;
    attr.bp_len = HW_BREAKPOINT_LEN_4;
    switch (cfg->mode) {
    case 3: attr.bp_type = HW_BREAKPOINT_RW; break;
    case 4: attr.bp_type = HW_BREAKPOINT_R; break;
    case 5: attr.bp_type = HW_BREAKPOINT_W; break;
    default: attr.bp_type = HW_BREAKPOINT_X; break;
    }
    for (i = 0; i < n; ++i) {
        struct perf_event *ev = g_register_user_hw_bp(&attr,
            hw_bp_overflow_handler, NULL, threads[i]);
        if (IS_ERR(ev)) { ret = PTR_ERR(ev); break; }
        g_hw_bp_events[g_hw_bp_event_count++] = ev;
    }
    if (ret) { hw_bp_cleanup(); goto put_threads; }
    get_task_struct(task);
    g_hw_bp_target_task = task;
    g_blr_x8_addr = blr_x8;
    g_blr_x9_addr = blr_x9;
    g_hw_bp_active = true;
put_threads:
    for (i = 0; i < n; ++i) put_task_struct(threads[i]);
    kvfree(threads);
    return ret;
}

static int hw_bp_update_blr(struct task_struct *task,
                              unsigned long blr_x8, unsigned long blr_x9)
{
    if (!g_hw_bp_active || !g_hw_bp_target_task) {
        pr_warn("HWBP update_blr: no active breakpoint\n");
        return -ENODEV;
    }

    /* 验证目标进程是否匹配 */
    if (g_hw_bp_target_task != task) {
        pr_warn("HWBP update_blr: task mismatch (cached=%d new=%d)\n",
                g_hw_bp_target_task->pid, task->pid);
        return -EINVAL;
    }

    g_blr_x8_addr = blr_x8;
    g_blr_x9_addr = blr_x9;
    pr_info("HWBP BLR updated: blr_x8=0x%lx blr_x9=0x%lx\n",
            blr_x8, blr_x9);
    return 0;
}

static int hw_bp_query_snapshot(unsigned long obj_addr,
                                 u32 *x_raw, u32 *y_raw, u32 *z_raw)
{
    int i;
    int found = -2; /* 默认：暂无快照 */

    {
        unsigned long flags;
        spin_lock_irqsave(&g_snapshot_lock, flags);
        /* 从最新条目开始反向搜索 */
        for (i = 0; i < g_snapshot_count; i++) {
            int idx = (g_snapshot_head - 1 - i + SNAPSHOT_CACHE_SIZE) % SNAPSHOT_CACHE_SIZE;
            if (g_snapshots[idx].obj_addr == obj_addr) {
                *x_raw = g_snapshots[idx].x_raw;
                *y_raw = g_snapshots[idx].y_raw;
                *z_raw = g_snapshots[idx].z_raw;
                found = 0; /* 命中 */
                break;
            }
        }
        spin_unlock_irqrestore(&g_snapshot_lock, flags);
    }

    return found;
}
#else
static void hw_bp_cleanup(void) {}
#endif /* CONFIG_HAVE_HW_BREAKPOINT */


/* Packet capture is a single explicitly owned session. The filter observes
 * UDP flows sent in the selected process context and their reverse direction.
 * Packets sent asynchronously without that context are intentionally omitted. */
#define QW_CAPTURE_FLOWS 64
struct qw_flow { u8 family, reserved; __be16 local_port, remote_port; u8 local[16], remote[16]; };
struct qw_udp { struct qw_flow flow; int offset, length; };
static DEFINE_MUTEX(g_capture_mutex);
static struct qw_file *g_capture_owner;
static bool g_capture_ready;
static struct task_struct *g_capture_task;
static struct {
    spinlock_t lock;
    bool enabled;
    int pid;
    u64 write_seq, read_seq, seen, udp4, udp6, pid_hit;
    u16 port;
    struct qw_flow flows[QW_CAPTURE_FLOWS];
    unsigned int flow_count, flow_next;
    qw_capture_pkt_t packets[QW_CAPTURE_RING];
} g_capture = { .lock = __SPIN_LOCK_UNLOCKED(g_capture.lock) };

static bool qw_udp_parse(struct sk_buff *skb, u8 family, struct qw_udp *out)
{
    struct udphdr udp;
    int start = skb_network_offset(skb), pos, end;
    memset(out, 0, sizeof(*out));
    out->flow.family = family;
    if (start < 0) return false;
    if (family == NFPROTO_IPV4) {
        struct iphdr ip;
        if (skb_copy_bits(skb, start, &ip, sizeof(ip)) || ip.version != 4 ||
            ip.ihl < 5 || ip.protocol != IPPROTO_UDP ||
            (ntohs(ip.frag_off) & (IP_MF | IP_OFFSET))) return false;
        pos = start + ip.ihl * 4;
        end = start + ntohs(ip.tot_len);
        memcpy(out->flow.local, &ip.saddr, 4);
        memcpy(out->flow.remote, &ip.daddr, 4);
    } else if (family == NFPROTO_IPV6) {
        struct ipv6hdr ip;
        u8 next;
        __be16 fragment = 0;
        if (skb_copy_bits(skb, start, &ip, sizeof(ip)) || ip.version != 6) return false;
        next = ip.nexthdr;
        pos = ipv6_skip_exthdr(skb, start + sizeof(ip), &next, &fragment);
        if (pos < 0 || next != IPPROTO_UDP || fragment) return false;
        end = start + sizeof(ip) + ntohs(ip.payload_len);
        memcpy(out->flow.local, &ip.saddr, 16);
        memcpy(out->flow.remote, &ip.daddr, 16);
    } else return false;
    if (pos > end - (int)sizeof(udp) || end > skb->len ||
        skb_copy_bits(skb, pos, &udp, sizeof(udp)) || ntohs(udp.len) < sizeof(udp) ||
        ntohs(udp.len) > end - pos) return false;
    out->offset = pos + sizeof(udp);
    out->length = min_t(int, ntohs(udp.len) - sizeof(udp), QW_CAPTURE_MAX_PAYLOAD);
    out->flow.local_port = udp.source;
    out->flow.remote_port = udp.dest;
    return out->length > 0;
}
static bool qw_reverse_flow(const struct qw_flow *sent, const struct qw_flow *received)
{
    return sent->family == received->family && sent->local_port == received->remote_port &&
        sent->remote_port == received->local_port &&
        !memcmp(sent->local, received->remote, 16) && !memcmp(sent->remote, received->local, 16);
}
static unsigned int qw_capture_hook(void *priv, struct sk_buff *skb,
                                    const struct nf_hook_state *state)
{
    struct qw_udp udp;
    qw_capture_pkt_t *slot;
    unsigned int i;
    bool match = false, tx = state->hook == NF_INET_LOCAL_OUT;
    if (!READ_ONCE(g_capture.enabled) || !qw_udp_parse(skb, state->pf, &udp)) return NF_ACCEPT;
    spin_lock_bh(&g_capture.lock);
    if (!g_capture.enabled) goto out;
    if (state->pf == NFPROTO_IPV4) ++g_capture.udp4; else ++g_capture.udp6;
    if (tx) {
        if (!g_capture_task || current->group_leader != g_capture_task) goto out;
        ++g_capture.pid_hit;
        for (i = 0; i < g_capture.flow_count; ++i)
            if (!memcmp(&g_capture.flows[i], &udp.flow, sizeof(udp.flow))) break;
        if (i == g_capture.flow_count) {
            g_capture.flows[g_capture.flow_next] = udp.flow;
            g_capture.flow_next = (g_capture.flow_next + 1) % QW_CAPTURE_FLOWS;
            if (g_capture.flow_count < QW_CAPTURE_FLOWS) ++g_capture.flow_count;
        }
        g_capture.port = ntohs(udp.flow.local_port);
    } else {
        for (i = 0; i < g_capture.flow_count; ++i)
            if (qw_reverse_flow(&g_capture.flows[i], &udp.flow)) { match = true; break; }
        if (!match) goto out;
    }
    slot = &g_capture.packets[g_capture.write_seq % QW_CAPTURE_RING];
    /* Zero payload tails; all output bytes have defined content. */
    memset(slot, 0, sizeof(*slot));
    if (skb_copy_bits(skb, udp.offset, slot->payload, udp.length)) goto out;
    slot->ts_ns = ktime_get_ns();
    slot->direction = tx ? 0 : 1;
    slot->len = udp.length;
    ++g_capture.write_seq;
    if (g_capture.write_seq - g_capture.read_seq > QW_CAPTURE_RING)
        g_capture.read_seq = g_capture.write_seq - QW_CAPTURE_RING;
    ++g_capture.seen;
out:
    spin_unlock_bh(&g_capture.lock);
    return NF_ACCEPT;
}
static struct nf_hook_ops g_capture_hooks[] = {
    { .hook = qw_capture_hook, .pf = NFPROTO_IPV4, .hooknum = NF_INET_LOCAL_OUT, .priority = NF_IP_PRI_FIRST },
    { .hook = qw_capture_hook, .pf = NFPROTO_IPV4, .hooknum = NF_INET_LOCAL_IN, .priority = NF_IP_PRI_FIRST },
#if IS_ENABLED(CONFIG_IPV6)
    { .hook = qw_capture_hook, .pf = NFPROTO_IPV6, .hooknum = NF_INET_LOCAL_OUT, .priority = NF_IP_PRI_FIRST },
    { .hook = qw_capture_hook, .pf = NFPROTO_IPV6, .hooknum = NF_INET_LOCAL_IN, .priority = NF_IP_PRI_FIRST },
#endif
};
static void qw_capture_disable(void)
{
    spin_lock_bh(&g_capture.lock);
    WRITE_ONCE(g_capture.enabled, false);
    spin_unlock_bh(&g_capture.lock);
    synchronize_net();
}
/* Called with session mutex. On a user fault no unread packets are consumed.
 * Producer overwrite during snapshot is detected instead of returning mixtures. */
static long qw_capture_read(unsigned long arg)
{
    qw_capture_read_t pkt;
    qw_capture_pkt_t *tmp;
    u64 first;
    unsigned int i, n;
    long ret = 0;
    if (copy_from_user(&pkt, (void __user *)arg, sizeof(pkt))) return -EFAULT;
    pkt.count_out = pkt.reserved0 = pkt.reserved1 = 0;
    n = min_t(u32, pkt.max_pkts, QW_CAPTURE_RING);
    if (!n) return copy_to_user((void __user *)arg, &pkt, sizeof(pkt)) ? -EFAULT : 0;
    if (!pkt.out_buf) return -EINVAL;
    tmp = kvmalloc_array(n, sizeof(*tmp), GFP_KERNEL);
    if (!tmp) return -ENOMEM;
    spin_lock_bh(&g_capture.lock);
    first = g_capture.read_seq;
    n = min_t(u64, n, g_capture.write_seq - first);
    spin_unlock_bh(&g_capture.lock);
    for (i = 0; i < n; ++i) {
        spin_lock_bh(&g_capture.lock);
        if (g_capture.read_seq > first) ret = -EAGAIN;
        else tmp[i] = g_capture.packets[(first + i) % QW_CAPTURE_RING];
        spin_unlock_bh(&g_capture.lock);
        if (ret) goto out;
    }
    if (copy_to_user(u64_to_user_ptr(pkt.out_buf), tmp, n * sizeof(*tmp))) { ret = -EFAULT; goto out; }
    pkt.count_out = n;
    if (copy_to_user((void __user *)arg, &pkt, sizeof(pkt))) { ret = -EFAULT; goto out; }
    spin_lock_bh(&g_capture.lock);
    g_capture.read_seq = max_t(u64, g_capture.read_seq, first + n);
    spin_unlock_bh(&g_capture.lock);
out:
    kvfree(tmp);
    return ret;
}

static long qw_batch(struct qw_file *ctx, unsigned long arg)
{
    mem_batch_packet_t pkt;
    struct task_struct *task;
    struct qw_reader reader = {0};
    long ret = 0;
    u32 i;
    u64 requested = 0;
    if (copy_from_user(&pkt, (void __user *)arg, sizeof(pkt))) return -EFAULT;
    pkt.pkg_name[sizeof(pkt.pkg_name) - 1] = '\0';
    if (!pkt.count || pkt.count > QW_BATCH_MAX_ITEMS || pkt.item_size != sizeof(mem_batch_item_t) ||
        !pkt.out_size || pkt.out_size > QW_BATCH_MAX_SIZE || !pkt.items_buf || !pkt.out_buf)
        return -EINVAL;
    if (!ctx->items) {
        ctx->items = kcalloc(QW_BATCH_MAX_ITEMS, sizeof(*ctx->items), GFP_KERNEL);
        if (!ctx->items) return -ENOMEM;
    }
    if (ctx->output_capacity < pkt.out_size) {
        void *new_output = kvmalloc(pkt.out_size, GFP_KERNEL);
        if (!new_output) return -ENOMEM;
        kvfree(ctx->output);
        ctx->output = new_output;
        ctx->output_capacity = pkt.out_size;
    }
    if (copy_from_user(ctx->items, u64_to_user_ptr(pkt.items_buf), pkt.count * sizeof(*ctx->items)))
        return -EFAULT;
    memset(ctx->output, 0, pkt.out_size);
    for (i = 0; i < pkt.count; ++i) {
        ctx->items[i].status = -ESRCH;
        ctx->items[i].reserved = 0;
    }
    task = get_cached_task_for_target(ctx, pkt.pid, pkt.pkg_name);
    if (IS_ERR(task)) { ret = PTR_ERR(task); goto copy_results; }
    reader.mm = get_task_mm(task);
    put_task_struct(task);
    if (!reader.mm) { ret = -ESRCH; goto copy_results; }
    for (i = 0; i < pkt.count; ++i) {
        mem_batch_item_t *item = &ctx->items[i];
        item->status = -EINVAL;
        if (item->size > QW_BATCH_MAX_SIZE - requested) { item->status = -E2BIG; continue; }
        requested += item->size;
        if (!item->size || item->size > QW_BATCH_MAX_SIZE || item->out_offset > pkt.out_size ||
            item->size > pkt.out_size - item->out_offset) continue;
        item->status = qw_read(&reader, item->addr, (char *)ctx->output + item->out_offset, item->size);
        if (item->status)
            memset((char *)ctx->output + item->out_offset, 0, item->size);
        cond_resched();
    }
    qw_reader_clear(&reader);
    mmput(reader.mm);
copy_results:
    if (copy_to_user(u64_to_user_ptr(pkt.items_buf), ctx->items, pkt.count * sizeof(*ctx->items)) ||
        copy_to_user(u64_to_user_ptr(pkt.out_buf), ctx->output, pkt.out_size)) ret = -EFAULT;
    return ret;
}

static long qw_hw_command(struct qw_file *ctx, unsigned int cmd, unsigned long arg,
                          mem_packet_t *pkt, struct task_struct *task)
{
#ifdef CONFIG_HAVE_HW_BREAKPOINT
    long ret = 0;
    mutex_lock(&g_hw_lock);
    if (g_hw_owner && g_hw_owner != ctx) { ret = -EBUSY; goto out; }
    switch (cmd) {
    case CMD_CLEAR_HOOK:
        hw_bp_cleanup();
        g_hw_owner = NULL;
        break;
    case CMD_GET_HWBP_STATS: {
        hwbp_stats_t st = {0};
        unsigned long flags;
        st.hit_count = atomic64_read(&g_hw_bp_hit_count);
        st.bp_addr = g_hw_bp_addr;
        spin_lock_irqsave(&g_snapshot_lock, flags);
        st.snapshot_count = g_snapshot_count;
        spin_unlock_irqrestore(&g_snapshot_lock, flags);
        st.active = g_hw_bp_active;
        ret = copy_to_user((void __user *)arg, &st, sizeof(st)) ? -EFAULT : 0;
        break;
    }
    case CMD_READ_DEC_SNAP: {
        decoder_snap_t *snaps;
        unsigned long flags;
        u32 n, i, want = min_t(u64, pkt->size, DEC_SNAP_CACHE_SIZE);
        if (!pkt->user_buf || !want) { ret = -EINVAL; break; }
        snaps = kcalloc(want, sizeof(*snaps), GFP_KERNEL);
        if (!snaps) { ret = -ENOMEM; break; }
        spin_lock_irqsave(&g_dec_lock, flags);
        n = min_t(u32, want, g_dec_count);
        for (i = 0; i < n; ++i)
            snaps[i] = g_dec_snaps[(g_dec_head - 1 - i + DEC_SNAP_CACHE_SIZE) % DEC_SNAP_CACHE_SIZE];
        spin_unlock_irqrestore(&g_dec_lock, flags);
        pkt->offset_count = n;
        if (copy_to_user(u64_to_user_ptr(pkt->user_buf), snaps, n * sizeof(*snaps)) ||
            copy_to_user((void __user *)arg, pkt, sizeof(*pkt))) ret = -EFAULT;
        kfree(snaps);
        break;
    }
    case CMD_QUERY_SNAPSHOT: {
        u32 raw[3] = {0};
        ret = hw_bp_query_snapshot(pkt->addr, &raw[0], &raw[1], &raw[2]);
        if (copy_to_user(u64_to_user_ptr(pkt->user_buf), raw, sizeof(raw))) ret = -EFAULT;
        break;
    }
    case CMD_SET_BLR_ADDRS: {
        u64 blr[2];
        if (copy_from_user(blr, u64_to_user_ptr(pkt->user_buf), sizeof(blr))) ret = -EFAULT;
        else ret = hw_bp_update_blr(task, blr[0], blr[1]);
        break;
    }
    case CMD_SET_HW_BP:
    case CMD_SET_HOOK_CFG: {
        hw_bp_cfg_t cfg = {0};
        u64 blr[2] = {0};
        if (cmd == CMD_SET_HOOK_CFG) {
            if (copy_from_user(&cfg, u64_to_user_ptr(pkt->user_buf), sizeof(cfg))) { ret = -EFAULT; break; }
        } else {
            cfg.bp_addr = pkt->addr;
            cfg.mode = pkt->offset_count;
            if (pkt->user_buf && copy_from_user(blr, u64_to_user_ptr(pkt->user_buf), sizeof(blr))) {
                ret = -EFAULT; break;
            }
        }
        ret = hw_bp_setup(task, &cfg, blr[0], blr[1]);
        if (!ret) g_hw_owner = ctx;
        /* A failed reconfiguration may have removed the old events. */
        if (!g_hw_bp_active) g_hw_owner = NULL;
        if (cmd == CMD_SET_HOOK_CFG) {
            cfg.ret = ret;
            cfg.reserved = 0;
            if (copy_to_user(u64_to_user_ptr(pkt->user_buf), &cfg, sizeof(cfg))) {
                hw_bp_cleanup(); g_hw_owner = NULL; ret = -EFAULT;
            }
        }
        break;
    }
    default: ret = -ENOTTY;
    }
out:
    mutex_unlock(&g_hw_lock);
    return ret;
#else
    if (cmd == CMD_GET_HWBP_STATS) {
        hwbp_stats_t st = {0};
        return copy_to_user((void __user *)arg, &st, sizeof(st)) ? -EFAULT : 0;
    }
    return -EOPNOTSUPP;
#endif
}

static long qw_capture_command(struct qw_file *ctx, unsigned int cmd, unsigned long arg,
                               struct task_struct *task)
{
    long ret = 0;
    mutex_lock(&g_capture_mutex);
    if (g_capture_owner && g_capture_owner != ctx) { ret = -EBUSY; goto out; }
    switch (cmd) {
    case CMD_CAPTURE_START:
        if (!g_capture_ready) { ret = -EOPNOTSUPP; break; }
        qw_capture_disable();
        if (g_capture_task) put_task_struct(g_capture_task);
        g_capture_task = task->group_leader;
        get_task_struct(g_capture_task);
        spin_lock_bh(&g_capture.lock);
        g_capture.pid = task_tgid_vnr(task);
        g_capture.read_seq = g_capture.write_seq = 0;
        g_capture.seen = g_capture.udp4 = g_capture.udp6 = g_capture.pid_hit = 0;
        g_capture.port = g_capture.flow_count = g_capture.flow_next = 0;
        memset(g_capture.flows, 0, sizeof(g_capture.flows));
        WRITE_ONCE(g_capture.enabled, true);
        spin_unlock_bh(&g_capture.lock);
        g_capture_owner = ctx;
        break;
    case CMD_CAPTURE_STOP:
        qw_capture_disable();
        /* Retain ownership of unread packets until file close/restart. */
        break;
    case CMD_CAPTURE_READ:
        ret = qw_capture_read(arg);
        break;
    case CMD_CAPTURE_STATS: {
        qw_capture_stats_t st = {0};
        spin_lock_bh(&g_capture.lock);
        st.enabled = g_capture.enabled;
        st.target_pid = g_capture.pid;
        st.learned_local_port = g_capture.port;
        st.ring_count = g_capture.write_seq - g_capture.read_seq;
        st.total_seen = g_capture.seen;
        st.stat_udp_v4 = g_capture.udp4;
        st.stat_udp_v6 = g_capture.udp6;
        st.stat_pid_hit = g_capture.pid_hit;
        spin_unlock_bh(&g_capture.lock);
        ret = copy_to_user((void __user *)arg, &st, sizeof(st)) ? -EFAULT : 0;
        break;
    }
    default: ret = -ENOTTY;
    }
out:
    mutex_unlock(&g_capture_mutex);
    return ret;
}

static long qw_memory_command(struct qw_file *ctx, unsigned int cmd, unsigned long arg,
                              mem_packet_t *pkt, struct task_struct *task)
{
    void *buf = NULL;
    long ret = 0;
    u64 addr = pkt->addr;
    switch (cmd) {
    case CMD_GET_BASE: {
        char name[256];
        u64 base;
        long length = strncpy_from_user(name, u64_to_user_ptr(pkt->user_buf), sizeof(name));
        if (length < 0) return length;
        if (!length || length == sizeof(name)) return -EINVAL;
        base = get_module_base(task, name);
        if (!base) return -ENOENT;
        return copy_to_user(u64_to_user_ptr(pkt->user_buf), &base, sizeof(base)) ? -EFAULT : 0;
    }
    case CMD_ENUM_MODULES: {
        int count;
        if (pkt->size < 64 || pkt->size > 65536) return -EINVAL;
        buf = kvzalloc(pkt->size, GFP_KERNEL);
        if (!buf) return -ENOMEM;
        count = enum_modules(task, buf, pkt->size);
        pkt->pid = count;
        if (copy_to_user(u64_to_user_ptr(pkt->user_buf), buf, pkt->size) ||
            copy_to_user((void __user *)arg, pkt, sizeof(*pkt))) ret = -EFAULT;
        else if (count < 0) ret = count;
        break;
    }
    case CMD_READ_MEM:
    case CMD_READ_PTR: {
        struct qw_reader reader = { .mm = get_task_mm(task) };
        int i;
        if (!reader.mm) return -ESRCH;
        if (!pkt->size || pkt->size > QW_READ_MAX_SIZE) { ret = -EINVAL; goto read_out; }
        buf = kvmalloc(pkt->size, GFP_KERNEL);
        if (!buf) { ret = -ENOMEM; goto read_out; }
        if (cmd == CMD_READ_PTR && pkt->offset_count) {
            for (i = 0; i < pkt->offset_count; ++i) {
                u64 ptr = 0, current;
                if (check_add_overflow(addr, i ? pkt->offsets[i-1] : 0ULL, &current)) {
                    ret = -EOVERFLOW; break;
                }
                ret = qw_read(&reader, current, &ptr, sizeof(ptr));
                if (ret) break;
                addr = ptr;
            }
            if (!ret && check_add_overflow(addr, pkt->offsets[pkt->offset_count - 1], &addr)) ret = -EOVERFLOW;
        } else if (pkt->offset_count && check_add_overflow(addr, pkt->offsets[0], &addr)) ret = -EOVERFLOW;
        if (!ret) ret = qw_read(&reader, addr, buf, pkt->size);
        if (!ret && copy_to_user(u64_to_user_ptr(pkt->user_buf), buf, pkt->size)) ret = -EFAULT;
read_out:
        qw_reader_clear(&reader);
        mmput(reader.mm);
        break;
    }
    case CMD_WRITE_MEM: {
        int wrote;
        if (!pkt->size || pkt->size > QW_READ_MAX_SIZE) return -EINVAL;
        if (pkt->offset_count && check_add_overflow(addr, pkt->offsets[0], &addr)) return -EOVERFLOW;
        if (!qw_range_valid(addr, pkt->size)) return -EFAULT;
        buf = kvmalloc(pkt->size, GFP_KERNEL);
        if (!buf) return -ENOMEM;
        if (copy_from_user(buf, u64_to_user_ptr(pkt->user_buf), pkt->size)) { ret = -EFAULT; break; }
        /* Core mm code handles COW, permissions, dirty and instruction caches.
         * No FOLL_FORCE: a read-only mapping is not made writable by this API. */
        wrote = access_process_vm(task, addr, buf, pkt->size, FOLL_WRITE);
        if (wrote != pkt->size) ret = -EFAULT;
        break;
    }
    case CMD_READ_TLS_PAC: {
        tls_pac_packet_t result = {0};
        /* Saved thread state is diagnostic; running threads can change TLS.
         * exec serialization prevents reading half-replaced PAC keys. */
        down_read(&task->signal->exec_update_lock);
        result.tls = QW_GET_TLS(task);
#if QW_HAS_PAC
        {
            const typeof(*QW_PAC_FIELD(task)) *keys = QW_PAC_FIELD(task);
            result.apia_lo = keys->apia.lo; result.apia_hi = keys->apia.hi;
            result.apib_lo = keys->apib.lo; result.apib_hi = keys->apib.hi;
            result.apda_lo = keys->apda.lo; result.apda_hi = keys->apda.hi;
            result.apdb_lo = keys->apdb.lo; result.apdb_hi = keys->apdb.hi;
            result.apga_lo = keys->apga.lo; result.apga_hi = keys->apga.hi;
            result.pac_valid = 1;
        }
#endif
        up_read(&task->signal->exec_update_lock);
        ret = copy_to_user(u64_to_user_ptr(pkt->user_buf), &result, sizeof(result)) ? -EFAULT : 0;
        break;
    }
    case CMD_CAPTURE_START: return qw_capture_command(ctx, cmd, arg, task);
    case CMD_SET_HW_BP:
    case CMD_SET_BLR_ADDRS:
    case CMD_QUERY_SNAPSHOT:
    case CMD_SET_HOOK_CFG: return qw_hw_command(ctx, cmd, arg, pkt, task);
    default: ret = -ENOTTY;
    }
    kvfree(buf);
    return ret;
}

static long qw_dispatch(struct qw_file *ctx, unsigned int cmd, unsigned long arg)
{
    mem_packet_t pkt;
    struct task_struct *task;
    long ret;
    switch (cmd) {
    case CMD_GET_CAPS: {
        qw_caps_t caps = { .abi_version = QW_ABI_VERSION,
            .features = QW_FEATURE_BATCH_LARGE | QW_FEATURE_PER_FILE |
                        QW_FEATURE_BIND_TARGET,
            .max_items = QW_BATCH_MAX_ITEMS, .max_out_size = QW_BATCH_MAX_SIZE,
            .max_item_size = QW_BATCH_MAX_SIZE };
#ifdef CONFIG_HAVE_HW_BREAKPOINT
        if (g_register_user_hw_bp && g_unregister_hw_bp) caps.features |= QW_FEATURE_HWBP;
#endif
        if (g_capture_ready) caps.features |= QW_FEATURE_CAPTURE;
        return copy_to_user((void __user *)arg, &caps, sizeof(caps)) ? -EFAULT : 0;
    }
    case CMD_GET_MODULE_INFO: {
        module_info_t info = {0};
        /* Historical field name: this measures wall duration, including waits. */
        info.cpu_time_ns = atomic64_read(&g_total_cpu_ns);
        info.call_count = atomic64_read(&g_call_count);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 4, 0)
        info.mem_bytes = THIS_MODULE->mem[MOD_TEXT].size + THIS_MODULE->mem[MOD_DATA].size +
                         THIS_MODULE->mem[MOD_RODATA].size + THIS_MODULE->mem[MOD_RO_AFTER_INIT].size;
#else
        info.mem_bytes = THIS_MODULE->core_layout.size;
#endif
        return copy_to_user((void __user *)arg, &info, sizeof(info)) ? -EFAULT : 0;
    }
    case CMD_GET_HWBP_STATS:
    case CMD_CLEAR_HOOK: return qw_hw_command(ctx, cmd, arg, NULL, NULL);
    case CMD_CAPTURE_STOP:
    case CMD_CAPTURE_READ:
    case CMD_CAPTURE_STATS: return qw_capture_command(ctx, cmd, arg, NULL);
    case CMD_READ_BATCH: return qw_batch(ctx, arg);
    case CMD_GET_BASE: case CMD_READ_MEM: case CMD_WRITE_MEM: case CMD_READ_PTR:
    case CMD_SET_HW_BP: case CMD_SET_BLR_ADDRS: case CMD_QUERY_SNAPSHOT:
    case CMD_FIND_PID: case CMD_READ_TLS_PAC: case CMD_ENUM_MODULES:
    case CMD_CAPTURE_START: case CMD_READ_DEC_SNAP: case CMD_SET_HOOK_CFG:
    case CMD_BIND_TARGET:
        break;
    default: return -ENOTTY;
    }
    if (copy_from_user(&pkt, (void __user *)arg, sizeof(pkt))) return -EFAULT;
    pkt.pkg_name[sizeof(pkt.pkg_name) - 1] = '\0';
    pkt.reserved0 = pkt.reserved1 = 0;
    if (cmd == CMD_BIND_TARGET) {
        /* A private candidate makes failure atomic for the existing attachment. */
        struct qw_file candidate = {0};
        if (pkt.pid <= 0 && !pkt.pkg_name[0]) return -EINVAL;
        task = get_cached_task_for_target(&candidate, pkt.pid, pkt.pkg_name);
        if (IS_ERR(task)) return PTR_ERR(task);
        pkt.pid = task_pid_vnr(task);
        ret = copy_to_user((void __user *)arg, &pkt, sizeof(pkt)) ? -EFAULT : 0;
        if (!ret) {
#ifdef CONFIG_HAVE_HW_BREAKPOINT
            mutex_lock(&g_hw_lock);
            if (g_hw_owner == ctx && g_hw_bp_target_task &&
                g_hw_bp_target_task->group_leader != task->group_leader) {
                hw_bp_cleanup();
                g_hw_owner = NULL;
            }
            mutex_unlock(&g_hw_lock);
#endif
            if (ctx->task) put_task_struct(ctx->task);
            ctx->task = candidate.task;
            candidate.task = NULL;
            ctx->pid = pkt.pid;
            strscpy(ctx->name, pkt.pkg_name, sizeof(ctx->name));
        }
        if (candidate.task) put_task_struct(candidate.task);
        put_task_struct(task);
        return ret;
    }
    if (cmd == CMD_READ_DEC_SNAP) return qw_hw_command(ctx, cmd, arg, &pkt, NULL);
    if (pkt.offset_count < 0 || pkt.offset_count > ARRAY_SIZE(pkt.offsets)) return -EINVAL;
    if (cmd == CMD_FIND_PID) {
        pkt.pid = find_pid_by_name(pkt.pkg_name);
        if (copy_to_user((void __user *)arg, &pkt, sizeof(pkt))) return -EFAULT;
        return pkt.pid > 0 ? 0 : pkt.pid;
    }
    task = get_cached_task_for_target(ctx, pkt.pid, pkt.pkg_name);
    if (IS_ERR(task)) return PTR_ERR(task);
    ret = qw_memory_command(ctx, cmd, arg, &pkt, task);
    put_task_struct(task);
    return ret;
}
static long device_ioctl(struct file *filp, unsigned int cmd, unsigned long arg)
{
    struct qw_file *ctx = filp->private_data;
    u64 start;
    long ret;
    if (!qw_authorized()) return -EPERM;
    start = ktime_get_ns();
    if (mutex_lock_interruptible(&ctx->lock)) return -ERESTARTSYS;
    ret = qw_dispatch(ctx, cmd, arg);
    mutex_unlock(&ctx->lock);
    atomic64_add(ktime_get_ns() - start, &g_total_cpu_ns);
    atomic64_inc(&g_call_count);
    return ret;
}
static int device_open(struct inode *inode, struct file *file)
{
    struct qw_file *ctx;
    if (!qw_authorized()) return -EPERM;
    ctx = kzalloc(sizeof(*ctx), GFP_KERNEL);
    if (!ctx) return -ENOMEM;
    mutex_init(&ctx->lock);
    file->private_data = ctx;
    return 0;
}
static int device_release(struct inode *inode, struct file *file)
{
    struct qw_file *ctx = file->private_data;
#ifdef CONFIG_HAVE_HW_BREAKPOINT
    mutex_lock(&g_hw_lock);
    if (g_hw_owner == ctx) { hw_bp_cleanup(); g_hw_owner = NULL; }
    mutex_unlock(&g_hw_lock);
#endif
    mutex_lock(&g_capture_mutex);
    if (g_capture_owner == ctx) {
        qw_capture_disable();
        g_capture_owner = NULL;
        if (g_capture_task) put_task_struct(g_capture_task);
        g_capture_task = NULL;
        spin_lock_bh(&g_capture.lock);
        g_capture.read_seq = g_capture.write_seq = 0;
        g_capture.pid = 0;
        g_capture.port = g_capture.flow_count = g_capture.flow_next = 0;
        g_capture.seen = g_capture.udp4 = g_capture.udp6 = g_capture.pid_hit = 0;
        spin_unlock_bh(&g_capture.lock);
    }
    mutex_unlock(&g_capture_mutex);
    if (ctx->task) put_task_struct(ctx->task);
    kfree(ctx->items);
    kvfree(ctx->output);
    kfree(ctx);
    return 0;
}
static const struct file_operations fops = {
    .owner = THIS_MODULE, .open = device_open, .release = device_release,
    .unlocked_ioctl = device_ioctl,
#ifdef CONFIG_COMPAT
    .compat_ioctl = compat_ptr_ioctl,
#endif
    .llseek = no_llseek,
};
static struct miscdevice misc_dev = {
    .minor = MISC_DYNAMIC_MINOR, .name = DEVICE_NAME, .fops = &fops, .mode = 0600,
};
static int __init mem_reader_init(void)
{
    int ret;
#ifdef CONFIG_HAVE_HW_BREAKPOINT
    g_register_user_hw_bp = (register_user_hw_bp_fn)resolve_symbol("register_user_hw_breakpoint");
    g_unregister_hw_bp = (unregister_hw_bp_fn)resolve_symbol("unregister_hw_breakpoint");
    if (!g_register_user_hw_bp || !g_unregister_hw_bp) {
        g_register_user_hw_bp = NULL;
        g_unregister_hw_bp = NULL;
        pr_warn("HWBP unavailable\n");
    }
#endif
    ret = nf_register_net_hooks(&init_net, g_capture_hooks, ARRAY_SIZE(g_capture_hooks));
    g_capture_ready = !ret;
    if (ret) pr_warn("UDP capture unavailable: %d\n", ret);
    ret = misc_register(&misc_dev);
    if (ret) {
        if (g_capture_ready) nf_unregister_net_hooks(&init_net, g_capture_hooks, ARRAY_SIZE(g_capture_hooks));
        return ret;
    }
    pr_info("/dev/%s ready, ABI %u\n", DEVICE_NAME, QW_ABI_VERSION);
    return 0;
}
static void __exit mem_reader_exit(void)
{
    misc_deregister(&misc_dev);
    if (g_capture_ready) nf_unregister_net_hooks(&init_net, g_capture_hooks, ARRAY_SIZE(g_capture_hooks));
    hw_bp_cleanup();
}
module_init(mem_reader_init);
module_exit(mem_reader_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Qingwei authorized process memory and diagnostic driver");
MODULE_VERSION("2.0");
