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
#include <linux/capability.h>
#include <linux/compat.h>
#include <linux/user_namespace.h>
#include <linux/pid.h>
#include <linux/dcache.h>
#include <linux/path.h>
#include <linux/overflow.h>
#include <linux/limits.h>
#include <net/ip.h>
#include <net/net_namespace.h>
#include <asm/pgtable.h>
#include <linux/netfilter.h>
#include <linux/netfilter_ipv4.h>
#include <linux/ip.h>
#include <linux/udp.h>
#include <net/udp.h>
#include <net/ipv6.h>
#include <linux/spinlock.h>
#ifdef CONFIG_ARM64_PTR_AUTH
#include <asm/pointer_auth.h>
#endif

#ifdef CONFIG_HAVE_HW_BREAKPOINT
#include <linux/perf_event.h>
#include <linux/hw_breakpoint.h>

/* 动态解析未导出的 HWBP 符号 */
typedef struct perf_event *(*register_user_hw_bp_fn)(struct perf_event_attr *attr,
    perf_overflow_handler_t triggered, void *context, struct task_struct *tsk);
typedef void (*unregister_hw_bp_fn)(struct perf_event *bp);

static register_user_hw_bp_fn g_register_user_hw_bp = NULL;
static unregister_hw_bp_fn g_unregister_hw_bp = NULL;

/* 通过 kprobe 解析未导出符号（Android 6.1 唯一可靠方式） */
static unsigned long resolve_symbol(const char *name)
{
#if IS_ENABLED(CONFIG_KPROBES)
    struct kprobe kp;
    unsigned long addr;

    memset(&kp, 0, sizeof(kp));
    kp.symbol_name = name;

    if (register_kprobe(&kp) < 0)
        return 0;

    addr = (unsigned long)kp.addr;
    unregister_kprobe(&kp);
    return addr;
#else
    return 0;
#endif
}
#endif

#define DEVICE_NAME "qingwei_fd"

/* ---------- 用户态接口 ---------- */
typedef struct {
    int pid;
    char pkg_name[64];
    unsigned long addr;
    size_t size;
    unsigned long offsets[8];
    int offset_count;
    unsigned long user_buf;
} mem_packet_t;

typedef struct {
    unsigned long addr;
    uint32_t size;
    uint32_t out_offset;
    int32_t status;
} mem_batch_item_t;

typedef struct {
    int pid;
    char pkg_name[64];
    uint32_t count;
    uint32_t item_size;
    unsigned long items_buf;
    unsigned long out_buf;
    size_t out_size;
} mem_batch_packet_t;

typedef struct {
    unsigned long long cpu_time_ns;   // 模块累计 CPU 时间（纳秒）
    unsigned long long call_count;    // 总 ioctl 调用次数
    unsigned long mem_bytes;          // 模块常驻内存大小（字节）
} module_info_t;

typedef struct {
    unsigned long long hit_count;    // 断点触发次数
    unsigned long long bp_addr;      // 断点地址
    int snapshot_count;              // 当前快照缓存条目数
    int active;                      // 1=激活, 0=未激活
} hwbp_stats_t;

/* TLS + PAC 密钥数据包：从内核 task_struct 直接读取，无需 ptrace */
typedef struct {
    uint64_t tls;            // TPIDR_EL0
    uint64_t apia_lo, apia_hi;
    uint64_t apib_lo, apib_hi;
    uint64_t apda_lo, apda_hi;
    uint64_t apdb_lo, apdb_hi;
    uint64_t apga_lo, apga_hi;
    int32_t  pac_valid;      // 1 = PAC 密钥有效, 0 = 内核不支持
    int32_t  reserved;
} tls_pac_packet_t;

#define MEM_IOCTL_MAGIC 'Q'

/* ================= 坐标收发截取：netfilter 抓包 ================= */
#define QW_CAPTURE_MAX_PAYLOAD 2048
#define QW_CAPTURE_RING 256

typedef struct {
    uint64_t ts_ns;
    uint32_t direction; /* 0=TX 1=RX */
    uint32_t len;
    uint8_t payload[QW_CAPTURE_MAX_PAYLOAD];
} qw_capture_pkt_t;

typedef struct {
    uint32_t max_pkts;
    unsigned long out_buf;
    uint32_t count_out;
} qw_capture_read_t;

typedef struct {
    uint32_t enabled;
    int target_pid;
    uint16_t learned_local_port;
    uint32_t ring_count;
    uint64_t total_seen;
    uint64_t stat_udp_v4;
    uint64_t stat_udp_v6;
    uint64_t stat_pid_hit;
} qw_capture_stats_t;
#define CMD_GET_BASE            _IOWR(MEM_IOCTL_MAGIC, 1, mem_packet_t)
#define CMD_READ_MEM            _IOWR(MEM_IOCTL_MAGIC, 2, mem_packet_t)
#define CMD_WRITE_MEM           _IOWR(MEM_IOCTL_MAGIC, 3, mem_packet_t)
#define CMD_READ_PTR            _IOWR(MEM_IOCTL_MAGIC, 4, mem_packet_t)
#define CMD_READ_BATCH          _IOWR(MEM_IOCTL_MAGIC, 5, mem_batch_packet_t)
#define CMD_GET_MODULE_INFO     _IOR(MEM_IOCTL_MAGIC, 6, module_info_t)
#define CMD_SET_HW_BP           _IOW(MEM_IOCTL_MAGIC, 7, mem_packet_t)
#define CMD_SET_BLR_ADDRS       _IOW(MEM_IOCTL_MAGIC, 8, mem_packet_t)
#define CMD_QUERY_SNAPSHOT      _IOWR(MEM_IOCTL_MAGIC, 9, mem_packet_t)
#define CMD_GET_HWBP_STATS      _IOR(MEM_IOCTL_MAGIC, 10, hwbp_stats_t)
#define CMD_FIND_PID            _IOWR(MEM_IOCTL_MAGIC, 11, mem_packet_t)
#define CMD_READ_TLS_PAC        _IOWR(MEM_IOCTL_MAGIC, 12, mem_packet_t)
#define CMD_ENUM_MODULES        _IOWR(MEM_IOCTL_MAGIC, 13, mem_packet_t)
#define CMD_CAPTURE_START       _IOWR(MEM_IOCTL_MAGIC, 14, mem_packet_t)
#define CMD_CAPTURE_STOP        _IO(MEM_IOCTL_MAGIC, 15)
#define CMD_CAPTURE_READ        _IOWR(MEM_IOCTL_MAGIC, 16, qw_capture_read_t)
#define CMD_CAPTURE_STATS       _IOR(MEM_IOCTL_MAGIC, 17, qw_capture_stats_t)
#define CMD_READ_DEC_SNAP      _IOWR(MEM_IOCTL_MAGIC, 18, mem_packet_t)
#define CMD_SET_HOOK_CFG       _IOWR(MEM_IOCTL_MAGIC, 19, mem_packet_t)

/* Additive v2 negotiation; legacy ARM64 command layouts remain unchanged. */
#define QW_ABI_VERSION 2
#define QW_FEATURE_BATCH_LARGE (1U << 0)
#define QW_FEATURE_HWBP        (1U << 1)
#define QW_FEATURE_CAPTURE     (1U << 2)
#define QW_FEATURE_PER_FILE    (1U << 3)
#define QW_FEATURE_BIND_TARGET (1U << 4)
typedef struct {
    uint32_t abi_version, features, max_items, max_out_size;
    uint32_t max_item_size, reserved[3];
} qw_caps_t;
#define CMD_GET_CAPS     _IOR(MEM_IOCTL_MAGIC, 20, qw_caps_t)
#define CMD_CLEAR_HOOK   _IO(MEM_IOCTL_MAGIC, 21)
#define CMD_BIND_TARGET  _IOWR(MEM_IOCTL_MAGIC, 22, mem_packet_t)

#define QW_BATCH_MAX_ITEMS 512
#define QW_BATCH_MAX_SIZE  (2 * 1024 * 1024)

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

/* ---------- 全局变量 ---------- */
/* Each lookup returns its own referenced task. No global borrowed-task cache. */

struct qw_file {
    struct mutex lock;
    struct task_struct *task;
    int pid;
    char name[64];
};

static atomic64_t g_total_cpu_ns = ATOMIC64_INIT(0);
static atomic64_t g_call_count = ATOMIC64_INIT(0);

/* 解码器 hook 快照：hook sub_184E3150 输出点 (STRB W8,[X19] 后)，X19=输出指针 */
typedef struct decoder_snap {
	uint64_t ts_ns;
	uint64_t pc;
	uint64_t regs[8];
	uint32_t reg_count;
	uint32_t mode;
} decoder_snap_t;

/* 用户态 hook 配置：断点地址 + 模式 + 记录哪些寄存器（0-30） */
typedef struct {
	unsigned long bp_addr;
	uint32_t mode;
	uint32_t reg_count;
	uint32_t regs[8];
	int ret;   /* 驱动写回的错误码，用户态可读；0=成功 */
} hw_bp_cfg_t;

_Static_assert(sizeof(mem_packet_t) == 168, "mem_packet ARM64 wire layout");
_Static_assert(sizeof(mem_batch_packet_t) == 104, "batch packet wire layout");
_Static_assert(sizeof(mem_batch_item_t) == 24, "batch item wire layout");
_Static_assert(sizeof(hw_bp_cfg_t) == 56, "HWBP config wire layout");
_Static_assert(sizeof(decoder_snap_t) == 88, "decoder snapshot wire layout");

/* ---------- HW Breakpoint 状态 ---------- */
#ifdef CONFIG_HAVE_HW_BREAKPOINT
static DEFINE_MUTEX(g_hw_bp_lock);
static struct qw_file *g_hw_owner;
/* Bound registration resources without truncating normal game thread groups. */
#define QW_HW_MAX_THREADS 1024
static struct perf_event *g_hw_bp_events[QW_HW_MAX_THREADS] = {NULL};   /* 每线程一个 perf 断点（perf 用户断点只绑单线程，须遍历进程所有线程才能命中） */
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

/* Remote memory access must pin pages and handle faults/COW through the MM API.
 * Addresses are deliberately untagged; overflowing or kernel ranges are rejected.
 * access_process_vm is exported by the same Android GKI used for qingwei_mcp. */
static bool qw_range_valid(unsigned long addr, size_t len)
{
    return len && addr < TASK_SIZE_MAX && len <= TASK_SIZE_MAX - addr;
}

static int qw_access_memory(struct task_struct *task, unsigned long addr,
                            void *buf, size_t len, bool write)
{
    int done;

    if (!task || !buf || len > INT_MAX || !qw_range_valid(addr, len))
        return -EFAULT;
    done = access_process_vm(task, addr, buf, (int)len,
                             write ? FOLL_WRITE : 0);
    if (done != len)
        return done < 0 ? done : -EFAULT;
    return done;
}

static int manual_read_memory(struct task_struct *task, unsigned long addr,
                              void *buf, size_t len)
{
    return qw_access_memory(task, addr, buf, len, false);
}

static int manual_write_memory(struct task_struct *task, unsigned long addr,
                               void *buf, size_t len)
{
    return qw_access_memory(task, addr, buf, len, true);
}

static int qw_validate_packet(const mem_packet_t *pkt)
{
    if (!memchr(pkt->pkg_name, '\0', sizeof(pkt->pkg_name)))
        return -EINVAL;
    return 0;
}

static int qw_resolve_address(struct task_struct *task, const mem_packet_t *pkt,
                              bool pointer_chain, unsigned long *result)
{
    unsigned long addr = pkt->addr;
    int i, ret;

    if (pkt->offset_count < 0 || pkt->offset_count > ARRAY_SIZE(pkt->offsets))
        return -EINVAL;
    if (pointer_chain && pkt->offset_count) {
        for (i = 0; i < pkt->offset_count; i++) {
            uint64_t value;

            if (i && check_add_overflow(addr, pkt->offsets[i - 1], &addr))
                return -EOVERFLOW;
            ret = manual_read_memory(task, addr, &value, sizeof(value));
            if (ret < 0)
                return ret;
            addr = (unsigned long)value;
            if (i == pkt->offset_count - 1 &&
                check_add_overflow(addr, pkt->offsets[i], &addr))
                return -EOVERFLOW;
        }
    } else if (pkt->offset_count &&
               check_add_overflow(addr, pkt->offsets[0], &addr)) {
        return -EOVERFLOW;
    }
    if (!qw_range_valid(addr, pkt->size))
        return -EFAULT;
    *result = addr;
    return 0;
}

/* Take task references under RCU; all faultable accesses and mmput happen
 * outside RCU. Never select an unrelated process by truncated comm substring. */
#define QW_MAX_LOOKUP_TASKS 8192
static int find_pid_by_name(const char *name)
{
    struct task_struct **tasks, *task;
    unsigned int count = 0, i;
    size_t name_len = strnlen(name, 64);
    int exact = -ESRCH, prefix = -ESRCH;
    bool overflow = false;

    if (!name_len || name_len == 64)
        return -EINVAL;
    tasks = kvcalloc(QW_MAX_LOOKUP_TASKS, sizeof(*tasks), GFP_KERNEL);
    if (!tasks)
        return -ENOMEM;

    rcu_read_lock();
    for_each_process(task) {
        if (!task_pid_vnr(task))
            continue;
        if (count == QW_MAX_LOOKUP_TASKS) {
            overflow = true;
            break;
        }
        get_task_struct(task);
        tasks[count++] = task;
    }
    rcu_read_unlock();

    for (i = 0; i < count; i++) {
        struct mm_struct *mm = get_task_mm(tasks[i]);
        char cmdline[512] = {0};
        unsigned long start, end;
        int got;

        if (!mm)
            continue;
        spin_lock(&mm->arg_lock);
        start = mm->arg_start;
        end = mm->arg_end;
        spin_unlock(&mm->arg_lock);
        mmput(mm);
        if (end <= start)
            continue;
        /* A short result is usable only if it includes argv[0]'s terminator.
         * A concurrent exec may change the mm; such samples simply fail. */
        got = access_process_vm(tasks[i], start, cmdline,
                    min_t(unsigned long, end - start, sizeof(cmdline) - 1), 0);
        if (got > 0 && memchr(cmdline, '\0', got)) {
            if (!strcmp(cmdline, name))
                exact = task_pid_vnr(tasks[i]);
            else if (prefix < 0 && !strncmp(cmdline, name, name_len) &&
                     cmdline[name_len] == ':')
                prefix = task_pid_vnr(tasks[i]);
        }
        if (exact > 0)
            break;
        cond_resched();
    }
    for (i = 0; i < count; i++)
        put_task_struct(tasks[i]);
    kvfree(tasks);
    return exact > 0 ? exact : overflow ? -E2BIG : prefix;
}

static struct task_struct *get_target_task(int pid, const char *pkg_name)
{
    struct task_struct *task;
    struct pid *target;

    if (pid <= 0) {
        pid = find_pid_by_name(pkg_name);
        if (pid <= 0)
            return ERR_PTR(pid);
    }
    target = find_get_pid(pid);
    task = target ? get_pid_task(target, PIDTYPE_PID) : NULL;
    if (target)
        put_pid(target);
    return task ? task : ERR_PTR(-ESRCH);
}

/* Stable per-file attachment: explicit reads of another task do not replace
 * it. In particular, HWBP self-tests must not make a killed PID reusable. */
static void qw_remember_target(struct qw_file *ctx, struct task_struct *task,
                                int pid, const char *name)
{
    get_task_struct(task);
    if (ctx->task)
        put_task_struct(ctx->task);
    ctx->task = task;
    ctx->pid = pid > 0 ? pid : task_pid_vnr(task);
    strscpy(ctx->name, name, sizeof(ctx->name));
}

static struct task_struct *qw_context_target(struct qw_file *ctx, int pid,
                                             const char *name, bool fresh)
{
    struct task_struct *task;
    bool implicit = pid <= 0 && !name[0];
    bool matches = ctx && ctx->task &&
        (implicit || (pid > 0 && pid == ctx->pid) ||
         (pid <= 0 && name[0] && !strcmp(name, ctx->name)));

    if (!ctx)
        return get_target_task(pid, name);
    if (!fresh && matches) {
        if (!pid_alive(ctx->task))
            return ERR_PTR(-ESRCH);
        get_task_struct(ctx->task);
        return ctx->task;
    }
    if (implicit)
        return ERR_PTR(-ENOTCONN);
    task = get_target_task(pid, name);
    if (IS_ERR(task))
        return task;
    if (!fresh && !ctx->task)
        qw_remember_target(ctx, task, pid, name);
    return task;
}

/* ---------- 获取模块基址 ---------- */
/* 简单的大小写不敏感字符串查找 */
static char *stristr(const char *haystack, const char *needle)
{
    if (!haystack || !needle || !*needle)
        return NULL;
    size_t nlen = strlen(needle);
    const char *p = haystack;
    while (*p) {
        size_t i;
        for (i = 0; i < nlen; i++) {
            char a = p[i];
            char b = needle[i];
            if (a >= 'A' && a <= 'Z') a += 32;
            if (b >= 'A' && b <= 'Z') b += 32;
            if (a != b) break;
        }
        if (i == nlen) return (char *)p;
        p++;
    }
    return NULL;
}

static unsigned long get_module_base(struct task_struct *task, const char *mod_name)
{
    struct mm_struct *mm = get_task_mm(task);
    unsigned long base = 0;
    unsigned long addr = 0;
    struct vm_area_struct *vma;
    int vma_count = 0;
    int so_count = 0;
    int logged = 0;
    char *pathbuf = kmalloc(PAGE_SIZE, GFP_KERNEL);
    if (!pathbuf) {
        if (mm)
            mmput(mm);
        return 0;
    }

    if (!mm) {
        pr_info("get_module_base: mm unavailable (pid=%d comm=%s)\n",
                task->pid, task->comm);
        kfree(pathbuf);
        return 0;
    }

    pr_info("get_module_base: searching '%s' in pid=%d comm=%s\n",
            mod_name, task->pid, task->comm);

    mmap_read_lock(mm);
    while ((vma = find_vma(mm, addr)) != NULL) {
        vma_count++;
        if (vma->vm_file) {
            char *path = d_path(&vma->vm_file->f_path, pathbuf, PAGE_SIZE);
            if (!IS_ERR(path)) {
                /* 1. 优先大小写敏感匹配 */
                if (strstr(path, mod_name)) {
                    base = vma->vm_start;
                    pr_info("get_module_base: FOUND '%s' at 0x%lx (path=%s)\n",
                            mod_name, base, path);
                    break;
                }
                /* 记录 .so 模块（前10个）用于诊断 */
                if (strstr(path, ".so")) {
                    so_count++;
                    if (logged < 10) {
                        pr_info("get_module_base:   so[%d] 0x%lx %s\n",
                                so_count, vma->vm_start, path);
                        logged++;
                    }
                }
            }
        }
        addr = vma->vm_end;
    }
    mmap_read_unlock(mm);

    /* 2. 大小写敏感未命中，尝试大小写不敏感匹配 */
    if (base == 0) {
        addr = 0;
        mmap_read_lock(mm);
        while ((vma = find_vma(mm, addr)) != NULL) {
            if (vma->vm_file) {
                char *path = d_path(&vma->vm_file->f_path, pathbuf, PAGE_SIZE);
                if (!IS_ERR(path) && stristr(path, mod_name)) {
                    base = vma->vm_start;
                    pr_info("get_module_base: FOUND (case-insensitive) '%s' at 0x%lx (path=%s)\n",
                            mod_name, base, path);
                    break;
                }
            }
            addr = vma->vm_end;
        }
        mmap_read_unlock(mm);
    }
    mmput(mm);   /* 统一释放（修复双重 mmput UAF） */

    if (base == 0) {
        pr_info("get_module_base: NOT FOUND '%s' (vma_count=%d so_count=%d pid=%d)\n",
                mod_name, vma_count, so_count, task->pid);
    }

    kfree(pathbuf);
    return base;
}

/* ---------- 枚举已加载模块（仅返回包含 .so 的路径） ---------- */
static int enum_modules(struct task_struct *task, char *out_buf, size_t buf_size)
{
    struct mm_struct *mm = get_task_mm(task);
    unsigned long addr = 0;
    struct vm_area_struct *vma;
    char *pathbuf;
    size_t written = 0;
    int count = 0;

    if (!mm)
        return 0;
    if (!out_buf || buf_size < 2) {
        mmput(mm);
        return 0;
    }
    out_buf[0] = '\0';

    pathbuf = kmalloc(PAGE_SIZE, GFP_KERNEL);
    if (!pathbuf) {
        mmput(mm);
        return -ENOMEM;
    }

    mmap_read_lock(mm);
    while ((vma = find_vma(mm, addr)) != NULL) {
        if (vma->vm_file) {
            char *path = d_path(&vma->vm_file->f_path, pathbuf, PAGE_SIZE);
            if (!IS_ERR(path) && strstr(path, ".so")) {
                const char *base = strrchr(path, '/');
                base = base ? base + 1 : path;
                size_t len = strlen(base);
                /* 格式: "base_name@vma_start\n" */
                size_t need = len + 20;
                if (written + need < buf_size) {
                    written += scnprintf(out_buf + written, buf_size - written,
                                         "%s@0x%lx\n", base, vma->vm_start);
                    count++;
                }
            }
        }
        addr = vma->vm_end;
    }
    mmap_read_unlock(mm);
    mmput(mm);
    kfree(pathbuf);
    return count;
}

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

/* g_hw_bp_lock held; unregister waits for all in-flight callbacks. */
static void hw_bp_cleanup_locked(void)
{
    int i;
    for (i = 0; i < g_hw_bp_event_count; i++) {
        if (g_hw_bp_events[i] && g_unregister_hw_bp)
            g_unregister_hw_bp(g_hw_bp_events[i]);
        g_hw_bp_events[i] = NULL;
    }
    g_hw_bp_event_count = 0;
    g_hw_owner = NULL;
    if (g_hw_bp_target_task) {
        put_task_struct(g_hw_bp_target_task);
        g_hw_bp_target_task = NULL;
    }
    g_hw_bp_active = false;
    g_hw_bp_addr = 0;
    g_blr_x8_addr = 0;
    g_blr_x9_addr = 0;

    {
        unsigned long flags;
        spin_lock_irqsave(&g_snapshot_lock, flags);
        g_snapshot_head = 0;
        g_snapshot_count = 0;
        memset(g_snapshots, 0, sizeof(g_snapshots));
        spin_unlock_irqrestore(&g_snapshot_lock, flags);
        spin_lock_irqsave(&g_dec_lock, flags);
        g_dec_head = 0;
        g_dec_count = 0;
        memset(g_dec_snaps, 0, sizeof(g_dec_snaps));
        spin_unlock_irqrestore(&g_dec_lock, flags);
    }
    atomic64_set(&g_hw_bp_hit_count, 0);
}

static void hw_bp_cleanup(void)
{
    mutex_lock(&g_hw_bp_lock);
    hw_bp_cleanup_locked();
    mutex_unlock(&g_hw_bp_lock);
}

static int hw_bp_setup(struct task_struct *task, unsigned long bp_addr,
                        unsigned long blr_x8, unsigned long blr_x9, int mode,
                        const hw_bp_cfg_t *cfg, struct qw_file *owner)
{
    struct perf_event_attr attr;
    struct task_struct **threads, *t;
    unsigned int count = 0, i;
    bool overflow = false;
    int ret = 0;

    if (mode < 0 || mode > 5 || !qw_range_valid(bp_addr, 4))
        return -EINVAL;
    if (cfg) {
        if (cfg->reg_count > ARRAY_SIZE(cfg->regs))
            return -EINVAL;
        for (i = 0; i < cfg->reg_count; i++)
            if (cfg->regs[i] > 30)
                return -EINVAL;
    }
    threads = kcalloc(ARRAY_SIZE(g_hw_bp_events), sizeof(*threads), GFP_KERNEL);
    if (!threads)
        return -ENOMEM;

    mutex_lock(&g_hw_bp_lock);
    if (g_hw_owner && g_hw_owner != owner) {
        ret = -EBUSY;
        goto out;
    }
    if (!g_register_user_hw_bp || !g_unregister_hw_bp) {
        ret = -EOPNOTSUPP;
        goto out;
    }
    rcu_read_lock();
    for_each_thread(task, t) {
        if (count == ARRAY_SIZE(g_hw_bp_events)) {
            overflow = true;
            break;
        }
        get_task_struct(t);
        threads[count++] = t;
    }
    rcu_read_unlock();
    if (!count || overflow) {
        ret = overflow ? -E2BIG : -ESRCH;
        goto out;
    }

    hw_bp_cleanup_locked();
    g_hw_bp_mode = mode;
    if (cfg) {
        g_hw_bp_reg_count = cfg->reg_count;
        memset(g_hw_bp_regs, 0, sizeof(g_hw_bp_regs));
        memcpy(g_hw_bp_regs, cfg->regs,
               cfg->reg_count * sizeof(cfg->regs[0]));
    }
    hw_breakpoint_init(&attr);
    attr.disabled = 0;
    attr.bp_addr = bp_addr;
    attr.bp_len = HW_BREAKPOINT_LEN_4;
    switch (mode) {
    case 3: attr.bp_type = HW_BREAKPOINT_RW; break;
    case 4: attr.bp_type = HW_BREAKPOINT_R; break;
    case 5: attr.bp_type = HW_BREAKPOINT_W; break;
    default: attr.bp_type = HW_BREAKPOINT_X; break;
    }
    /* Allocation and registration may sleep: never hold RCU here. */
    for (i = 0; i < count; i++) {
        struct perf_event *event = g_register_user_hw_bp(&attr,
                         hw_bp_overflow_handler, NULL, threads[i]);
        if (IS_ERR(event)) {
            ret = PTR_ERR(event);
            hw_bp_cleanup_locked();
            goto out;
        }
        g_hw_bp_events[g_hw_bp_event_count++] = event;
    }
    get_task_struct(task);
    g_hw_bp_target_task = task;
    g_hw_bp_addr = bp_addr;
    g_blr_x8_addr = blr_x8;
    g_blr_x9_addr = blr_x9;
    g_hw_bp_active = true;
    g_hw_owner = owner;
out:
    mutex_unlock(&g_hw_bp_lock);
    for (i = 0; i < count; i++)
        put_task_struct(threads[i]);
    kfree(threads);
    return ret;
}

static int hw_bp_update_blr(struct task_struct *task,
                             unsigned long blr_x8, unsigned long blr_x9,
                             struct qw_file *owner)
{
    int ret = 0;

    mutex_lock(&g_hw_bp_lock);
    if (g_hw_owner && g_hw_owner != owner)
        ret = -EPERM;
    else if (!g_hw_bp_active || !g_hw_bp_target_task)
        ret = -ENODEV;
    else if (g_hw_bp_target_task->group_leader != task->group_leader)
        ret = -EINVAL;
    else {
        g_blr_x8_addr = blr_x8;
        g_blr_x9_addr = blr_x9;
    }
    mutex_unlock(&g_hw_bp_lock);
    return ret;
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
/* CONFIG_HAVE_HW_BREAKPOINT 未启用时的桩函数 */
static void hw_bp_cleanup(void) {}
static int hw_bp_setup(struct task_struct *task, unsigned long bp_addr,
                        unsigned long blr_x8, unsigned long blr_x9, int mode,
                        const hw_bp_cfg_t *cfg, struct qw_file *owner) { return -EOPNOTSUPP; }
static int hw_bp_update_blr(struct task_struct *task,
                              unsigned long blr_x8, unsigned long blr_x9,
                              struct qw_file *owner) { return -EOPNOTSUPP; }
static int hw_bp_query_snapshot(unsigned long obj_addr,
                                 u32 *x_raw, u32 *y_raw, u32 *z_raw) { return -ENOSYS; }
#endif /* CONFIG_HAVE_HW_BREAKPOINT */

/* ================= netfilter 抓包实现 ================= */
static struct {
    spinlock_t lock;
    qw_capture_pkt_t pkt[QW_CAPTURE_RING];
    uint32_t head;
    uint32_t count;
    uint16_t learned_local_port;
    atomic_t enabled;
    atomic_t target_pid;
    atomic64_t total_seen;
    atomic64_t stat_udp_v4;
    atomic64_t stat_udp_v6;
    atomic64_t stat_pid_hit;
} g_capture = {
    .lock = __SPIN_LOCK_UNLOCKED(g_capture.lock),
};

static void qw_capture_store(struct sk_buff *skb, int dir, int is_v6)
{
    struct udphdr *uh;
    qw_capture_pkt_t *slot;
    int len;
    int hdr_len;

    hdr_len = is_v6 ? (int)sizeof(struct ipv6hdr) : ip_hdrlen(skb);
    uh = (struct udphdr *)(skb_network_header(skb) + hdr_len);
    len = ntohs(uh->len) - (int)sizeof(struct udphdr);
    if (len <= 0)
        return;
    if (len > QW_CAPTURE_MAX_PAYLOAD)
        len = QW_CAPTURE_MAX_PAYLOAD;

    /* TX 方向学到游戏本地端口，供 RX 过滤使用 */
    if (dir == 0)
        g_capture.learned_local_port = ntohs(uh->source);

    spin_lock_bh(&g_capture.lock);
    slot = &g_capture.pkt[g_capture.head];
    slot->ts_ns = ktime_get_ns();
    slot->direction = dir;
    slot->len = (uint32_t)len;
    if (skb_copy_bits(skb, hdr_len + (int)sizeof(struct udphdr),
                      slot->payload, len)) {
        spin_unlock_bh(&g_capture.lock);
        return;
    }
    g_capture.head = (g_capture.head + 1) % QW_CAPTURE_RING;
    if (g_capture.count < QW_CAPTURE_RING)
        g_capture.count++;
    atomic64_inc(&g_capture.total_seen);
    spin_unlock_bh(&g_capture.lock);
}

/* TX：本机发出。netfilter LOCAL_OUT 运行在发送进程上下文，current 即游戏进程 */
static unsigned int qw_capture_hook_local_out(void *priv, struct sk_buff *skb,
                                              const struct nf_hook_state *state)
{
    int pid;

    if (!atomic_read(&g_capture.enabled))
        return NF_ACCEPT;
    pid = atomic_read(&g_capture.target_pid);
    if (pid <= 0)
        return NF_ACCEPT;

    if (skb->protocol == htons(ETH_P_IP)) {
        struct iphdr *iph = ip_hdr(skb);
        if (!iph || iph->protocol != IPPROTO_UDP)
            return NF_ACCEPT;
        atomic64_inc(&g_capture.stat_udp_v4);
        if (current->tgid == pid)
            atomic64_inc(&g_capture.stat_pid_hit);
        else
            return NF_ACCEPT;
        qw_capture_store(skb, 0, 0);
    } else if (skb->protocol == htons(ETH_P_IPV6)) {
        struct ipv6hdr *ip6 = ipv6_hdr(skb);
        if (!ip6 || ip6->nexthdr != IPPROTO_UDP)
            return NF_ACCEPT;
        atomic64_inc(&g_capture.stat_udp_v6);
        if (current->tgid == pid)
            atomic64_inc(&g_capture.stat_pid_hit);
        else
            return NF_ACCEPT;
        qw_capture_store(skb, 0, 1);
    }
    return NF_ACCEPT;
}

/* RX：本机接收。软中断上下文 current 不可靠，用 TX 学到的本地端口过滤 */
static unsigned int qw_capture_hook_local_in(void *priv, struct sk_buff *skb,
                                             const struct nf_hook_state *state)
{
    struct udphdr *uh;
    uint16_t dport;

    if (!atomic_read(&g_capture.enabled))
        return NF_ACCEPT;
    if (!g_capture.learned_local_port)
        return NF_ACCEPT;

    if (skb->protocol == htons(ETH_P_IP)) {
        struct iphdr *iph = ip_hdr(skb);
        if (!iph || iph->protocol != IPPROTO_UDP)
            return NF_ACCEPT;
        atomic64_inc(&g_capture.stat_udp_v4);
        uh = (struct udphdr *)(skb_network_header(skb) + ip_hdrlen(skb));
        dport = ntohs(uh->dest);
        if (dport != g_capture.learned_local_port)
            return NF_ACCEPT;
        qw_capture_store(skb, 1, 0);
    } else if (skb->protocol == htons(ETH_P_IPV6)) {
        struct ipv6hdr *ip6 = ipv6_hdr(skb);
        if (!ip6 || ip6->nexthdr != IPPROTO_UDP)
            return NF_ACCEPT;
        atomic64_inc(&g_capture.stat_udp_v6);
        uh = (struct udphdr *)(skb_network_header(skb) + sizeof(struct ipv6hdr));
        dport = ntohs(uh->dest);
        if (dport != g_capture.learned_local_port)
            return NF_ACCEPT;
        qw_capture_store(skb, 1, 1);
    }
    return NF_ACCEPT;
}
static struct nf_hook_ops g_capture_hook_out = {
    .hook = qw_capture_hook_local_out,
    .pf = NFPROTO_INET,
    .hooknum = NF_INET_LOCAL_OUT,
    .priority = NF_IP_PRI_FIRST,
};

static struct nf_hook_ops g_capture_hook_in = {
    .hook = qw_capture_hook_local_in,
    .pf = NFPROTO_INET,
    .hooknum = NF_INET_LOCAL_IN,
    .priority = NF_IP_PRI_FIRST,
};

/* ---------- ioctl 处理 ---------- */
static long device_ioctl(struct file *filp, unsigned int cmd, unsigned long arg)
{
    u64 start = ktime_get_ns();
    long ret = 0;
    struct qw_file *ctx = filp->private_data;
    bool locked = false;
    bool capture_cmd = cmd == CMD_CAPTURE_START || cmd == CMD_CAPTURE_STOP ||
                       cmd == CMD_CAPTURE_READ || cmd == CMD_CAPTURE_STATS;

    if (!capture_cmd && !ns_capable(&init_user_ns, CAP_SYS_PTRACE)) {
        ret = -EPERM;
        goto out;
    }

    if (!capture_cmd) {
        if (!ctx) { ret = -ENODEV; goto out; }
        if (mutex_lock_interruptible(&ctx->lock)) {
            ret = -ERESTARTSYS;
            goto out;
        }
        locked = true;
    }
    if (cmd == CMD_GET_CAPS) {
        qw_caps_t caps = {
            .abi_version = QW_ABI_VERSION,
            .features = QW_FEATURE_BATCH_LARGE | QW_FEATURE_PER_FILE |
                        QW_FEATURE_BIND_TARGET,
            .max_items = QW_BATCH_MAX_ITEMS, .max_out_size = QW_BATCH_MAX_SIZE,
            .max_item_size = QW_BATCH_MAX_SIZE,
        };
#ifdef CONFIG_HAVE_HW_BREAKPOINT
        if (g_register_user_hw_bp && g_unregister_hw_bp)
            caps.features |= QW_FEATURE_HWBP;
#endif
        /* Capture remains outside this repair and is not advertised as safe. */
        ret = copy_to_user((void __user *)arg, &caps, sizeof(caps)) ? -EFAULT : 0;
        goto out;
    }
    if (cmd == CMD_CLEAR_HOOK) {
#ifdef CONFIG_HAVE_HW_BREAKPOINT
        mutex_lock(&g_hw_bp_lock);
        if (g_hw_owner && g_hw_owner != ctx)
            ret = -EPERM;
        else
            hw_bp_cleanup_locked();
        mutex_unlock(&g_hw_bp_lock);
#else
        ret = -EOPNOTSUPP;
#endif
        goto out;
    }

    if (cmd == CMD_GET_MODULE_INFO) {
        module_info_t info;
        info.cpu_time_ns = atomic64_read(&g_total_cpu_ns);
        info.call_count = atomic64_read(&g_call_count);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 4, 0)
        info.mem_bytes = THIS_MODULE->mem[MOD_TEXT].size +
                         THIS_MODULE->mem[MOD_DATA].size +
                         THIS_MODULE->mem[MOD_RODATA].size +
                         THIS_MODULE->mem[MOD_RO_AFTER_INIT].size;
#else
        info.mem_bytes = THIS_MODULE->core_layout.size;
#endif
        if (copy_to_user((void __user *)arg, &info, sizeof(info)))
            ret = -EFAULT;
        else
            ret = 0;
        goto out;
    }

    if (cmd == CMD_GET_HWBP_STATS) {
        hwbp_stats_t stats;
        memset(&stats, 0, sizeof(stats));
#ifdef CONFIG_HAVE_HW_BREAKPOINT
        unsigned long flags;
        mutex_lock(&g_hw_bp_lock);
        stats.hit_count = atomic64_read(&g_hw_bp_hit_count);
        if (g_hw_bp_event_count > 0) {
            stats.bp_addr = g_hw_bp_addr;
        }
        spin_lock_irqsave(&g_snapshot_lock, flags);
        stats.snapshot_count = g_snapshot_count;
        spin_unlock_irqrestore(&g_snapshot_lock, flags);
        stats.active = g_hw_bp_active ? 1 : 0;
        mutex_unlock(&g_hw_bp_lock);
#else
        stats.active = 0;
#endif
        if (copy_to_user((void __user *)arg, &stats, sizeof(stats)))
            ret = -EFAULT;
        else
            ret = 0;
        goto out;
    }

    /* CMD_FIND_PID: 内核态进程查找，无需 /proc 或 pidof */
    if (cmd == CMD_FIND_PID) {
        mem_packet_t fpkt;
        if (copy_from_user(&fpkt, (void __user *)arg, sizeof(fpkt))) {
            ret = -EFAULT;
            goto out;
        }
        if (qw_validate_packet(&fpkt) || !fpkt.pkg_name[0]) {
            ret = -EINVAL;
            goto out;
        }
        int found_pid = find_pid_by_name(fpkt.pkg_name);
        fpkt.pid = found_pid;
        if (copy_to_user((void __user *)arg, &fpkt, sizeof(fpkt)))
            ret = -EFAULT;
        else
            ret = (found_pid > 0) ? 0 : found_pid;
        goto out;
    }

    /* CMD_READ_TLS_PAC: 从 task_struct 直接读取 TLS 和 PACGA 密钥 */
    if (cmd == CMD_READ_TLS_PAC) {
        mem_packet_t tpkt;
        struct task_struct *task;
        tls_pac_packet_t result;

        if (copy_from_user(&tpkt, (void __user *)arg, sizeof(tpkt))) {
            ret = -EFAULT;
            goto out;
        }

        if (qw_validate_packet(&tpkt)) {
            ret = -EINVAL;
            goto out;
        }
        task = qw_context_target(ctx, tpkt.pid, tpkt.pkg_name, false);
        if (IS_ERR(task)) {
            ret = PTR_ERR(task);
            goto out;
        }

        memset(&result, 0, sizeof(result));

        /* 读取 TLS (TPIDR_EL0) */
        result.tls = QW_GET_TLS(task);

        /* 读取 PAC 密钥 */
#if QW_HAS_PAC
        {
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 7, 0)
            struct ptrauth_keys_user *keys = QW_PAC_FIELD(task);
#else
            struct ptrauth_keys *keys = QW_PAC_FIELD(task);
#endif
            result.apia_lo = keys->apia.lo;
            result.apia_hi = keys->apia.hi;
            result.apib_lo = keys->apib.lo;
            result.apib_hi = keys->apib.hi;
            result.apda_lo = keys->apda.lo;
            result.apda_hi = keys->apda.hi;
            result.apdb_lo = keys->apdb.lo;
            result.apdb_hi = keys->apdb.hi;
            result.apga_lo = keys->apga.lo;
            result.apga_hi = keys->apga.hi;
            result.pac_valid = 1;
        }
#else
        result.pac_valid = 0;
#endif
        put_task_struct(task);

        if (copy_to_user((void __user *)tpkt.user_buf, &result, sizeof(result)))
            ret = -EFAULT;
        else
            ret = 0;
        goto out;
    }

    // 其他命令需要处理...
    mem_packet_t pkt;
    void *kbuf = NULL;
    struct task_struct *task;
    unsigned long final_addr;

    if (cmd == CMD_READ_BATCH) {
        mem_batch_packet_t bpkt;
        mem_batch_item_t *items = NULL;
        void *out_buf = NULL;
        uint32_t i;

        if (copy_from_user(&bpkt, (void __user *)arg, sizeof(bpkt))) {
            ret = -EFAULT;
            goto out;
        }
        if (bpkt.count == 0 || bpkt.count > QW_BATCH_MAX_ITEMS ||
            bpkt.item_size != sizeof(mem_batch_item_t) ||
            bpkt.out_size == 0 || bpkt.out_size > QW_BATCH_MAX_SIZE) {
            ret = -EINVAL;
            goto out;
        }

        if (!memchr(bpkt.pkg_name, '\0', sizeof(bpkt.pkg_name))) {
            ret = -EINVAL;
            goto out;
        }
        task = qw_context_target(ctx, bpkt.pid, bpkt.pkg_name, false);
        if (IS_ERR(task)) {
            ret = PTR_ERR(task);
            goto out;
        }

        items = kcalloc(bpkt.count, sizeof(mem_batch_item_t), GFP_KERNEL);
        out_buf = vzalloc(bpkt.out_size);
        if (!items || !out_buf) {
            ret = -ENOMEM;
            goto batch_out;
        }
        if (copy_from_user(items, (void __user *)bpkt.items_buf,
                           bpkt.count * sizeof(mem_batch_item_t))) {
            ret = -EFAULT;
            goto batch_out;
        }

        for (i = 0; i < bpkt.count; i++) {
            size_t offset = items[i].out_offset, size = items[i].size;
            int r;

            items[i].status = -EINVAL;
            if (!size || size > QW_BATCH_MAX_SIZE || offset > bpkt.out_size ||
                size > bpkt.out_size - offset)
                continue;
            r = manual_read_memory(task, items[i].addr,
                                    (char *)out_buf + offset, size);
            if (r < 0)
                memset((char *)out_buf + offset, 0, size);
            items[i].status = r < 0 ? r : 0;
        }

        if (copy_to_user((void __user *)bpkt.items_buf, items,
                         bpkt.count * sizeof(mem_batch_item_t)) ||
            copy_to_user((void __user *)bpkt.out_buf, out_buf, bpkt.out_size)) {
            ret = -EFAULT;
        } else {
            ret = 0;
        }

batch_out:
        if (task)
            put_task_struct(task);
        kfree(items);
        vfree(out_buf);
        goto out;
    }

    if (copy_from_user(&pkt, (void __user *)arg, sizeof(pkt))) {
        ret = -EFAULT;
        goto out;
    }

    if (!capture_cmd && qw_validate_packet(&pkt)) {
        ret = -EINVAL;
        goto out;
    }
    if (cmd == CMD_BIND_TARGET) {
        if (pkt.pid <= 0 && !pkt.pkg_name[0]) { ret = -EINVAL; goto out; }
        task = get_target_task(pkt.pid, pkt.pkg_name);
        if (IS_ERR(task)) { ret = PTR_ERR(task); goto out; }
        if (pkt.pid <= 0)
            pkt.pid = task_pid_vnr(task);
        if (pkt.pid <= 0 || !pid_alive(task)) {
            put_task_struct(task);
            ret = -ESRCH;
            goto out;
        }
        if (copy_to_user((void __user *)arg, &pkt, sizeof(pkt))) {
            put_task_struct(task);
            ret = -EFAULT;
            goto out;
        }
#ifdef CONFIG_HAVE_HW_BREAKPOINT
        mutex_lock(&g_hw_bp_lock);
        if (g_hw_owner == ctx && g_hw_bp_target_task &&
            g_hw_bp_target_task->group_leader != task->group_leader)
            hw_bp_cleanup_locked();
        mutex_unlock(&g_hw_bp_lock);
#endif
        if (ctx->task)
            put_task_struct(ctx->task);
        ctx->task = task; /* transfer the lookup reference after successful reply */
        ctx->pid = pkt.pid;
        strscpy(ctx->name, pkt.pkg_name, sizeof(ctx->name));
        goto out;
    }
    task = qw_context_target(capture_cmd ? NULL : ctx, pkt.pid, pkt.pkg_name,
                             cmd == CMD_GET_BASE && (pkt.pid > 0 || pkt.pkg_name[0]));
    if (IS_ERR(task)) {
        ret = PTR_ERR(task);
        goto out;
    }

    switch (cmd) {
    case CMD_GET_BASE: {
        char *mod_name = (char *)pkt.user_buf;
        char namebuf[256] = {0};
        if (!mod_name) { ret = -EINVAL; break; }
        long name_len = strncpy_from_user(namebuf, (void __user *)mod_name,
                                           sizeof(namebuf));
        if (name_len <= 0 || name_len >= sizeof(namebuf)) {
            pr_info("CMD_GET_BASE: copy_from_user failed for mod_name\n");
            ret = name_len < 0 ? name_len : -EINVAL; break;
        }
        pr_info("CMD_GET_BASE: pid=%d pkg=%s so=%s\n",
                pkt.pid, pkt.pkg_name, namebuf);
        unsigned long base = get_module_base(task, namebuf);
        if (base == 0) {
            /* 模块未找到: 返回错误而非 0，让用户态能区分"基址为0"和"未找到" */
            ret = -ENOENT;
        } else {
            if (copy_to_user((void __user *)pkt.user_buf, &base, sizeof(unsigned long)))
                ret = -EFAULT;
            else
                qw_remember_target(ctx, task, pkt.pid, pkt.pkg_name);
        }
        break;
    }

    case CMD_ENUM_MODULES: {
        /* 枚举目标进程已加载的 .so 模块列表
         * user_buf 指向用户态缓冲区，size 指定缓冲区大小
         * 返回格式: "module_name@0xbase\n" 逐行排列
         * pkt.pid (输出) = 模块数量
         *
         * 注意: 不能直接写用户空间指针（ARM64 PAN 会触发 data abort），
         * 必须先写入内核缓冲区，再 copy_to_user */
        char __user *user_buf = (char __user *)pkt.user_buf;
        size_t buf_size = pkt.size;
        if (!user_buf || buf_size < 64) { ret = -EINVAL; break; }
        if (buf_size > 65536) { ret = -EINVAL; break; }

        char *kbuf = kzalloc(buf_size, GFP_KERNEL);
        if (!kbuf) { ret = -ENOMEM; break; }

        int mod_count = enum_modules(task, kbuf, buf_size);
        if (mod_count < 0) {
            kfree(kbuf);
            ret = mod_count;
            break;
        }
        pkt.pid = mod_count;

        if (copy_to_user(user_buf, kbuf, buf_size)) {
            ret = -EFAULT;
        } else if (copy_to_user((void __user *)arg, &pkt, sizeof(pkt))) {
            ret = -EFAULT;
        } else {
            ret = 0;
        }
        kfree(kbuf);
        break;
    }

    case CMD_READ_MEM:
    case CMD_READ_PTR: {
        size_t total = pkt.size;
        if (total == 0 || total > 0x1000000) { ret = -EINVAL; break; }
        ret = qw_resolve_address(task, &pkt, cmd == CMD_READ_PTR, &final_addr);
        if (ret)
            break;
        kbuf = kvmalloc(total, GFP_KERNEL);
        if (!kbuf) { ret = -ENOMEM; break; }

        ret = manual_read_memory(task, final_addr, kbuf, total);
        if (ret > 0 && copy_to_user((void __user *)pkt.user_buf, kbuf, total))
            ret = -EFAULT;
        else if (ret > 0)
            ret = 0;
        break;
    }

    case CMD_WRITE_MEM: {
        size_t total = pkt.size;
        if (total == 0 || total > 0x1000000) { ret = -EINVAL; break; }
        ret = qw_resolve_address(task, &pkt, false, &final_addr);
        if (ret)
            break;
        kbuf = kvmalloc(total, GFP_KERNEL);
        if (!kbuf) { ret = -ENOMEM; break; }
        if (copy_from_user(kbuf, (void __user *)pkt.user_buf, total)) {
            ret = -EFAULT; break;
        }
        ret = manual_write_memory(task, final_addr, kbuf, total);
        if (ret > 0) ret = 0;
        break;
    }

    /* ====== HWBP 命令 ====== */
    case CMD_SET_HW_BP: {
        unsigned long bp_addr = pkt.addr;
        unsigned long blr_addrs[2] = {0, 0};

        if (pkt.user_buf && copy_from_user(blr_addrs,
                (void __user *)pkt.user_buf, sizeof(blr_addrs))) {
            ret = -EFAULT;
            break;
        }
        /* offset_count 作为 mode：0=坐标hook 2=解码器输出hook */
        ret = hw_bp_setup(task, bp_addr, blr_addrs[0], blr_addrs[1],
                          pkt.offset_count, NULL, ctx);
        break;
    }

    case CMD_SET_BLR_ADDRS: {
        unsigned long blr_addrs[2] = {0, 0};

        if (pkt.user_buf && copy_from_user(blr_addrs,
                (void __user *)pkt.user_buf, sizeof(blr_addrs))) {
            ret = -EFAULT;
            break;
        }
        ret = hw_bp_update_blr(task, blr_addrs[0], blr_addrs[1], ctx);
        break;
    }

    case CMD_QUERY_SNAPSHOT: {
        unsigned long obj_addr = pkt.addr;
        u32 result[3] = {0, 0, 0};
        int status;

#ifdef CONFIG_HAVE_HW_BREAKPOINT
        mutex_lock(&g_hw_bp_lock);
        if (g_hw_owner && g_hw_owner != ctx)
            status = -EPERM;
        else
            status = hw_bp_query_snapshot(obj_addr, &result[0], &result[1], &result[2]);
        mutex_unlock(&g_hw_bp_lock);
#else
        status = -EOPNOTSUPP;
#endif

        if (pkt.user_buf && copy_to_user((void __user *)pkt.user_buf,
                result, sizeof(result))) {
            ret = -14; /* 用户缓冲区写入失败 */
        } else {
            ret = status;
        }
        break;
    }

    case CMD_CAPTURE_START: {
        spin_lock_bh(&g_capture.lock);
        g_capture.head = 0;
        g_capture.count = 0;
        g_capture.learned_local_port = 0;
        spin_unlock_bh(&g_capture.lock);
        atomic_set(&g_capture.target_pid, pkt.pid);
        atomic64_set(&g_capture.total_seen, 0);
        atomic_set(&g_capture.enabled, 1);
        ret = 0;
        break;
    }

    case CMD_CAPTURE_STOP: {
        atomic_set(&g_capture.enabled, 0);
        ret = 0;
        break;
    }

    case CMD_CAPTURE_READ: {
        qw_capture_read_t rpkt;
        qw_capture_pkt_t *tmp;
        uint32_t n, avail, start, got = 0, i;

        if (copy_from_user(&rpkt, (void __user *)arg, sizeof(rpkt))) {
            ret = -EFAULT;
            break;
        }
        n = rpkt.max_pkts;
        if (n > QW_CAPTURE_RING)
            n = QW_CAPTURE_RING;
        if (n == 0) {
            rpkt.count_out = 0;
            if (copy_to_user((void __user *)arg, &rpkt, sizeof(rpkt)))
                ret = -EFAULT;
            else
                ret = 0;
            break;
        }
        tmp = vmalloc((size_t)n * sizeof(qw_capture_pkt_t));
        if (!tmp) {
            ret = -ENOMEM;
            break;
        }
        spin_lock_bh(&g_capture.lock);
        avail = g_capture.count;
        if (n > avail)
            n = avail;
        start = (g_capture.head + QW_CAPTURE_RING - g_capture.count) % QW_CAPTURE_RING;
        for (i = 0; i < n; i++)
            tmp[i] = g_capture.pkt[(start + i) % QW_CAPTURE_RING];
        g_capture.count -= n;
        spin_unlock_bh(&g_capture.lock);

        for (i = 0; i < n; i++) {
            if (copy_to_user((void __user *)(rpkt.out_buf +
                             (unsigned long)i * sizeof(qw_capture_pkt_t)),
                             &tmp[i], sizeof(qw_capture_pkt_t))) {
                got = i;
                break;
            }
            got++;
        }
        vfree(tmp);
        rpkt.count_out = got;
        if (copy_to_user((void __user *)arg, &rpkt, sizeof(rpkt)))
            ret = -EFAULT;
        else
            ret = 0;
        break;
    }

    case CMD_CAPTURE_STATS: {
        qw_capture_stats_t st;
        st.enabled = atomic_read(&g_capture.enabled);
        st.target_pid = atomic_read(&g_capture.target_pid);
        st.learned_local_port = g_capture.learned_local_port;
        st.ring_count = g_capture.count;
        st.total_seen = atomic64_read(&g_capture.total_seen);
        st.stat_udp_v4 = atomic64_read(&g_capture.stat_udp_v4);
        st.stat_udp_v6 = atomic64_read(&g_capture.stat_udp_v6);
        st.stat_pid_hit = atomic64_read(&g_capture.stat_pid_hit);
        if (copy_to_user((void __user *)arg, &st, sizeof(st)))
            ret = -EFAULT;
        else
            ret = 0;
        break;
    }

    case CMD_READ_DEC_SNAP: {
#ifdef CONFIG_HAVE_HW_BREAKPOINT
        decoder_snap_t *tmp;
        unsigned int want, avail, i;
        unsigned long flags;

        if (!pkt.user_buf || !pkt.size || pkt.size > DEC_SNAP_CACHE_SIZE) {
            ret = -EINVAL;
            break;
        }
        mutex_lock(&g_hw_bp_lock);
        ret = g_hw_owner && g_hw_owner != ctx ? -EPERM : 0;
        mutex_unlock(&g_hw_bp_lock);
        if (ret)
            break;
        want = pkt.size;
        tmp = kvcalloc(want, sizeof(*tmp), GFP_KERNEL);
        if (!tmp) { ret = -ENOMEM; break; }
        spin_lock_irqsave(&g_dec_lock, flags);
        avail = min_t(unsigned int, g_dec_count, want);
        for (i = 0; i < avail; i++) {
            int idx = (g_dec_head - 1 - i + DEC_SNAP_CACHE_SIZE) %
                      DEC_SNAP_CACHE_SIZE;
            tmp[i] = g_dec_snaps[idx];
        }
        spin_unlock_irqrestore(&g_dec_lock, flags);
        if (copy_to_user((void __user *)pkt.user_buf, tmp,
                         avail * sizeof(*tmp))) {
            ret = -EFAULT;
        } else {
            pkt.offset_count = avail;
            ret = copy_to_user((void __user *)arg, &pkt, sizeof(pkt)) ?
                  -EFAULT : 0;
        }
        kvfree(tmp);
#else
        ret = -EOPNOTSUPP;
#endif
        break;
    }

    case CMD_SET_HOOK_CFG: {
        hw_bp_cfg_t cfg;

        if (copy_from_user(&cfg, (void __user *)pkt.user_buf, sizeof(cfg))) {
            ret = -EFAULT;
            break;
        }
        /* Reuse the referenced outer task; never borrow a task after RCU. */
        ret = hw_bp_setup(task, cfg.bp_addr, 0, 0, cfg.mode, &cfg, ctx);
        cfg.ret = ret;
        if (copy_to_user((void __user *)pkt.user_buf, &cfg, sizeof(cfg))) {
#ifdef CONFIG_HAVE_HW_BREAKPOINT
            if (!ret) {
                mutex_lock(&g_hw_bp_lock);
                if (g_hw_owner == ctx)
                    hw_bp_cleanup_locked();
                mutex_unlock(&g_hw_bp_lock);
            }
#endif
            ret = -EFAULT;
        }
        break;
    }



    default:
        ret = -ENOTTY;
    }

    if (task)
        put_task_struct(task);
    kvfree(kbuf);

out:
    if (locked)
        mutex_unlock(&ctx->lock);
    {
        u64 delta = ktime_get_ns() - start;
        atomic64_add(delta, &g_total_cpu_ns);
        atomic64_inc(&g_call_count);
    }
    return ret;
}

/* Last close of an open file also releases any breakpoint it owns. dup()
 * descriptors intentionally share the file's target and hardware ownership. */
static int device_open(struct inode *inode, struct file *file)
{
    struct qw_file *ctx = kzalloc(sizeof(*ctx), GFP_KERNEL);

    if (!ctx)
        return -ENOMEM;
    mutex_init(&ctx->lock);
    file->private_data = ctx;
    return 0;
}

static int device_release(struct inode *inode, struct file *file)
{
    struct qw_file *ctx = file->private_data;

#ifdef CONFIG_HAVE_HW_BREAKPOINT
    mutex_lock(&g_hw_bp_lock);
    if (g_hw_owner == ctx)
        hw_bp_cleanup_locked();
    mutex_unlock(&g_hw_bp_lock);
#endif
    if (ctx->task)
        put_task_struct(ctx->task);
    kfree(ctx);
    return 0;
}

/* ---------- 设备操作 ---------- */
static struct file_operations fops = {
    .owner = THIS_MODULE,
    .open = device_open,
    .release = device_release,
    .unlocked_ioctl = device_ioctl,
#ifdef CONFIG_COMPAT
    /* Rebuilt 32-bit clients use the same fixed/aligned 64-bit wire layout. */
    .compat_ioctl = compat_ptr_ioctl,
#endif
};

static struct miscdevice misc_dev = {
    .minor = MISC_DYNAMIC_MINOR,
    .name = DEVICE_NAME,
    .fops = &fops,
};

/* ---------- 模块初始/退出 ---------- */
static int __init mem_reader_init(void)
{
    int ret;

#ifdef CONFIG_HAVE_HW_BREAKPOINT
    /* Android GKI 6.1 不导出 register_user_hw_breakpoint / unregister_hw_breakpoint，
     * 通过 kprobe 临时注册解析符号地址 */
    g_register_user_hw_bp = (register_user_hw_bp_fn)
        resolve_symbol("register_user_hw_breakpoint");
    g_unregister_hw_bp = (unregister_hw_bp_fn)
        resolve_symbol("unregister_hw_breakpoint");

    if (g_register_user_hw_bp && g_unregister_hw_bp) {
        pr_info("HWBP symbols resolved via kprobe\n");
    } else {
        g_register_user_hw_bp = NULL;
        g_unregister_hw_bp = NULL;
        pr_warn("HWBP symbols not found via kprobe, HWBP disabled\n");
    }
#endif

    /* Publish the device only after control-path dependencies are ready. */
    ret = misc_register(&misc_dev);
    if (ret)
        return ret;

    /* netfilter 抓包 hook 注册 */
    if (nf_register_net_hook(&init_net, &g_capture_hook_out) < 0)
        pr_warn("capture hook (out) register failed\n");
    else
        pr_info("capture hook (out) registered\n");
    if (nf_register_net_hook(&init_net, &g_capture_hook_in) < 0)
        pr_warn("capture hook (in) register failed\n");
    else
        pr_info("capture hook (in) registered\n");

    pr_info("device /dev/%s ready"
#ifdef CONFIG_HAVE_HW_BREAKPOINT
            " [HWBP supported]"
#endif
            "\n", DEVICE_NAME);
    return 0;
}

static void __exit mem_reader_exit(void)
{
    hw_bp_cleanup();
    nf_unregister_net_hook(&init_net, &g_capture_hook_out);
    nf_unregister_net_hook(&init_net, &g_capture_hook_in);
    misc_deregister(&misc_dev);
    pr_info("module unloaded\n");
}

module_init(mem_reader_init);
module_exit(mem_reader_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("Security Researcher");
MODULE_DESCRIPTION("Optimized ARM64 memory R/W with HWBP snapshot support");
