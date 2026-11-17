// SPDX-License-Identifier: GPL-2.0
/*
 * qingwei_mcp.c — 优化性能版内核驱动
 *
 * 核心优化：
 *   1. 内核态包名→PID解析：通过遍历 /proc/<pid>/cmdline 直接在内核空间
 *      完成包名到 PID 的映射，避免用户态枚举全部进程的低效方式
 *   2. 高性能 PID 缓存：带哈希表的 LRU 缓存，加速重复查找
 *   3. 进程运行状态验证：检查目标进程是否存活、mm 是否有效
 *   4. 使用 access_process_vm 完成远程进程内存访问和 COW 处理
 *   5. 批量读取优化：单次持锁完成多个内存区域读取
 *   6. 性能统计：记录每次 ioctl 的 CPU 耗时与调用次数
 *
 * 兼容 App 端：
 *   - 设备节点: /dev/qingwei_mcp
 *   - ioctl 命令号与结构体大小完全匹配 IoctlCommands.kt
 *   - 所有需要 PID 的命令均支持传入包名自动解析
 *
 * 配套文件: Kbuild, Makefile
 */

#define pr_fmt(fmt) "qingwei_mcp: " fmt

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/miscdevice.h>
#include <linux/uaccess.h>
#include <linux/capability.h>
#include <linux/pid.h>
#include <linux/sched.h>
#include <linux/sched/mm.h>
#include <linux/sched/signal.h>
#include <linux/mm.h>
#include <linux/highmem.h>
#include <linux/ioctl.h>
#include <linux/version.h>
#include <linux/slab.h>
#include <linux/math64.h>
#include <linux/list.h>
#include <linux/mutex.h>
#include <linux/spinlock.h>
#include <linux/vmalloc.h>
#include <linux/ktime.h>
#include <linux/kprobes.h>
#include <linux/dcache.h>
#include <linux/path.h>
#include <linux/nsproxy.h>
#include <linux/pid_namespace.h>

#if defined(CONFIG_ARM64) && defined(CONFIG_HAVE_HW_BREAKPOINT)
#define QINGWEI_HAVE_HW_BREAKPOINT
#include <linux/perf_event.h>
#include <linux/hw_breakpoint.h>
#include <asm/ptrace.h>
#endif

/* =====================================================================
 * 配置常量
 * ===================================================================== */

#define DEVICE_NAME       "qingwei_mcp"

/* PID 缓存配置 */
#define PID_CACHE_BITS       6
#define PID_CACHE_SIZE       (1 << PID_CACHE_BITS)
#define PID_CACHE_MASK       (PID_CACHE_SIZE - 1)
#define PKG_NAME_MAX_LEN     256
#define CMDLINE_BUF_SIZE     512

/* 批量操作限制 */
#define MAX_BATCH_ITEMS      512
#define MAX_BATCH_BUF_SIZE   (2 * 1024 * 1024)

/* 内存读取限制 */
#define MAX_SINGLE_READ      (16 * 1024 * 1024)
#define MAX_PROC_ENTRIES     512
#define MAX_MODULE_ENTRIES   256
#define MAX_VMA_ENTRIES      1024
#define MAX_THREAD_ENTRIES   256
#define MAX_BP_ENTRIES       64
#define MAX_SEARCH_RESULTS   256

/* ARM64 寄存器数量 */
#define ARM64_REG_COUNT      34

/* 硬件断点快照缓存 */
#define SNAPSHOT_CACHE_SIZE  256

/* =====================================================================
 * 数据结构定义 — 与 App 端 Kotlin 代码严格对应
 * ===================================================================== */

/* --- CMD_LIST_PROCS (32 bytes) --- */
struct kai_proc_list_packet {
    __s32  max_count;
    __s32  actual_count;
    __s32  total_count;
    __s32  offset;
    __u64  out_buf;
    __u64  out_buf_size;
} __packed;

/* 进程条目 (296 bytes) */
struct kai_proc_entry {
    __s32  pid;
    __s32  ppid;
    __s32  tgid;
    char   comm[16];
    char   cmdline[256];
    __u64  state;
    __s32  thread_count;
} __packed;

/* --- CMD_LIST_MODULES (48 bytes) --- */
struct kai_module_list_packet {
    __s32  pid;
    __s32  max_count;
    __s32  actual_count;
    __s32  pad;
    __u64  out_buf;
    __u64  out_buf_size;
    __u64  reserved[2];
} __packed;

/* 模块条目 (288 bytes) */
struct kai_module_entry {
    __u64  base_addr;
    __u64  end_addr;
    __u64  size;
    __u32  flags;
    __u64  offset;
    char   path[248];
    __u32  is_exec;
} __packed;

/* --- CMD_LIST_VMAS (48 bytes) --- */
struct kai_vma_list_packet {
    __s32  pid;
    __s32  max_count;
    __s32  actual_count;
    __s32  pad;
    __u64  out_buf;
    __u64  out_buf_size;
    __u64  reserved[2];
} __packed;

/* VMA 条目 (48 bytes) */
struct kai_vma_entry {
    __u64  start;
    __u64  end;
    __u32  flags;
    __u64  pgoff;
    char   name[16];
    __u32  pad;
} __packed;

/* --- CMD_LIST_THREADS (48 bytes) --- */
struct kai_thread_list_packet {
    __s32  pid;
    __s32  max_count;
    __s32  actual_count;
    __s32  pad;
    __u64  out_buf;
    __u64  out_buf_size;
    __u64  reserved[2];
} __packed;

/* 线程条目 (44 bytes) */
struct kai_thread_entry {
    __s32  tid;
    char   comm[16];
    __u64  state;
    __u64  stack_ptr;
    __u64  pc;
} __packed;

/* --- CMD_RAW_READ (40 bytes) --- */
struct kai_raw_read_packet {
    __s32  pid;
    __s32  pad;
    __u64  address;
    __u32  size;
    __u32  actual_size;
    __u64  data_ptr;
    __u64  data_size;
} __packed;

/* --- CMD_MEM_SEARCH (568 bytes) --- */
struct kai_mem_search_packet {
    __s32  pid;
    __u32  pattern_size;
    __s32  actual_results;
    __s32  total_found;
    __u64  start_addr;
    __u64  end_addr;
    __u32  max_results;
    __u32  pad;
    char   pattern[512];
    __u64  results_ptr;
    __u64  results_size;
} __packed;

/* --- CMD_HWBP_SET (92 bytes) --- */
struct kai_hwbp_setup {
    __s32  pid;
    __u32  bp_type;
    __u32  bp_len;
    __u32  pad;
    __u64  address;
    __u32  bp_id;
    char   reserved[64];
} __packed;

/* --- CMD_HWBP_REMOVE / CMD_SWBP_REMOVE (72 bytes) --- */
struct kai_hwbp_remove {
    __u32  bp_id;
    __s32  pid;
    char   reserved[64];
} __packed;

/* --- CMD_HWBP_LIST (48 bytes) --- */
struct kai_hwbp_list_packet {
    __s32  pid;
    __s32  total_bps;
    __s32  actual_bps;
    __s32  pad;
    __u64  out_buf;
    __u64  out_buf_size;
    __u64  reserved[2];
} __packed;

/* 断点条目 (32 bytes) */
struct kai_bp_entry {
    __u32  index;
    __u64  addr;
    __u32  type;
    __u32  len;
    __u64  hit_count;
    __u32  active;
} __packed;

/* --- CMD_SWBP_SET (80 bytes) --- */
struct kai_swbp_setup {
    __s32  pid;
    __u32  pad;
    __u64  address;
    __u32  bp_id;
    char   reserved[60];
} __packed;

/* --- CMD_READ_REGS (280 bytes) --- */
struct kai_reg_read_packet {
    __u32  bp_id;
    __u32  valid;
    __u64  regs[ARM64_REG_COUNT]; /* X0-X30, SP, PC, PSTATE */
} __packed;

/* --- CMD_CALLSTACK (56 bytes) --- */
struct kai_callstack_packet {
    __s32  pid;
    __s32  bp_id;
    __s32  actual_frames;
    __s32  total_frames;
    __u64  out_buf;
    __u64  out_buf_size;
    __s32  max_frames;
    __s32  pad;
    __u64  reserved[2];
} __packed;

/* 栈帧条目 (48 bytes) */
struct kai_stack_frame {
    __u64  pc;
    __u64  sp;
    __u64  fp;
    __u64  lr;
    char   symbol[16];
} __packed;

/* --- CMD_RESOLVE_PKG (264 bytes) — 包名→PID解析 --- */
struct kai_resolve_pkg {
    char   pkg_name[PKG_NAME_MAX_LEN]; /* 输入：包名 */
    __s32  pid;                        /* 输出：PID */
    __u32  running;                    /* 输出：1=运行中, 0=未运行 */
} __packed;

/* =====================================================================
 * ioctl 命令定义 — 与 IoctlCommands.kt 严格对应
 * ===================================================================== */

#define IOCTL_MAGIC  'M'

/* 辅助宏：生成 _IOWR/_IOW/_IOR 值 */
#define KAI_IOWR(nr, size)  _IOWR(IOCTL_MAGIC, nr, char[size])
#define KAI_IOW(nr, size)   _IOW(IOCTL_MAGIC, nr, char[size])
#define KAI_IOR(nr, size)   _IOR(IOCTL_MAGIC, nr, char[size])

/* 原有命令（向后兼容，保留旧接口） */
#define CMD_GET_BASE_LEGACY     KAI_IOWR(1,  104)
#define CMD_READ_MEM_LEGACY     KAI_IOWR(2,  104)
#define CMD_WRITE_MEM           KAI_IOWR(3,  104)
#define CMD_READ_PTR_LEGACY     KAI_IOWR(4,  104)
#define CMD_READ_BATCH_LEGACY   KAI_IOWR(5,  56)
#define CMD_GET_MODULE_INFO     KAI_IOR(6,   56)
#define CMD_SET_HW_BP_LEGACY    KAI_IOW(7,   104)
#define CMD_SET_BLR_ADDRS_LEGACY KAI_IOW(8,  104)
#define CMD_QUERY_SNAPSHOT_LEGACY KAI_IOWR(9, 104)
#define CMD_GET_HWBP_STATS      KAI_IOR(10,  24)

/* 新增命令 — 完全匹配 App 端 IoctlCommands.kt */
#define CMD_LIST_PROCS          KAI_IOWR(11, 32)
#define CMD_LIST_MODULES        KAI_IOWR(12, 48)
#define CMD_LIST_VMAS           KAI_IOWR(13, 48)
#define CMD_READ_REGS           KAI_IOWR(14, 280)
#define CMD_HWBP_SET            KAI_IOW(15,  92)
#define CMD_HWBP_REMOVE         KAI_IOW(16,  72)
#define CMD_HWBP_LIST           KAI_IOWR(17, 48)
#define CMD_SWBP_SET            KAI_IOWR(18, 80)
#define CMD_SWBP_REMOVE         KAI_IOW(19,  72)
#define CMD_MEM_SEARCH          KAI_IOWR(20, 568)
#define CMD_CALLSTACK           KAI_IOWR(21, 56)
#define CMD_LIST_THREADS        KAI_IOWR(22, 48)
#define CMD_RAW_READ            KAI_IOWR(23, 40)

/* ★ 新增命令：包名→PID解析（核心优化） */
#define CMD_RESOLVE_PKG         KAI_IOWR(24, sizeof(struct kai_resolve_pkg))

/* =====================================================================
 * 性能统计结构
 * ===================================================================== */

struct module_perf_info {
    __u64  cpu_time_ns;
    __u64  call_count;
    __u64  pkg_resolve_count;
    __u64  pkg_resolve_hit;
    __u64  mem_read_count;
    __u64  mem_write_count;
    __u64  mem_read_bytes;
};

struct hwbp_stats {
    __u64  hit_count;
    __u64  bp_addr;
    __s32  snapshot_count;
    __s32  active;
};

/* Keep the packed ABI sizes explicit; Kotlin mirrors these values. */
static_assert(sizeof(struct kai_proc_list_packet) == 32);
static_assert(sizeof(struct kai_proc_entry) == 296);
static_assert(sizeof(struct kai_module_list_packet) == 48);
static_assert(sizeof(struct kai_module_entry) == 288);
static_assert(sizeof(struct kai_vma_list_packet) == 48);
static_assert(sizeof(struct kai_vma_entry) == 48);
static_assert(sizeof(struct kai_thread_list_packet) == 48);
static_assert(sizeof(struct kai_thread_entry) == 44);
static_assert(sizeof(struct kai_raw_read_packet) == 40);
static_assert(sizeof(struct kai_mem_search_packet) == 568);
static_assert(sizeof(struct kai_hwbp_setup) == 92);
static_assert(sizeof(struct kai_reg_read_packet) == 280);
static_assert(sizeof(struct kai_resolve_pkg) == 264);
static_assert(sizeof(struct kai_hwbp_remove) == 72);
static_assert(sizeof(struct kai_bp_entry) == 32);
static_assert(sizeof(struct kai_swbp_setup) == 80);
static_assert(sizeof(struct kai_callstack_packet) == 56);
static_assert(sizeof(struct kai_stack_frame) == 48);
static_assert(sizeof(struct module_perf_info) == 56);
static_assert(sizeof(struct hwbp_stats) == 24);

/* =====================================================================
 * PID 缓存 — 哈希表 + LRU
 * ===================================================================== */

struct pid_cache_entry {
    struct hlist_node   hash_node;
    struct list_head    lru_node;
    char                pkg_name[PKG_NAME_MAX_LEN];
    struct task_struct  *task;
    u64                 exec_id;
};

/* =====================================================================
 * 全局变量
 * ===================================================================== */

/* 进程查找缓存 */
static DEFINE_MUTEX(g_cache_lock);
static struct hlist_head g_pid_cache_hash[PID_CACHE_SIZE];
static LIST_HEAD(g_pid_cache_lru);
static struct pid_cache_entry g_pid_cache_pool[PID_CACHE_SIZE];
static int g_pid_cache_init_done;

/* 性能统计 */
static atomic64_t g_total_cpu_ns    = ATOMIC64_INIT(0);
static atomic64_t g_call_count      = ATOMIC64_INIT(0);
static atomic64_t g_pkg_resolve_cnt = ATOMIC64_INIT(0);
static atomic64_t g_pkg_resolve_hit = ATOMIC64_INIT(0);
static atomic64_t g_mem_read_cnt    = ATOMIC64_INIT(0);
static atomic64_t g_mem_read_bytes  = ATOMIC64_INIT(0);
static atomic64_t g_mem_write_cnt   = ATOMIC64_INIT(0);

#ifdef QINGWEI_HAVE_HW_BREAKPOINT
/* HW Breakpoint 动态符号解析 */
typedef struct perf_event *(*register_user_hw_bp_fn)(
    struct perf_event_attr *attr,
    perf_overflow_handler_t triggered,
    void *context,
    struct task_struct *tsk);
typedef void (*unregister_hw_bp_fn)(struct perf_event *bp);

static register_user_hw_bp_fn  g_reg_hw_bp;
static unregister_hw_bp_fn     g_unreg_hw_bp;

/* HW Breakpoint 状态 */
#define MAX_HW_BREAKPOINTS  16
struct hw_bp_slot {
    struct perf_event   *event;
    struct task_struct  *target_task;
    __u64                bp_addr;
    __u32                bp_type;
    __u32                bp_len;
    __u32                bp_id;
    atomic64_t           hit_count;
    bool                 active;
};
static struct hw_bp_slot g_hw_bp_slots[MAX_HW_BREAKPOINTS];
static DEFINE_MUTEX(g_hw_bp_lock);
static __u32 g_next_bp_id = 1;

/* 寄存器快照（断点命中时保存） */
struct reg_snapshot {
    __u32  bp_id;
    __u32  valid;
    __u64  regs[ARM64_REG_COUNT];
};
static struct reg_snapshot g_reg_snapshots[MAX_HW_BREAKPOINTS];
static DEFINE_RAW_SPINLOCK(g_reg_snap_lock);

/* 坐标快照缓存（环形缓冲区） */
struct snapshot_entry {
    unsigned long  obj_addr;
    u32            x_raw, y_raw, z_raw;
    unsigned long  jiffies_val;
};
static struct snapshot_entry g_snapshots[SNAPSHOT_CACHE_SIZE] __maybe_unused;
static int g_snapshot_head __maybe_unused;
static int g_snapshot_count;
static DEFINE_SPINLOCK(g_snapshot_lock);
#endif /* QINGWEI_HAVE_HW_BREAKPOINT */

/* =====================================================================
 * 第一部分：PID 缓存管理
 * ===================================================================== */

/**
 * 初始化 PID 缓存池
 */
static void pid_cache_init(void)
{
    int i;

    if (g_pid_cache_init_done)
        return;

    for (i = 0; i < PID_CACHE_SIZE; i++) {
        INIT_HLIST_NODE(&g_pid_cache_pool[i].hash_node);
        INIT_LIST_HEAD(&g_pid_cache_pool[i].lru_node);
    }
    for (i = 0; i < PID_CACHE_SIZE; i++)
        INIT_HLIST_HEAD(&g_pid_cache_hash[i]);

    g_pid_cache_init_done = 1;
}

/**
 * DJB2 哈希 — 用于包名→缓存桶映射
 */
static unsigned int pkg_name_hash(const char *name)
{
    unsigned int hash = 5381;

    while (*name) {
        hash = ((hash << 5) + hash) + (unsigned char)*name;
        name++;
    }
    return hash & PID_CACHE_MASK;
}

/* Cache entries own one task reference while linked. */
static void pid_cache_remove(struct pid_cache_entry *entry)
{
    hlist_del_init(&entry->hash_node);
    list_del_init(&entry->lru_node);
    put_task_struct(entry->task);
    entry->task = NULL;
    entry->pkg_name[0] = '\0';
}

static bool pid_cache_lookup(const char *pkg_name, int *out_pid)
{
    unsigned int h = pkg_name_hash(pkg_name);
    struct pid_cache_entry *entry;

    hlist_for_each_entry(entry, &g_pid_cache_hash[h], hash_node) {
        struct mm_struct *mm;
        pid_t pid;

        if (strcmp(entry->pkg_name, pkg_name))
            continue;

        pid = task_pid_vnr(entry->task);
        mm = get_task_mm(entry->task);
        if (mm) {
            mmput(mm);
            if (entry->exec_id == READ_ONCE(entry->task->self_exec_id)) {
                if (!pid || pid != task_pid_vnr(entry->task))
                    return false;

                *out_pid = pid;
                list_move(&entry->lru_node, &g_pid_cache_lru);
                atomic64_inc(&g_pkg_resolve_hit);
                return true;
            }
        }
        pid_cache_remove(entry);
        break;
    }
    return false;
}

static void pid_cache_insert(const char *pkg_name, struct task_struct *task,
                             u64 exec_id)
{
    unsigned int h = pkg_name_hash(pkg_name);
    struct pid_cache_entry *entry = NULL, *victim;
    int i;

    hlist_for_each_entry(victim, &g_pid_cache_hash[h], hash_node) {
        if (!strcmp(victim->pkg_name, pkg_name)) {
            pid_cache_remove(victim);
            entry = victim;
            break;
        }
    }

    for (i = 0; !entry && i < PID_CACHE_SIZE; i++) {
        if (!g_pid_cache_pool[i].task)
            entry = &g_pid_cache_pool[i];
    }

    if (!entry && !list_empty(&g_pid_cache_lru)) {
        victim = list_last_entry(&g_pid_cache_lru,
                                 struct pid_cache_entry, lru_node);
        pid_cache_remove(victim);
        entry = victim;
    }
    if (!entry)
        return;

    strscpy(entry->pkg_name, pkg_name, sizeof(entry->pkg_name));
    get_task_struct(task);
    entry->task = task;
    entry->exec_id = exec_id;

    hlist_add_head(&entry->hash_node, &g_pid_cache_hash[h]);
    list_add(&entry->lru_node, &g_pid_cache_lru);
}

/* =====================================================================
 * 第二部分：核心 — 包名→PID解析（内核态通过 /proc 读取）
 * ===================================================================== */

/**
 * 读取进程的 /proc/<pid>/cmdline 内容
 * 返回: 0 = 成功, <0 = 失败
 *
 * cmdline 是进程的完整命令行，对于 Android 应用就是包名
 * 例如: "com.example.app\0" 或 "com.example.app:param1\0"
 */
static int read_proc_cmdline(struct task_struct *task, char *buf, size_t buf_size)
{
    struct mm_struct *mm;
    unsigned long arg_start, arg_end;
    size_t len;
    int ret;

    if (!task || !buf || buf_size < 2)
        return -EINVAL;

    buf[0] = '\0';
    mm = get_task_mm(task);
    if (!mm)
        return -EINVAL;

    spin_lock(&mm->arg_lock);
    arg_start = mm->arg_start;
    arg_end = mm->arg_end;
    spin_unlock(&mm->arg_lock);

    if (!arg_start || arg_end <= arg_start) {
        mmput(mm);
        return -ENOENT;
    }

    len = min_t(size_t, buf_size - 1, arg_end - arg_start);
    /* access_process_vm reads the target task's address space, not current's. */
    ret = access_process_vm(task, arg_start, buf, (int)len, 0);
    mmput(mm);
    if (ret <= 0)
        return ret ? ret : -EFAULT;

    buf[min_t(size_t, ret, buf_size - 1)] = '\0';
    return ret;
}

/**
 * ★ 核心优化函数：通过包名查找 PID ★
 *
 * 工作流程：
 *   1. 先查缓存 → 命中则直接返回
 *   2. 未命中则遍历所有进程
 *   3. 对每个进程：先比较 comm（快速路径），再读 cmdline（精确匹配）
 *   4. 匹配成功 → 插入缓存并返回 PID
 *   5. 检查进程运行状态（mm 是否存在）
 *
 * 参数：
 *   pkg_name: 目标包名（如 "com.example.app"）
 *   out_pid:  输出 PID
 *   out_running: 输出运行状态（1=运行中, 0=未运行）
 *
 * 返回: 0 = 成功找到, -ESRCH = 未找到, -EINVAL = 参数无效
 */
/* Take stable task references under RCU; all potentially sleeping work is
 * performed only after leaving the RCU read-side critical section. */
static struct task_struct **snapshot_processes(size_t *out_count)
{
    struct task_struct *task;
    struct task_struct **tasks;
    size_t capacity = 0, count = 0;

    *out_count = 0;
    rcu_read_lock();
    for_each_process(task)
        capacity++;
    rcu_read_unlock();

    if (!capacity)
        return NULL;

    tasks = kvmalloc_array(capacity, sizeof(*tasks), GFP_KERNEL);
    if (!tasks)
        return ERR_PTR(-ENOMEM);

    rcu_read_lock();
    for_each_process(task) {
        if (count >= capacity)
            break;
        get_task_struct(task);
        tasks[count++] = task;
    }
    rcu_read_unlock();

    *out_count = count;
    return tasks;
}

static bool cmdline_matches_package(const char *cmdline, const char *pkg_name)
{
    size_t len = strlen(pkg_name);

    return !strncmp(cmdline, pkg_name, len) &&
           (cmdline[len] == '\0' || cmdline[len] == ':');
}

static int resolve_pkg_to_pid(const char *pkg_name, int *out_pid,
                               u32 *out_running)
{
    struct task_struct **tasks;
    size_t task_count = 0, i;
    size_t pkg_len;
    int found_pid = 0;
    char cmdline_buf[CMDLINE_BUF_SIZE];

    if (!pkg_name || !pkg_name[0] || !out_pid || !out_running)
        return -EINVAL;
    pkg_len = strnlen(pkg_name, PKG_NAME_MAX_LEN);
    if (!pkg_len || pkg_len >= PKG_NAME_MAX_LEN)
        return -EINVAL;

    atomic64_inc(&g_pkg_resolve_cnt);

    mutex_lock(&g_cache_lock);
    if (pid_cache_lookup(pkg_name, out_pid)) {
        *out_running = 1;
        mutex_unlock(&g_cache_lock);
        return 0;
    }
    mutex_unlock(&g_cache_lock);

    tasks = snapshot_processes(&task_count);
    if (IS_ERR(tasks))
        return PTR_ERR(tasks);
    if (!tasks) {
        *out_pid = 0;
        *out_running = 0;
        return -ESRCH;
    }

    for (i = 0; i < task_count; i++) {
        struct task_struct *task = tasks[i];
        struct mm_struct *mm;
        u64 exec_id = READ_ONCE(task->self_exec_id);
        pid_t pid = task_pid_vnr(task);
        char comm[TASK_COMM_LEN];
        bool matched;

        if (!pid)
            continue;

        get_task_comm(comm, task);
        matched = !strcmp(comm, pkg_name);
        if (!matched && read_proc_cmdline(task, cmdline_buf,
                                          sizeof(cmdline_buf)) > 0)
            matched = cmdline_matches_package(cmdline_buf, pkg_name);
        if (!matched)
            continue;

        mm = get_task_mm(task);
        if (!mm)
            continue;
        mmput(mm);

        mutex_lock(&g_cache_lock);
        if (exec_id == READ_ONCE(task->self_exec_id) && pid == task_pid_vnr(task)) {
            pid_cache_insert(pkg_name, task, exec_id);
            found_pid = pid;
        }
        mutex_unlock(&g_cache_lock);
        if (found_pid)
            break;
    }

    for (i = 0; i < task_count; i++)
        put_task_struct(tasks[i]);
    kvfree(tasks);

    *out_pid = found_pid;
    *out_running = found_pid != 0;
    return found_pid ? 0 : -ESRCH;
}

/**
 * 检查指定包名的进程是否正在运行
 * 返回: 1 = 运行中, 0 = 未运行, <0 = 错误
 */
static int __maybe_unused check_pkg_running(const char *pkg_name)
{
    struct task_struct **tasks;
    size_t task_count = 0, i;
    int running = 0;
    char cmdline_buf[CMDLINE_BUF_SIZE];

    if (!pkg_name || !pkg_name[0] ||
        strnlen(pkg_name, PKG_NAME_MAX_LEN) >= PKG_NAME_MAX_LEN)
        return 0;

    tasks = snapshot_processes(&task_count);
    if (IS_ERR(tasks))
        return PTR_ERR(tasks);
    for (i = 0; i < task_count; i++) {
        struct task_struct *task = tasks[i];
        char comm[TASK_COMM_LEN];

        get_task_comm(comm, task);
        if (!strcmp(comm, pkg_name) ||
            (read_proc_cmdline(task, cmdline_buf, sizeof(cmdline_buf)) > 0 &&
             cmdline_matches_package(cmdline_buf, pkg_name))) {
            running = 1;
            break;
        }
    }

    for (i = 0; i < task_count; i++)
        put_task_struct(tasks[i]);
    kvfree(tasks);
    return running;
}

/* =====================================================================
 * 第三部分：任务解析（支持 PID 或包名）
 * ===================================================================== */

/**
 * 根据 PID 或包名获取 task_struct
 *
 * 优先级：
 *   1. 如果 pid > 0，直接通过 PID 查找
 *   2. 如果 pid <= 0 且 pkg_name 非空，通过包名解析 PID
 *
 * 返回: task_struct 指针（需要调用者 put_task_struct），NULL = 未找到
 */
static struct task_struct *get_task_by_vpid_ref(pid_t nr)
{
    struct pid *pid;
    struct task_struct *task;

    pid = find_get_pid(nr);
    if (!pid)
        return NULL;
    task = get_pid_task(pid, PIDTYPE_PID);
    put_pid(pid);
    return task;
}

static struct task_struct *resolve_task(int pid, const char *pkg_name)
{
    struct task_struct *task = NULL;
    int target_pid = pid;

    /* 如果 pid 无效，尝试通过包名解析 */
    if (target_pid <= 0 && pkg_name && pkg_name[0]) {
        u32 running = 0;
        int ret = resolve_pkg_to_pid(pkg_name, &target_pid, &running);
        if (ret < 0 || target_pid <= 0)
            return NULL;
    }

    if (target_pid <= 0)
        return NULL;

    /* 通过 PID 查找 task_struct */
    task = get_task_by_vpid_ref(target_pid);

    /* 验证进程有效性 */
    if (task) {
        struct mm_struct *mm;

        if (!pid_alive(task)) {
            put_task_struct(task);
            return NULL;
        }
        mm = get_task_mm(task);
        if (!mm) {
            put_task_struct(task);
            return NULL;
        }
        mmput(mm);
    }

    return task;
}

/* =====================================================================
 * 第四部分：内存读写（优化版页表遍历）
 * ===================================================================== */

/**
 * 远程进程内存访问
 * 通过内核标准访问接口处理页故障、巨页和 COW，不直接修改页表
 */
static int read_memory(struct task_struct *task, unsigned long vaddr,
                       void *kbuf, size_t len)
{
    struct mm_struct *mm;
    int ret;

    if (!task || !kbuf || !len || len > MAX_SINGLE_READ ||
        len > ULONG_MAX - vaddr || len > INT_MAX)
        return -EINVAL;

    mm = get_task_mm(task);
    if (!mm)
        return -ESRCH;

    /* access_process_vm handles page faults, huge pages and page pinning. */
    ret = access_process_vm(task, vaddr, kbuf, (int)len, 0);
    mmput(mm);

    if (ret > 0) {
        atomic64_inc(&g_mem_read_cnt);
        atomic64_add(ret, &g_mem_read_bytes);
    }
    return ret;
}

static int write_memory(struct task_struct *task, unsigned long vaddr,
                        void *kbuf, size_t len)
{
    struct mm_struct *mm;
    int ret;

    if (!task || !kbuf || !len || len > 80 ||
        len > ULONG_MAX - vaddr || len > INT_MAX)
        return -EINVAL;

    mm = get_task_mm(task);
    if (!mm)
        return -ESRCH;

    /* FOLL_WRITE asks GUP to perform normal write/COW handling; no FORCE. */
    ret = access_process_vm(task, vaddr, kbuf, (int)len, FOLL_WRITE);
    mmput(mm);

    if (ret > 0)
        atomic64_inc(&g_mem_write_cnt);
    return ret;
}


/* =====================================================================
 * 第五部分：进程/模块/VMA/线程枚举
 * ===================================================================== */

/**
 * 枚举系统进程列表
 */
static int enumerate_processes(struct kai_proc_entry *entries,
                                int max_count, int offset, int *total_count)
{
    struct task_struct **tasks;
    struct task_struct *task;
    int count = 0, total = 0;
    int i;

    if (!entries || max_count <= 0 || offset < 0)
        return -EINVAL;

    tasks = kcalloc(max_count, sizeof(*tasks), GFP_KERNEL);
    if (!tasks)
        return -ENOMEM;

    /* Capture stable task references and scalar fields while under RCU. */
    rcu_read_lock();
    for_each_process(task) {
        pid_t pid = task_pid_vnr(task);

        if (!pid)
            continue;
        if (total++ < offset || count >= max_count)
            continue;
        get_task_struct(task);
        tasks[count] = task;
        entries[count].pid = pid;
        entries[count].ppid = task_ppid_nr_ns(task, NULL);
        entries[count].tgid = task_tgid_vnr(task);
        get_task_comm(entries[count].comm, task);
        entries[count].state = READ_ONCE(task->__state);
        count++;
    }
    rcu_read_unlock();

    /* User-memory access and mm operations are sleepable: do them outside RCU. */
    for (i = 0; i < count; i++) {
        struct task_struct *t = tasks[i];
        struct task_struct *thread;
        int thread_count = 0;

        entries[i].cmdline[0] = '\0';
        read_proc_cmdline(t, entries[i].cmdline,
                          sizeof(entries[i].cmdline));

        rcu_read_lock();
        for_each_thread(t, thread) {
            if (task_pid_vnr(thread))
                thread_count++;
        }
        rcu_read_unlock();
        entries[i].thread_count = thread_count;
        put_task_struct(t);
    }

    kfree(tasks);
    *total_count = total;
    return count;
}

/**
 * 枚举进程加载的模块（共享库）
 */
static int enumerate_modules(struct task_struct *task,
                              struct kai_module_entry *entries,
                              int max_count)
{
    struct mm_struct *mm = get_task_mm(task);
    struct vm_area_struct *vma;
    struct path *paths;
    int count = 0;
    char *pathbuf;

    if (!mm)
        return 0;

    pathbuf = kmalloc(PAGE_SIZE, GFP_KERNEL);
    paths = kmalloc_array(max_count, sizeof(*paths), GFP_KERNEL);
    if (!pathbuf || !paths) {
        count = -ENOMEM;
        goto out;
    }

    mmap_read_lock(mm);
    for (vma = find_vma(mm, 0); vma && count < max_count;
         vma = find_vma(mm, vma->vm_end)) {
        const struct path *file_path;
        char *name;
        int i;

        if (!vma->vm_file)
            continue;
        file_path = &vma->vm_file->f_path;
        name = d_path(file_path, pathbuf, PAGE_SIZE);
        if (IS_ERR(name))
            continue;

        /* Mapped files keep these paths alive while mmap_lock is held. */
        for (i = 0; i < count; i++) {
            if (path_equal(&paths[i], file_path) ||
                !strncmp(entries[i].path, name, sizeof(entries[i].path)))
                break;
        }
        if (i != count)
            continue;

        paths[count] = *file_path;
        entries[count].base_addr = vma->vm_start;
        entries[count].end_addr = vma->vm_end;
        entries[count].size = vma->vm_end - vma->vm_start;
        entries[count].flags = vma->vm_flags & 0xf;
        entries[count].offset = vma->vm_pgoff << PAGE_SHIFT;
        strscpy(entries[count].path, name, sizeof(entries[count].path));
        entries[count].is_exec = !!(vma->vm_flags & VM_EXEC);
        count++;
    }
    mmap_read_unlock(mm);

out:
    kfree(paths);
    kfree(pathbuf);
    mmput(mm);
    return count;
}

static void set_vma_file_name(struct kai_vma_entry *entry, const struct file *file)
{
    struct dentry *dentry = file->f_path.dentry;
    char buf[sizeof(entry->name)];
    const char *path = d_path(&file->f_path, buf, sizeof(buf));
    size_t len;

    if (!IS_ERR(path)) {
        strscpy(entry->name, path, sizeof(entry->name));
        return;
    }

    /* Preserve a useful name when the full path exceeds the ABI field. */
    spin_lock(&dentry->d_lock);
    len = min_t(size_t, dentry->d_name.len, sizeof(entry->name) - 1);
    memcpy(entry->name, dentry->d_name.name, len);
    spin_unlock(&dentry->d_lock);
    entry->name[len] = '\0';
}

/**
 * 枚举进程 VMA（虚拟内存区域）
 */
static int enumerate_vmas(struct task_struct *task,
                           struct kai_vma_entry *entries,
                           int max_count)
{
    struct mm_struct *mm = get_task_mm(task);
    struct vm_area_struct *vma;
    int count = 0;

    if (!mm)
        return 0;

    mmap_read_lock(mm);
    vma = find_vma(mm, 0);
    while (vma && count < max_count) {
        entries[count].start = vma->vm_start;
        entries[count].end = vma->vm_end;
        entries[count].flags = vma->vm_flags & 0xf;
        entries[count].pgoff = vma->vm_pgoff;

        if (vma->vm_file)
            set_vma_file_name(&entries[count], vma->vm_file);
        else
            entries[count].name[0] = '\0';
        entries[count].pad = 0;

        count++;
        vma = find_vma(mm, vma->vm_end);
    }
    mmap_read_unlock(mm);
    mmput(mm);

    return count;
}

/**
 * 枚举进程的所有线程
 */
static int enumerate_threads(struct task_struct *task,
                              struct kai_thread_entry *entries,
                              int max_count)
{
    /* These two ABI fields contain kernel addresses. */
    bool show_kernel_addresses = capable(CAP_SYSLOG);
    struct task_struct *t;
    int count = 0;

    rcu_read_lock();
    for_each_thread(task, t) {
        pid_t tid = task_pid_vnr(t);

        if (count >= max_count)
            break;
        if (!tid)
            continue;

        entries[count].tid = tid;
        get_task_comm(entries[count].comm, t);
        entries[count].state = READ_ONCE(t->__state);
        entries[count].stack_ptr = 0;
        entries[count].pc = 0;
        if (show_kernel_addresses) {
            entries[count].stack_ptr = (unsigned long)READ_ONCE(t->stack);
#ifdef thread_saved_pc
            entries[count].pc = thread_saved_pc(t);
#endif
        }
        count++;
    }
    rcu_read_unlock();

    return count;
}

/**
 * 获取模块基址
 */
static unsigned long __maybe_unused get_module_base(struct task_struct *task,
                                      const char *mod_name)
{
    struct mm_struct *mm = get_task_mm(task);
    unsigned long base = 0;
    unsigned long addr = 0;
    struct vm_area_struct *vma;
    char *pathbuf;

    if (!mm)
        return 0;

    pathbuf = kmalloc(PAGE_SIZE, GFP_KERNEL);
    if (!pathbuf) {
        mmput(mm);
        return 0;
    }

    mmap_read_lock(mm);
    while ((vma = find_vma(mm, addr)) != NULL) {
        if (vma->vm_file) {
            char *path = d_path(&vma->vm_file->f_path, pathbuf, PAGE_SIZE);
            if (!IS_ERR(path) && strstr(path, mod_name)) {
                base = vma->vm_start;
                break;
            }
        }
        addr = vma->vm_end;
    }
    mmap_read_unlock(mm);

    kfree(pathbuf);
    mmput(mm);
    return base;
}

/* =====================================================================
 * 第六部分：内存搜索
 * ===================================================================== */

/**
 * 在目标进程地址空间中搜索精确字节模式（当前版本不支持通配符）
 */
static int search_memory_pattern(struct task_struct *task,
                                  const char *pattern, u32 pattern_size,
                                  unsigned long start_addr,
                                  unsigned long end_addr,
                                  int max_results,
                                  unsigned long *results, int *total_found)
{
    struct mm_struct *mm;
    char *read_buf;
    unsigned long cursor = start_addr;
    int result_count = 0;
    int total = 0;

    if (!task || !pattern || !pattern_size || pattern_size > 512 ||
        max_results <= 0 || !results || !total_found ||
        (end_addr && end_addr < start_addr))
        return -EINVAL;

    mm = get_task_mm(task);
    if (!mm)
        return -ESRCH;

    read_buf = kmalloc(PAGE_SIZE, GFP_KERNEL);
    if (!read_buf) {
        mmput(mm);
        return -ENOMEM;
    }

    *total_found = 0;

    /* Find one VMA range under mmap_lock, then release the lock before
     * access_process_vm(), which may fault pages and sleep. */
    for (;;) {
        struct vm_area_struct *vma;
        unsigned long range_start, range_end, next_cursor;

        mmap_read_lock(mm);
        vma = find_vma(mm, cursor);
        if (!vma || (end_addr && vma->vm_start > end_addr)) {
            mmap_read_unlock(mm);
            break;
        }
        range_start = max(vma->vm_start, cursor);
        range_end = vma->vm_end;
        if (end_addr)
            range_end = min(range_end, end_addr);
        next_cursor = vma->vm_end;
        mmap_read_unlock(mm);

        while (range_start < range_end && result_count < max_results) {
            size_t request = min_t(unsigned long, PAGE_SIZE,
                                   range_end - range_start);
            size_t offset;
            int got;

            if (fatal_signal_pending(current)) {
                result_count = -EINTR;
                goto finish_search;
            }
            cond_resched();
            got = access_process_vm(task, range_start, read_buf,
                                    (int)request, 0);
            if (got >= (int)pattern_size && got <= (int)request) {
                for (offset = 0; offset + pattern_size <= (size_t)got;
                     offset++) {
                    if (!memcmp(read_buf + offset, pattern, pattern_size)) {
                        results[result_count++] = range_start + offset;
                        total++;
                        if (result_count >= max_results)
                            break;
                    }
                }
                if (result_count >= max_results)
                    break;
                /* Keep pattern_size-1 bytes of overlap between chunks. */
                range_start += got - (pattern_size - 1);
            } else {
                /* Unmapped/inaccessible chunk: advance without spinning. */
                range_start += request;
            }
        }

        if (result_count >= max_results || next_cursor <= cursor)
            break;
        cursor = next_cursor;
        if (end_addr && cursor > end_addr)
            break;
    }

finish_search:
    kfree(read_buf);
    mmput(mm);
    *total_found = total;
    return result_count;
}

#ifdef QINGWEI_HAVE_HW_BREAKPOINT
/* =====================================================================
 * 第七部分：硬件断点支持
 * ===================================================================== */

/**
 * 通过 kprobe 解析未导出的内核符号
 */
static unsigned long resolve_symbol(const char *name)
{
    struct kprobe kp;
    unsigned long addr;

    memset(&kp, 0, sizeof(kp));
    kp.symbol_name = (char *)name;

    if (register_kprobe(&kp) < 0)
        return 0;

    addr = (unsigned long)kp.addr;
    unregister_kprobe(&kp);
    return addr;
}

/**
 * HWBP 溢出处理 — 中断上下文，只能使用 spinlock
 */
static void hw_bp_overflow_handler(struct perf_event *event,
                                    struct perf_sample_data *data,
                                    struct pt_regs *regs)
{
    struct hw_bp_slot *slot = event->overflow_handler_context;
    struct reg_snapshot *snapshot;
    int i;

    if (!regs || !slot || !user_mode(regs))
        return;

    atomic64_inc(&slot->hit_count);

    /* Debug exceptions can interrupt a snapshot reader on the same CPU. */
    if (!raw_spin_trylock(&g_reg_snap_lock))
        return;

    snapshot = &g_reg_snapshots[slot - g_hw_bp_slots];
    snapshot->bp_id = slot->bp_id;
    for (i = 0; i < 31; i++)
        snapshot->regs[i] = regs->regs[i];
    snapshot->regs[31] = regs->sp;
    snapshot->regs[32] = regs->pc;
    snapshot->regs[33] = regs->pstate;
    snapshot->valid = 1;
    raw_spin_unlock(&g_reg_snap_lock);
}

/**
 * 设置硬件断点
 */
static int hw_bp_set(int pid, unsigned long addr, u32 bp_type, u32 bp_len,
                      u32 *out_bp_id)
{
    struct perf_event_attr attr;
    struct task_struct *task;
    struct hw_bp_slot *slot = NULL;
    struct perf_event *bp;
    int ret, i;

    if (!g_reg_hw_bp || !g_unreg_hw_bp)
        return -ENOSYS;

    task = get_task_by_vpid_ref(pid);
    if (!task)
        return -ESRCH;

    ptrace_breakpoint_init(&attr);
    attr.bp_addr = addr;
    attr.bp_len = bp_len ? bp_len : HW_BREAKPOINT_LEN_4;
    attr.bp_type = bp_type;

    mutex_lock(&g_hw_bp_lock);
    for (i = 0; i < MAX_HW_BREAKPOINTS; i++) {
        if (!g_hw_bp_slots[i].active) {
            slot = &g_hw_bp_slots[i];
            break;
        }
    }
    if (!slot || !g_next_bp_id) {
        ret = -ENOSPC;
        goto fail;
    }

    /* Registration can invoke the callback before it returns. */
    slot->bp_id = g_next_bp_id;
    atomic64_set(&slot->hit_count, 0);
    bp = g_reg_hw_bp(&attr, hw_bp_overflow_handler, slot, task);
    if (IS_ERR(bp)) {
        ret = PTR_ERR(bp);
        memset(slot, 0, sizeof(*slot));
        goto fail;
    }

    slot->event = bp;
    slot->target_task = task;
    slot->bp_addr = addr;
    slot->bp_type = bp_type;
    slot->bp_len = attr.bp_len;
    slot->active = true;
    *out_bp_id = g_next_bp_id++;
    mutex_unlock(&g_hw_bp_lock);
    return 0;

fail:
    mutex_unlock(&g_hw_bp_lock);
    put_task_struct(task);
    return ret;
}

/* The caller holds g_hw_bp_lock and the slot owns both references. */
static void hw_bp_release_slot(int i)
{
    struct hw_bp_slot *slot = &g_hw_bp_slots[i];
    unsigned long flags;

    g_unreg_hw_bp(slot->event);
    put_task_struct(slot->target_task);

    raw_spin_lock_irqsave(&g_reg_snap_lock, flags);
    memset(&g_reg_snapshots[i], 0, sizeof(g_reg_snapshots[i]));
    raw_spin_unlock_irqrestore(&g_reg_snap_lock, flags);
    memset(slot, 0, sizeof(*slot));
}

/**
 * 移除硬件断点
 */
static int hw_bp_remove(u32 bp_id)
{
    int i;

    mutex_lock(&g_hw_bp_lock);
    for (i = 0; i < MAX_HW_BREAKPOINTS; i++) {
        if (g_hw_bp_slots[i].active &&
            g_hw_bp_slots[i].bp_id == bp_id) {
            hw_bp_release_slot(i);
            mutex_unlock(&g_hw_bp_lock);

            pr_info("HWBP removed: bp_id=%u\n", bp_id);
            return 0;
        }
    }
    mutex_unlock(&g_hw_bp_lock);

    return -ENOENT;
}

/**
 * 列出所有硬件断点
 */
static int hw_bp_list(struct kai_bp_entry *entries, int max_count)
{
    int count = 0;
    int i;

    mutex_lock(&g_hw_bp_lock);
    for (i = 0; i < MAX_HW_BREAKPOINTS && count < max_count; i++) {
        if (g_hw_bp_slots[i].active) {
            entries[count].index = g_hw_bp_slots[i].bp_id;
            entries[count].addr = g_hw_bp_slots[i].bp_addr;
            entries[count].type = g_hw_bp_slots[i].bp_type;
            entries[count].len = g_hw_bp_slots[i].bp_len;
            entries[count].hit_count =
                atomic64_read(&g_hw_bp_slots[i].hit_count);
            entries[count].active = 1;
            count++;
        }
    }
    mutex_unlock(&g_hw_bp_lock);

    return count;
}

/**
 * 读取断点命中时的寄存器快照
 */
static int hw_bp_read_regs(u32 bp_id, struct kai_reg_read_packet *pkt)
{
    unsigned long flags;
    int i;

    pkt->bp_id = bp_id;
    pkt->valid = 0;
    memset(pkt->regs, 0, sizeof(pkt->regs));
    raw_spin_lock_irqsave(&g_reg_snap_lock, flags);
    for (i = 0; i < MAX_HW_BREAKPOINTS; i++) {
        if (g_reg_snapshots[i].valid &&
            g_reg_snapshots[i].bp_id == bp_id) {
            pkt->valid = 1;
            memcpy(pkt->regs, g_reg_snapshots[i].regs, sizeof(pkt->regs));
            break;
        }
    }
    raw_spin_unlock_irqrestore(&g_reg_snap_lock, flags);
    return pkt->valid ? 0 : -ENOENT;
}

/**
 * 清理所有硬件断点
 */
static void hw_bp_cleanup_all(void)
{
    int i;

    mutex_lock(&g_hw_bp_lock);
    for (i = 0; i < MAX_HW_BREAKPOINTS; i++) {
        if (g_hw_bp_slots[i].active)
            hw_bp_release_slot(i);
    }
    mutex_unlock(&g_hw_bp_lock);
}

#else /* !QINGWEI_HAVE_HW_BREAKPOINT */

static void hw_bp_cleanup_all(void) {}

#endif /* QINGWEI_HAVE_HW_BREAKPOINT */

/* =====================================================================
 * 第八部分：ioctl 处理入口
 * ===================================================================== */

/* Allow one extra entry to distinguish a fitting result from ENOSPC. */
static int list_scan_count(int max_count, u64 capacity, u32 entry_size)
{
    return min_t(u64, max_count - 1, div_u64(capacity, entry_size)) + 1;
}

static long device_ioctl(struct file *filp, unsigned int cmd,
                          unsigned long arg)
{
    u64 start_ns = ktime_get_ns();
    long ret = 0;
    void __user *uarg = (void __user *)arg;

    /* This device exposes process inspection and modification primitives. */
    if (!capable(CAP_SYS_PTRACE)) {
        ret = -EPERM;
        goto out;
    }

    /* ---- CMD_GET_MODULE_INFO: 获取模块性能信息 ---- */
    if (cmd == CMD_GET_MODULE_INFO) {
        struct module_perf_info info;

        memset(&info, 0, sizeof(info));
        info.cpu_time_ns      = atomic64_read(&g_total_cpu_ns);
        info.call_count       = atomic64_read(&g_call_count);
        info.pkg_resolve_count = atomic64_read(&g_pkg_resolve_cnt);
        info.pkg_resolve_hit   = atomic64_read(&g_pkg_resolve_hit);
        info.mem_read_count    = atomic64_read(&g_mem_read_cnt);
        info.mem_write_count   = atomic64_read(&g_mem_write_cnt);
        info.mem_read_bytes    = atomic64_read(&g_mem_read_bytes);
        if (copy_to_user(uarg, &info, sizeof(info)))
            ret = -EFAULT;
        goto out;
    }

    /* ---- CMD_GET_HWBP_STATS: 获取硬件断点统计 ---- */
    if (cmd == CMD_GET_HWBP_STATS) {
        struct hwbp_stats stats;
        memset(&stats, 0, sizeof(stats));
#ifdef QINGWEI_HAVE_HW_BREAKPOINT
        {
            int i;

            mutex_lock(&g_hw_bp_lock);
            for (i = 0; i < MAX_HW_BREAKPOINTS; i++) {
                if (g_hw_bp_slots[i].active) {
                    stats.hit_count +=
                        atomic64_read(&g_hw_bp_slots[i].hit_count);
                    stats.bp_addr = g_hw_bp_slots[i].bp_addr;
                    stats.active = 1;
                    break;
                }
            }
            mutex_unlock(&g_hw_bp_lock);
        }
        spin_lock(&g_snapshot_lock);
        stats.snapshot_count = g_snapshot_count;
        spin_unlock(&g_snapshot_lock);
#endif
        if (copy_to_user(uarg, &stats, sizeof(stats)))
            ret = -EFAULT;
        goto out;
    }

    /* ---- ★ CMD_RESOLVE_PKG: 包名→PID解析（核心优化）★ ---- */
    if (cmd == CMD_RESOLVE_PKG) {
        struct kai_resolve_pkg req;
        int pid = 0;
        u32 running = 0;

        if (copy_from_user(&req, uarg, sizeof(req))) {
            ret = -EFAULT;
            goto out;
        }

        req.pkg_name[sizeof(req.pkg_name) - 1] = '\0';
        ret = resolve_pkg_to_pid(req.pkg_name, &pid, &running);
        if (ret < 0 && ret != -ESRCH)
            goto out;

        req.pid = pid;
        req.running = running;
        if (copy_to_user(uarg, &req, sizeof(req)))
            ret = -EFAULT;
        else
            ret = 0;
        goto out;
    }

    /* ---- CMD_LIST_PROCS: 列出进程 ---- */
    if (cmd == CMD_LIST_PROCS) {
        struct kai_proc_list_packet pkt;
        struct kai_proc_entry *entries;
        int scan_count, actual, total;

        if (copy_from_user(&pkt, uarg, sizeof(pkt))) {
            ret = -EFAULT;
            goto out;
        }

        if (pkt.max_count <= 0 || pkt.max_count > MAX_PROC_ENTRIES)
            pkt.max_count = MAX_PROC_ENTRIES;

        scan_count = list_scan_count(pkt.max_count, pkt.out_buf_size,
                                     sizeof(*entries));
        entries = kvcalloc(scan_count, sizeof(*entries), GFP_KERNEL);
        if (!entries) {
            ret = -ENOMEM;
            goto out;
        }

        actual = enumerate_processes(entries, scan_count, pkt.offset, &total);
        if (actual < 0) {
            kvfree(entries);
            ret = actual;
            goto out;
        }

        pkt.actual_count = actual;
        pkt.total_count = total;

        if (pkt.out_buf_size < (u64)actual * sizeof(*entries)) {
            kvfree(entries);
            ret = -ENOSPC;
            goto out;
        }

        /* 将头部写回 */
        if (copy_to_user(uarg, &pkt, sizeof(pkt))) {
            kvfree(entries);
            ret = -EFAULT;
            goto out;
        }

        /* 将进程条目写到头部之后的用户空间缓冲区 */
        if (actual > 0) {
            size_t data_size = actual * sizeof(*entries);
            void __user *data_dst = uarg + sizeof(pkt);

            if (copy_to_user(data_dst, entries, data_size)) {
                kvfree(entries);
                ret = -EFAULT;
                goto out;
            }
        }

        kvfree(entries);
        ret = 0;
        goto out;
    }

    /* ---- CMD_LIST_MODULES: 列出模块 ---- */
    if (cmd == CMD_LIST_MODULES) {
        struct kai_module_list_packet pkt;
        struct kai_module_entry *entries;
        struct task_struct *task;
        int scan_count, actual;

        if (copy_from_user(&pkt, uarg, sizeof(pkt))) {
            ret = -EFAULT;
            goto out;
        }

        task = resolve_task(pkt.pid, NULL);
        if (!task) {
            ret = -ESRCH;
            goto out;
        }

        if (pkt.max_count <= 0 || pkt.max_count > MAX_MODULE_ENTRIES)
            pkt.max_count = MAX_MODULE_ENTRIES;

        scan_count = list_scan_count(pkt.max_count, pkt.out_buf_size,
                                     sizeof(*entries));
        entries = kvcalloc(scan_count, sizeof(*entries), GFP_KERNEL);
        if (!entries) {
            put_task_struct(task);
            ret = -ENOMEM;
            goto out;
        }

        actual = enumerate_modules(task, entries, scan_count);
        put_task_struct(task);
        if (actual < 0) {
            kvfree(entries);
            ret = actual;
            goto out;
        }
        pkt.actual_count = actual;

        if (pkt.out_buf_size < (u64)actual * sizeof(*entries)) {
            kvfree(entries);
            ret = -ENOSPC;
            goto out;
        }

        if (copy_to_user(uarg, &pkt, sizeof(pkt)) ||
            (actual > 0 && copy_to_user(uarg + sizeof(pkt),
                                         entries,
                                         actual * sizeof(*entries)))) {
            kvfree(entries);
            ret = -EFAULT;
            goto out;
        }

        kvfree(entries);
        ret = 0;
        goto out;
    }

    /* ---- CMD_LIST_VMAS: 列出 VMA ---- */
    if (cmd == CMD_LIST_VMAS) {
        struct kai_vma_list_packet pkt;
        struct kai_vma_entry *entries;
        struct task_struct *task;
        int scan_count, actual;

        if (copy_from_user(&pkt, uarg, sizeof(pkt))) {
            ret = -EFAULT;
            goto out;
        }

        task = resolve_task(pkt.pid, NULL);
        if (!task) {
            ret = -ESRCH;
            goto out;
        }

        if (pkt.max_count <= 0 || pkt.max_count > MAX_VMA_ENTRIES)
            pkt.max_count = MAX_VMA_ENTRIES;

        scan_count = list_scan_count(pkt.max_count, pkt.out_buf_size,
                                     sizeof(*entries));
        entries = kvcalloc(scan_count, sizeof(*entries), GFP_KERNEL);
        if (!entries) {
            put_task_struct(task);
            ret = -ENOMEM;
            goto out;
        }

        actual = enumerate_vmas(task, entries, scan_count);
        put_task_struct(task);
        pkt.actual_count = actual;

        if (pkt.out_buf_size < (u64)actual * sizeof(*entries)) {
            kvfree(entries);
            ret = -ENOSPC;
            goto out;
        }

        if (copy_to_user(uarg, &pkt, sizeof(pkt)) ||
            (actual > 0 && copy_to_user(uarg + sizeof(pkt),
                                         entries,
                                         actual * sizeof(*entries)))) {
            kvfree(entries);
            ret = -EFAULT;
            goto out;
        }

        kvfree(entries);
        ret = 0;
        goto out;
    }

    /* ---- CMD_LIST_THREADS: 列出线程 ---- */
    if (cmd == CMD_LIST_THREADS) {
        struct kai_thread_list_packet pkt;
        struct kai_thread_entry *entries;
        struct task_struct *task;
        int scan_count, actual;

        if (copy_from_user(&pkt, uarg, sizeof(pkt))) {
            ret = -EFAULT;
            goto out;
        }

        task = resolve_task(pkt.pid, NULL);
        if (!task) {
            ret = -ESRCH;
            goto out;
        }

        if (pkt.max_count <= 0 || pkt.max_count > MAX_THREAD_ENTRIES)
            pkt.max_count = MAX_THREAD_ENTRIES;

        scan_count = list_scan_count(pkt.max_count, pkt.out_buf_size,
                                     sizeof(*entries));
        entries = kvcalloc(scan_count, sizeof(*entries), GFP_KERNEL);
        if (!entries) {
            put_task_struct(task);
            ret = -ENOMEM;
            goto out;
        }

        actual = enumerate_threads(task, entries, scan_count);
        put_task_struct(task);
        pkt.actual_count = actual;

        if (pkt.out_buf_size < (u64)actual * sizeof(*entries)) {
            kvfree(entries);
            ret = -ENOSPC;
            goto out;
        }

        if (copy_to_user(uarg, &pkt, sizeof(pkt)) ||
            (actual > 0 && copy_to_user(uarg + sizeof(pkt),
                                         entries,
                                         actual * sizeof(*entries)))) {
            kvfree(entries);
            ret = -EFAULT;
            goto out;
        }

        kvfree(entries);
        ret = 0;
        goto out;
    }

    /* ---- CMD_RAW_READ: 原始内存读取 ---- */
    if (cmd == CMD_RAW_READ) {
        struct kai_raw_read_packet pkt;
        struct task_struct *task;
        void *kbuf;
        int actual;

        if (copy_from_user(&pkt, uarg, sizeof(pkt))) {
            ret = -EFAULT;
            goto out;
        }

        if (pkt.size == 0 || pkt.size > MAX_SINGLE_READ ||
            pkt.data_size < pkt.size) {
            ret = -EINVAL;
            goto out;
        }

        task = resolve_task(pkt.pid, NULL);
        if (!task) {
            ret = -ESRCH;
            goto out;
        }

        kbuf = kvmalloc(pkt.size, GFP_KERNEL);
        if (!kbuf) {
            put_task_struct(task);
            ret = -ENOMEM;
            goto out;
        }

        actual = read_memory(task, pkt.address, kbuf, pkt.size);
        if (actual <= 0) {
            ret = actual < 0 ? actual : -EFAULT;
        } else {
            pkt.actual_size = actual;
            if (copy_to_user(uarg, &pkt, sizeof(pkt)) ||
                copy_to_user(uarg + sizeof(pkt), kbuf, actual))
                ret = -EFAULT;
        }

        kvfree(kbuf);
        put_task_struct(task);
        goto out;
    }

    /* ---- CMD_WRITE_MEM: 内存写入 ---- */
    if (cmd == CMD_WRITE_MEM) {
        /* Legacy 104-byte ABI: pid, pad, address, size, actual_size, data[80]. */
        struct {
            __s32  pid;
            __s32  pad;
            __u64  addr;
            __u32  size;
            __u32  actual_size;
            char   data[80];
        } __packed wpkt;
        struct task_struct *task;
        int written;

        if (copy_from_user(&wpkt, uarg, sizeof(wpkt))) {
            ret = -EFAULT;
            goto out;
        }

        task = resolve_task(wpkt.pid, NULL);
        if (!task) {
            ret = -ESRCH;
            goto out;
        }

        if (wpkt.size == 0 || wpkt.size > 80) {
            put_task_struct(task);
            ret = -EINVAL;
            goto out;
        }

        written = write_memory(task, wpkt.addr, wpkt.data, wpkt.size);
        put_task_struct(task);
        if (written <= 0) {
            ret = written < 0 ? written : -EIO;
            goto out;
        }

        wpkt.actual_size = written;
        ret = written == wpkt.size ? 0 : -EIO;
        if (copy_to_user(uarg, &wpkt, sizeof(wpkt)))
            ret = -EFAULT;
        goto out;
    }

    /* ---- CMD_MEM_SEARCH: 内存搜索 ---- */
    if (cmd == CMD_MEM_SEARCH) {
        struct kai_mem_search_packet pkt;
        struct task_struct *task;
        unsigned long *results;
        int found, total_found;

        if (copy_from_user(&pkt, uarg, sizeof(pkt))) {
            ret = -EFAULT;
            goto out;
        }

        if (pkt.pattern_size == 0 ||
            pkt.pattern_size > sizeof(pkt.pattern) ||
            pkt.pattern_size > PAGE_SIZE ||
            (pkt.end_addr && pkt.end_addr < pkt.start_addr)) {
            ret = -EINVAL;
            goto out;
        }

        task = resolve_task(pkt.pid, NULL);
        if (!task) {
            ret = -ESRCH;
            goto out;
        }

        if (pkt.max_results <= 0 || pkt.max_results > MAX_SEARCH_RESULTS)
            pkt.max_results = MAX_SEARCH_RESULTS;
        if (pkt.results_size < (u64)pkt.max_results * sizeof(unsigned long)) {
            put_task_struct(task);
            ret = -ENOSPC;
            goto out;
        }

        results = kcalloc(pkt.max_results, sizeof(unsigned long),
                          GFP_KERNEL);
        if (!results) {
            put_task_struct(task);
            ret = -ENOMEM;
            goto out;
        }

        found = search_memory_pattern(task, pkt.pattern, pkt.pattern_size,
                                       pkt.start_addr, pkt.end_addr,
                                       pkt.max_results, results,
                                       &total_found);

        if (found < 0) {
            kfree(results);
            put_task_struct(task);
            ret = found;
            goto out;
        }

        pkt.actual_results = found;
        pkt.total_found = total_found;

        if (copy_to_user(uarg, &pkt, sizeof(pkt)) ||
            (found > 0 && copy_to_user(uarg + sizeof(pkt),
                                        results,
                                        found * sizeof(unsigned long)))) {
            kfree(results);
            put_task_struct(task);
            ret = -EFAULT;
            goto out;
        }

        kfree(results);
        put_task_struct(task);
        ret = 0;
        goto out;
    }

#ifdef QINGWEI_HAVE_HW_BREAKPOINT
    /* ---- CMD_HWBP_SET: 设置硬件断点 ---- */
    if (cmd == CMD_HWBP_SET) {
        struct kai_hwbp_setup pkt;
        u32 bp_id = 0;

        if (copy_from_user(&pkt, uarg, sizeof(pkt))) {
            ret = -EFAULT;
            goto out;
        }

        ret = hw_bp_set(pkt.pid, pkt.address, pkt.bp_type,
                         pkt.bp_len, &bp_id);
        if (ret == 0) {
            pkt.bp_id = bp_id;
            if (copy_to_user(uarg, &pkt, sizeof(pkt))) {
                hw_bp_remove(bp_id);
                ret = -EFAULT;
            }
        }
        goto out;
    }

    /* ---- CMD_HWBP_REMOVE: 移除硬件断点 ---- */
    if (cmd == CMD_HWBP_REMOVE) {
        struct kai_hwbp_remove pkt;

        if (copy_from_user(&pkt, uarg, sizeof(pkt))) {
            ret = -EFAULT;
            goto out;
        }

        ret = hw_bp_remove(pkt.bp_id);
        goto out;
    }

    /* ---- CMD_HWBP_LIST: 列出硬件断点 ---- */
    if (cmd == CMD_HWBP_LIST) {
        struct kai_hwbp_list_packet pkt;
        struct kai_bp_entry *entries;
        int actual;

        if (copy_from_user(&pkt, uarg, sizeof(pkt))) {
            ret = -EFAULT;
            goto out;
        }

        entries = kcalloc(MAX_HW_BREAKPOINTS, sizeof(*entries),
                          GFP_KERNEL);
        if (!entries) {
            ret = -ENOMEM;
            goto out;
        }

        actual = hw_bp_list(entries, MAX_HW_BREAKPOINTS);
        pkt.total_bps = actual;
        pkt.actual_bps = actual;

        if (pkt.out_buf_size < (u64)actual * sizeof(*entries)) {
            kfree(entries);
            ret = -ENOSPC;
            goto out;
        }

        if (copy_to_user(uarg, &pkt, sizeof(pkt)) ||
            (actual > 0 && copy_to_user(uarg + sizeof(pkt),
                                         entries,
                                         actual * sizeof(*entries)))) {
            kfree(entries);
            ret = -EFAULT;
            goto out;
        }

        kfree(entries);
        ret = 0;
        goto out;
    }

    /* ---- CMD_READ_REGS: 读取寄存器快照 ---- */
    if (cmd == CMD_READ_REGS) {
        struct kai_reg_read_packet pkt;

        if (copy_from_user(&pkt, uarg, sizeof(pkt))) {
            ret = -EFAULT;
            goto out;
        }

        ret = hw_bp_read_regs(pkt.bp_id, &pkt);
        if (copy_to_user(uarg, &pkt, sizeof(pkt)))
            ret = -EFAULT;
        else
            ret = 0;
        goto out;
    }
#endif /* QINGWEI_HAVE_HW_BREAKPOINT */

    /* The ABI numbers are reserved for these operations. */
    if (cmd == CMD_SWBP_SET || cmd == CMD_SWBP_REMOVE || cmd == CMD_CALLSTACK) {
        ret = -ENOSYS;
        goto out;
    }

    /* 未识别的命令 */
    ret = -ENOTTY;

out:
    {
        u64 delta = ktime_get_ns() - start_ns;
        atomic64_add(delta, &g_total_cpu_ns);
        atomic64_inc(&g_call_count);
    }
    return ret;
}

/* =====================================================================
 * 第九部分：设备注册与模块初始化
 * ===================================================================== */

static struct file_operations fops = {
    .owner          = THIS_MODULE,
    .unlocked_ioctl = device_ioctl,
};

static struct miscdevice misc_dev = {
    .minor = MISC_DYNAMIC_MINOR,
    .name  = DEVICE_NAME,
    .fops  = &fops,
};

static int __init qingwei_mcp_init(void)
{
    int ret;

    /* 初始化 PID 缓存 */
    pid_cache_init();

#ifdef QINGWEI_HAVE_HW_BREAKPOINT
    /* 动态解析 HWBP 符号 */
    g_reg_hw_bp = (register_user_hw_bp_fn)
        resolve_symbol("register_user_hw_breakpoint");
    g_unreg_hw_bp = (unregister_hw_bp_fn)
        resolve_symbol("unregister_hw_breakpoint");

    if (g_reg_hw_bp && g_unreg_hw_bp) {
        pr_info("HWBP symbols resolved via kprobe\n");
        memset(g_hw_bp_slots, 0, sizeof(g_hw_bp_slots));
        memset(g_reg_snapshots, 0, sizeof(g_reg_snapshots));
    } else {
        pr_warn("HWBP symbols not found, HWBP disabled\n");
    }
#endif

    /* 注册设备 */
    ret = misc_register(&misc_dev);
    if (ret) {
        pr_err("misc_register failed: %d\n", ret);
        return ret;
    }

    /* Keep the module identity intact for diagnostics and auditing. */
    pr_info("device /dev/%s ready\n", DEVICE_NAME);

    return 0;
}

static void __exit qingwei_mcp_exit(void)
{
#ifdef QINGWEI_HAVE_HW_BREAKPOINT
    hw_bp_cleanup_all();
#endif

    /* 清理 PID 缓存 */
    {
        int i;
        mutex_lock(&g_cache_lock);
        for (i = 0; i < PID_CACHE_SIZE; i++) {
            if (g_pid_cache_pool[i].task)
                pid_cache_remove(&g_pid_cache_pool[i]);
        }
        mutex_unlock(&g_cache_lock);
    }

    misc_deregister(&misc_dev);

    pr_info("module unloaded, total calls=%llu, pkg_resolves=%llu, "
            "cache_hits=%llu\n",
            (unsigned long long)atomic64_read(&g_call_count),
            (unsigned long long)atomic64_read(&g_pkg_resolve_cnt),
            (unsigned long long)atomic64_read(&g_pkg_resolve_hit));
}

module_init(qingwei_mcp_init);
module_exit(qingwei_mcp_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("Security Researcher");
MODULE_DESCRIPTION("Optimized kernel driver with pkg->pid resolution, "
                   "memory R/W, HWBP support for MCP protocol");
MODULE_VERSION("2.0.0");

