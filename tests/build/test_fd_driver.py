"""Fault injection against the actual FD-driver helpers and ioctl code.

This checks driver control flow, not the real kernel ABI or hardware. The
existing build-ko-module workflow supplies the actual ARM64/GKI build.
"""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
DRIVER = ROOT / "modules/custom/qingwei_fd_driver.c"
SOURCE = DRIVER.read_text(encoding="utf-8")


def function(name):
    match = re.search(r"^static [^\n]+\b" + name + r"\(", SOURCE, re.M)
    if not match:
        raise AssertionError(name)
    start = SOURCE.index("{", match.start())
    # Mask comments and literals without changing their positions.
    masked = re.sub(
        r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
        lambda m: " " * len(m.group()), SOURCE, flags=re.S)
    depth = 0
    for i in range(start, len(masked)):
        depth += (masked[i] == "{") - (masked[i] == "}")
        if not depth:
            return SOURCE[match.start():i + 1]
    raise AssertionError(name)


PRELUDE = r"""
typedef unsigned long size_t;
typedef unsigned char uint8_t;
typedef unsigned short uint16_t;
typedef unsigned int uint32_t, u32;
typedef int int32_t;
typedef unsigned long long uint64_t, u64;
typedef _Bool bool;
#define true 1
#define false 0
#define NULL ((void *)0)
#define __user
#define GFP_KERNEL 0
#define PAGE_SIZE 4096UL
#define TASK_SIZE_MAX (1UL << 48)
#define INT_MAX 2147483647
#define FOLL_WRITE 1
#define CAP_SYS_PTRACE 19
#define PIDTYPE_PID 0
#define EFAULT 14
#define EINVAL 22
#define ESRCH 3
#define ENOMEM 12
#define EOVERFLOW 75
#define EOPNOTSUPP 95
#define ENOSYS 38
#define ENODEV 19
#define E2BIG 7
#define ENOENT 2
#define ENOTTY 25
#define EPERM 1
#define EBUSY 16
#define ENOTCONN 107
#define ERESTARTSYS 512
#define ARRAY_SIZE(x) (sizeof(x)/sizeof((x)[0]))
#define min_t(t,a,b) ((t)(a)<(t)(b)?(t)(a):(t)(b))
#define check_add_overflow(a,b,out) __builtin_add_overflow((a),(b),(out))
#define _IO(m,n) (((m)<<8)|(n))
#define _IOR(m,n,t) (0x80000000U|((unsigned)sizeof(t)<<16)|_IO(m,n))
#define _IOW(m,n,t) (0x40000000U|((unsigned)sizeof(t)<<16)|_IO(m,n))
#define _IOWR(m,n,t) (0xc0000000U|((unsigned)sizeof(t)<<16)|_IO(m,n))
#define KERNEL_VERSION(a,b,c) (((a)<<16)|((b)<<8)|(c))
#define LINUX_VERSION_CODE KERNEL_VERSION(6,1,138)
#define QW_GET_TLS(t) ((t)->tls)
#define QW_HAS_PAC 0
#define DEVICE_NAME "qingwei_fd"
#define ERR_PTR(e) ((void *)(long)(e))
#define PTR_ERR(p) ((long)(p))
#define IS_ERR(p) ((unsigned long)(p) >= (unsigned long)-4095)
#define pr_info(...) ((void)0)
#define pr_warn(...) ((void)0)
#define pr_err(...) ((void)0)
#define CHECK(x) do { if (!(x)) { report_failure(__LINE__, #x); } } while(0)
extern void *malloc(size_t);
extern void free(void *);
extern void *memcpy(void *,const void *,size_t);
extern void *memset(void *,int,size_t);
extern void *memchr(const void *,int,size_t);
extern int memcmp(const void *,const void *,size_t);
extern size_t strlen(const char *);
extern int strcmp(const char *,const char *);
extern int strncmp(const char *,const char *,size_t);
static long strscpy(char *d,const char *s,size_t n) { size_t i; for(i=0;i+1<n && s[i];i++) d[i]=s[i]; d[i]=0; return i; }
extern int printf(const char *,...);
extern void exit(int);
static void report_failure(int line,const char *s) {
    printf("FAIL line %d: %s\n",line,s); exit(1);
}
static int irqs_disabled, rcu_depth, live_allocs, fail_alloc;
static int copy_count, copy_fail_at, authorized = 1, short_access, access_calls;
static unsigned int last_gup_flags;
struct mutex { int held; };
typedef struct { int held; } spinlock_t;
#define DEFINE_MUTEX(n) struct mutex n
#define DEFINE_SPINLOCK(n) spinlock_t n
#define __SPIN_LOCK_UNLOCKED(n) {0}
static void mutex_init(struct mutex *m) { m->held=0; }
static void mutex_lock(struct mutex *m) { CHECK(!m->held); m->held=1; }
static void mutex_unlock(struct mutex *m) { CHECK(m->held); m->held=0; }
static int mutex_lock_interruptible(struct mutex *m) { mutex_lock(m); return 0; }
#define spin_lock_irqsave(l,f) do { (f)=irqs_disabled; CHECK(!(l)->held); \
    (l)->held=1; irqs_disabled=1; } while(0)
#define spin_unlock_irqrestore(l,f) do { CHECK((l)->held); \
    (l)->held=0; irqs_disabled=(f); } while(0)
static void spin_lock_bh(spinlock_t *l) { CHECK(!l->held); l->held=1; }
static void spin_unlock_bh(spinlock_t *l) { CHECK(l->held); l->held=0; }
static void rcu_read_lock(void) { ++rcu_depth; }
static void rcu_read_unlock(void) { CHECK(rcu_depth); --rcu_depth; }
typedef struct { long long value; } atomic64_t;
typedef struct { int value; } atomic_t;
#define ATOMIC64_INIT(n) {n}
static u64 atomic64_read(atomic64_t *a) { return a->value; }
static void atomic64_set(atomic64_t *a,u64 v) { a->value=v; }
static void atomic64_inc(atomic64_t *a) { ++a->value; }
static void atomic64_add(u64 v,atomic64_t *a) { a->value+=v; }
static int atomic_read(atomic_t *a) { return a->value; }
static void atomic_set(atomic_t *a,int v) { a->value=v; }
static u64 ktime_get_ns(void) { static u64 n; return ++n; }
static unsigned long jiffies;
static int init_user_ns;
static int ns_capable(void *ns,int cap) { return authorized; }
static void *allocate(size_t n,int zero) {
    void *p; CHECK(!rcu_depth && !irqs_disabled);
    if (fail_alloc) { --fail_alloc; return NULL; }
    p=malloc(n); CHECK(p); memset(p,zero?0:0xa5,n); ++live_allocs; return p;
}
static void *kmalloc(size_t n,int f) { return allocate(n,0); }
static void *kvmalloc(size_t n,int f) { return allocate(n,0); }
static void *kzalloc(size_t n,int f) { return allocate(n,1); }
static void *kcalloc(size_t n,size_t s,int f) { return allocate(n*s,1); }
static void *kvcalloc(size_t n,size_t s,int f) { return allocate(n*s,1); }
static void *vmalloc(size_t n) { return allocate(n,0); }
static void *vzalloc(size_t n) { return allocate(n,1); }
static void kvfree(void *p) { if (p) { --live_allocs; free(p); } }
#define kfree kvfree
#define vfree kvfree
static int copy_to_user(void *d,const void *s,size_t n) {
    CHECK(!irqs_disabled && !rcu_depth);
    ++copy_count;
    if (copy_count==copy_fail_at || (unsigned long)d<4096) return 1;
    memcpy(d,s,n); return 0;
}
static int copy_from_user(void *d,const void *s,size_t n) {
    CHECK(!irqs_disabled && !rcu_depth);
    if ((unsigned long)s<4096) return 1; memcpy(d,s,n); return 0;
}
static long strncpy_from_user(char *d,const char *s,size_t n) {
    size_t i; if ((unsigned long)s<4096) return -EFAULT;
    for(i=0;i<n;i++) { d[i]=s[i]; if(!s[i]) return i; } return n;
}
struct task_struct {
    int pid, refs; unsigned long tls;
    struct task_struct *group_leader, *next;
};
struct pid { int id; };
static struct task_struct tasks[1026];
static struct task_struct *pid_one_override;
static int exit_during_access;
static unsigned char remote_mem[16384];
static int find_pid_by_name(const char *s) { return s[0]?1:-EINVAL; }
static struct pid pid_ref;
static struct pid *find_get_pid(int pid) {
    if(pid<1 || pid>66) return NULL; pid_ref.id=pid; return &pid_ref;
}
static struct task_struct *get_pid_task(struct pid *p,int type) {
    if(p->id==1 && pid_one_override) { ++pid_one_override->refs; return pid_one_override; }
    ++tasks[p->id-1].refs; return &tasks[p->id-1];
}
static void put_pid(struct pid *p) {}
static void get_task_struct(struct task_struct *t) { ++t->refs; }
static void put_task_struct(struct task_struct *t) { CHECK(t->refs>1); --t->refs; }
static int pid_alive(struct task_struct *t) { return t->pid>0; }
static int task_pid_vnr(struct task_struct *t) { return t->pid; }
#define for_each_thread(leader,t) for((t)=(leader);(t);(t)=(t)->next)
static int access_process_vm(struct task_struct *t,unsigned long a,void *b,int n,unsigned f) {
    int actual=n;
    CHECK(!rcu_depth && !irqs_disabled && t->refs>1);
    ++access_calls; last_gup_flags=f;
    if(exit_during_access) {
        t->pid=0;
        if(exit_during_access==1) return 0; /* exit before get_task_mm */
        /* An already pinned page remains valid until this read releases it. */
    }
    if (a<4096 || a-4096>sizeof(remote_mem) || n>sizeof(remote_mem)-(a-4096))
        return 0;
    if(short_access) actual=n>1?n/2:0;
    if(f&FOLL_WRITE) memcpy(remote_mem+a-4096,b,actual);
    else memcpy(b,remote_mem+a-4096,actual);
    return actual;
}
struct inode { int unused; };
struct file { void *private_data; };
static struct { struct { unsigned long size; } core_layout; } module;
#define THIS_MODULE (&module)
struct pt_regs { unsigned long regs[31], sp, pc; };
struct perf_event_attr { unsigned long bp_addr; int disabled,bp_len,bp_type; };
struct perf_event { int live; };
struct perf_sample_data { int unused; };
typedef void (*perf_overflow_handler_t)(struct perf_event *,struct perf_sample_data *,struct pt_regs *);
typedef struct perf_event *(*register_user_hw_bp_fn)(struct perf_event_attr *,
    perf_overflow_handler_t,void *,struct task_struct *);
typedef void (*unregister_hw_bp_fn)(struct perf_event *);
static register_user_hw_bp_fn g_register_user_hw_bp;
static unregister_hw_bp_fn g_unregister_hw_bp;
#define HW_BREAKPOINT_LEN_4 4
#define HW_BREAKPOINT_RW 3
#define HW_BREAKPOINT_R 1
#define HW_BREAKPOINT_W 2
#define HW_BREAKPOINT_X 4
static void hw_breakpoint_init(struct perf_event_attr *a) { memset(a,0,sizeof(*a)); }
static int enum_modules(struct task_struct *t,char *b,size_t n) { b[0]=0; return 0; }
static unsigned long get_module_base(struct task_struct *t,const char *s) { return 4096; }
"""


TESTS = r"""
static void reset(void) {
    int i;
#ifdef CONFIG_HAVE_HW_BREAKPOINT
    hw_bp_cleanup();
#endif
    if(test_context.task) put_task_struct(test_context.task);
    memset(&test_context,0,sizeof(test_context));
    CHECK(!live_allocs && !irqs_disabled && !rcu_depth);
    authorized=1; short_access=0; access_calls=0; copy_fail_at=0; copy_count=0;
    pid_one_override=NULL; exit_during_access=0;
    for(i=0;i<1026;i++) {
        CHECK(tasks[i].refs==0 || tasks[i].refs==1);
        tasks[i].pid=i+1; tasks[i].refs=1; tasks[i].group_leader=&tasks[0];
        tasks[i].next=NULL;
    }
    for(i=0;i<sizeof(remote_mem);i++) remote_mem[i]=(unsigned char)(i%251);
}
static void test_packets(void) {
    mem_packet_t p={0}; unsigned long result=0; int n;
    p.pid=1; p.addr=4096; p.size=16;
    CHECK(sizeof(p)==168 && sizeof(mem_batch_item_t)==24);
    CHECK(qw_validate_packet(&p)==0);
    memset(p.pkg_name,'x',sizeof(p.pkg_name));
    CHECK(qw_validate_packet(&p)==-EINVAL);
    memset(p.pkg_name,0,sizeof(p.pkg_name));
    for(n=-3;n<13;n++) {
        p.offset_count=n;
        if(n<0 || n>8) {
            CHECK(qw_resolve_address(&tasks[0],&p,1,&result)==-EINVAL);
        }
    }
    CHECK(access_calls==0);
    p.offset_count=1; p.addr=~0UL-1; p.offsets[0]=4;
    CHECK(qw_resolve_address(&tasks[0],&p,0,&result)==-EOVERFLOW);
    CHECK(!qw_range_valid(TASK_SIZE_MAX-4,8));
    CHECK(!qw_range_valid(~0UL-7,16));
    CHECK(!qw_range_valid(4096,0));
    CHECK(qw_range_valid(TASK_SIZE_MAX-4,4));
}
static void test_memory(void) {
    mem_packet_t p={0}; unsigned char out[8192]; long r;
    p.pid=1; p.addr=4096+4000; p.size=sizeof(out); p.user_buf=(unsigned long)out;
    r=device_ioctl(NULL,CMD_READ_MEM,(unsigned long)&p);
    CHECK(r==0 && !memcmp(out,remote_mem+4000,sizeof(out)));
    memset(out,0x77,sizeof(out));
    CHECK(device_ioctl(NULL,CMD_WRITE_MEM,(unsigned long)&p)==0);
    CHECK(last_gup_flags==FOLL_WRITE && remote_mem[4000]==0x77);
    short_access=1;
    CHECK(device_ioctl(NULL,CMD_READ_MEM,(unsigned long)&p)==-EFAULT);
    short_access=0;
    fail_alloc=1;
    CHECK(device_ioctl(NULL,CMD_READ_MEM,(unsigned long)&p)==-ENOMEM);
    CHECK(!live_allocs && tasks[0].refs==2);
}
static void test_pointer_chain(void) {
    mem_packet_t p={0}; unsigned long a=5000,b=6000; unsigned char out[4];
    memcpy(remote_mem,&a,8); memcpy(remote_mem+5000-4096+16,&b,8);
    p.pid=1; p.addr=4096; p.size=4; p.offset_count=2;
    p.offsets[0]=16; p.offsets[1]=32; p.user_buf=(unsigned long)out;
    CHECK(device_ioctl(NULL,CMD_READ_PTR,(unsigned long)&p)==0);
    CHECK(!memcmp(out,remote_mem+6000-4096+32,4));
    p.offset_count=9;
    CHECK(device_ioctl(NULL,CMD_READ_PTR,(unsigned long)&p)==-EINVAL);
    CHECK(tasks[0].refs==2);
}
static void test_batch(void) {
    mem_batch_packet_t p={0}; mem_batch_item_t item[3]={{0}};
    unsigned char out[128]; int i;
    p.pid=1; p.count=3; p.item_size=sizeof(item[0]); p.out_size=sizeof(out);
    p.items_buf=(unsigned long)item; p.out_buf=(unsigned long)out;
    item[0].addr=4096+4090; item[0].size=20; item[0].out_offset=16;
    item[1].addr=1; item[1].size=8; item[1].out_offset=48;
    item[2].addr=4096; item[2].size=32; item[2].out_offset=0xffffffffU;
    memset(out,0xcc,sizeof(out));
    CHECK(device_ioctl(NULL,CMD_READ_BATCH,(unsigned long)&p)==0);
    CHECK(item[0].status==0 && item[1].status==-EFAULT && item[2].status==-EINVAL);
    for(i=0;i<sizeof(out);i++)
        CHECK(out[i]==(i>=16 && i<36?remote_mem[4090+i-16]:0));
    short_access=1;
    CHECK(device_ioctl(NULL,CMD_READ_BATCH,(unsigned long)&p)==0);
    CHECK(item[0].status==-EFAULT);
    for(i=0;i<sizeof(out);i++) CHECK(out[i]==0);
    CHECK(!live_allocs && tasks[0].refs==2);
}
static void test_binding(void) {
    struct file second={0}; mem_packet_t p={0}; qw_caps_t caps={0};
    unsigned char out[8]; int before;
    CHECK(device_ioctl(&test_file,CMD_GET_CAPS,(unsigned long)&caps)==0);
    CHECK(caps.abi_version==2 && (caps.features&QW_FEATURE_BIND_TARGET));
    p.addr=4096; p.size=8; p.user_buf=(unsigned long)out;
    CHECK(device_ioctl(&test_file,CMD_READ_MEM,(unsigned long)&p)==-ENOTCONN);
    p.pid=1;
    copy_fail_at=copy_count+1;
    CHECK(device_ioctl(&test_file,CMD_BIND_TARGET,(unsigned long)&p)==-EFAULT);
    CHECK(!test_context.task && tasks[0].refs==1);
    copy_fail_at=0;
    CHECK(device_ioctl(&test_file,CMD_BIND_TARGET,(unsigned long)&p)==0);
    CHECK(test_context.task==&tasks[0] && tasks[0].refs==2);
    p.pid=0;
    CHECK(device_ioctl(&test_file,CMD_READ_MEM,(unsigned long)&p)==0);
    CHECK(device_open(NULL,&second)==0);
    p.pid=2;
    CHECK(device_ioctl(&second,CMD_BIND_TARGET,(unsigned long)&p)==0);
    CHECK(((struct qw_file*)second.private_data)->task==&tasks[1]);
    CHECK(test_context.task==&tasks[0]);
    p.pid=0;
    CHECK(device_ioctl(&second,CMD_READ_MEM,(unsigned long)&p)==0);
    CHECK(device_release(NULL,&second)==0 && tasks[1].refs==1 && !live_allocs);
    before=access_calls; tasks[0].pid=0;
    CHECK(device_ioctl(&test_file,CMD_READ_MEM,(unsigned long)&p)==-ESRCH);
    CHECK(access_calls==before); tasks[0].pid=1;
}
static void test_auth_and_targets(void) {
    mem_packet_t p={0}; unsigned char out[8];
    struct task_struct *a,*b;
    p.pid=1; p.addr=4096; p.size=8; p.user_buf=(unsigned long)out;
    authorized=0;
    CHECK(device_ioctl(NULL,CMD_READ_MEM,(unsigned long)&p)==-EPERM);
    CHECK(access_calls==0);
    authorized=1;
    a=get_target_task(1,""); b=get_target_task(2,"");
    CHECK(a==&tasks[0] && b==&tasks[1] && a->refs==2 && b->refs==2);
    put_task_struct(b); CHECK(a->refs==2); put_task_struct(a);
    memset(p.pkg_name,'x',sizeof(p.pkg_name));
    CHECK(device_ioctl(NULL,CMD_READ_MEM,(unsigned long)&p)==-EINVAL);
}
static void test_exit_and_pid_reuse(void) {
    mem_packet_t p={0}; unsigned char out[8]; int before;
    struct task_struct *other;
    p.pid=1; p.addr=4096; p.size=8; p.user_buf=(unsigned long)out;
    CHECK(!device_ioctl(&test_file,CMD_BIND_TARGET,(unsigned long)&p));
    other=qw_context_target(&test_context,2,"",false);
    CHECK(other==&tasks[1] && test_context.task==&tasks[0]);
    put_task_struct(other);
    exit_during_access=1;
    CHECK(device_ioctl(&test_file,CMD_READ_MEM,(unsigned long)&p)==-EFAULT);
    exit_during_access=0;
    tasks[2].pid=1; pid_one_override=&tasks[2]; /* new object, same numeric PID */
    before=access_calls;
    CHECK(device_ioctl(&test_file,CMD_READ_MEM,(unsigned long)&p)==-ESRCH);
    CHECK(access_calls==before && tasks[2].refs==1);
    CHECK(!device_ioctl(&test_file,CMD_BIND_TARGET,(unsigned long)&p));
    CHECK(test_context.task==&tasks[2] && tasks[0].refs==1);
    exit_during_access=2;
    CHECK(!device_ioctl(&test_file,CMD_READ_MEM,(unsigned long)&p));
    exit_during_access=0;
    CHECK(!memcmp(out,remote_mem,8));
    CHECK(device_ioctl(&test_file,CMD_READ_MEM,(unsigned long)&p)==-ESRCH);
    CHECK(!live_allocs && tasks[2].refs==2);
}
#ifdef CONFIG_HAVE_HW_BREAKPOINT
static struct perf_event events[64];
static int registration_count, fail_registration_at, unregistered, fire_registration;
static struct perf_event *register_event(struct perf_event_attr *a,
    perf_overflow_handler_t cb,void *ctx,struct task_struct *t) {
    struct pt_regs r={0}; struct perf_event *e;
    CHECK(g_hw_bp_lock.held && !rcu_depth && !irqs_disabled && t->refs>1);
    if(++registration_count==fail_registration_at) return ERR_PTR(-ENOMEM);
    e=&events[registration_count-1]; CHECK(!e->live); e->live=1;
    r.pc=a->bp_addr; r.regs[19]=0x123456; r.regs[8]=7;
    if(fire_registration) cb(e,NULL,&r);
    return e;
}
static void unregister_event(struct perf_event *e) {
    CHECK(g_hw_bp_lock.held && e->live); e->live=0; ++unregistered;
}
static void test_hwbp(void) {
    hw_bp_cfg_t cfg={0}; mem_packet_t p={0}; decoder_snap_t out[4];
    int i; struct pt_regs r={0};
    g_register_user_hw_bp=register_event; g_unregister_hw_bp=unregister_event;
    cfg.bp_addr=4096; cfg.mode=3; cfg.reg_count=1; cfg.regs[0]=19;
    registration_count=0; fail_registration_at=0; fire_registration=1;
    CHECK(hw_bp_setup(&tasks[0],4096,0,0,3,&cfg,&test_context)==0);
    CHECK(g_dec_count==1 && g_dec_snaps[0].mode==3);
    CHECK(g_dec_snaps[0].regs[0]==0x123456);
    for(i=1;i<8;i++) CHECK(!g_dec_snaps[0].regs[i]);
    p.pid=1; p.size=4; p.user_buf=(unsigned long)out;
    copy_fail_at=copy_count+1;
    CHECK(device_ioctl(NULL,CMD_READ_DEC_SNAP,(unsigned long)&p)==-EFAULT);
    CHECK(!g_dec_lock.held && !irqs_disabled && !live_allocs);
    copy_fail_at=0;
    CHECK(device_ioctl(NULL,CMD_READ_DEC_SNAP,(unsigned long)&p)==0);
    CHECK(p.offset_count==1 && out[0].regs[0]==0x123456);
    p.user_buf=(unsigned long)&cfg; cfg.regs[0]=31;
    CHECK(device_ioctl(NULL,CMD_SET_HOOK_CFG,(unsigned long)&p)==-EINVAL);
    CHECK(cfg.ret==-EINVAL && g_hw_bp_active);
    hw_bp_cleanup();
    CHECK(!g_dec_count && !g_hw_bp_active);
    cfg.regs[0]=19; tasks[0].next=&tasks[1];
    registration_count=0; unregistered=0; fail_registration_at=2;
    CHECK(hw_bp_setup(&tasks[0],4096,0,0,2,&cfg,&test_context)==-ENOMEM);
    CHECK(unregistered==1 && !g_hw_bp_event_count && !g_dec_count);
    CHECK(tasks[0].refs==2 && tasks[1].refs==1);
    for(i=0;i<1025;i++) tasks[i].next=&tasks[i+1];
    registration_count=0;
    CHECK(hw_bp_setup(&tasks[0],4096,0,0,2,&cfg,&test_context)==-E2BIG);
    CHECK(!registration_count);
    for(i=0;i<1026;i++) CHECK(tasks[i].refs==(i==0?2:1));
}
#else
static void test_no_hwbp(void) {
    mem_packet_t p={0}; hw_bp_cfg_t cfg={0};
    p.pid=1; p.size=1; p.user_buf=(unsigned long)&cfg;
    CHECK(device_ioctl(NULL,CMD_READ_DEC_SNAP,(unsigned long)&p)==-EOPNOTSUPP);
    CHECK(device_ioctl(NULL,CMD_SET_HOOK_CFG,(unsigned long)&p)==-EOPNOTSUPP);
    CHECK(cfg.ret==-EOPNOTSUPP);
}
#endif
int main(void) {
    reset(); test_packets();
    reset(); test_memory();
    reset(); test_pointer_chain();
    reset(); test_batch();
    reset(); test_auth_and_targets();
    reset(); test_binding();
    reset(); test_exit_and_pid_reuse();
    reset();
#ifdef CONFIG_HAVE_HW_BREAKPOINT
    test_hwbp();
#else
    test_no_hwbp();
#endif
    reset();
    printf("PASS FD driver fault injection\n");
    return 0;
}
"""


def harness():
    wire = SOURCE[SOURCE.index("typedef struct {\n    int pid;"):
                  SOURCE.index("/* ---------- 内核版本兼容:")]
    types = SOURCE[SOURCE.index("typedef struct decoder_snap {"):
                   SOURCE.index("/* ---------- HW Breakpoint 状态")]
    globals_ = SOURCE[SOURCE.index("struct qw_file {"):
                      SOURCE.index("/* 解码器 hook 快照：")]
    state = SOURCE[SOURCE.index("#ifdef CONFIG_HAVE_HW_BREAKPOINT\nstatic DEFINE_MUTEX"):
                   SOURCE.index("/* Remote memory access")]
    helpers = "\n".join(function(x) for x in (
        "qw_range_valid", "qw_access_memory", "manual_read_memory",
        "manual_write_memory", "qw_validate_packet", "qw_resolve_address",
        "get_target_task", "qw_remember_target", "qw_context_target"))
    hw = SOURCE[SOURCE.index("#ifdef CONFIG_HAVE_HW_BREAKPOINT\n/* ==="):
                SOURCE.index("#endif /* CONFIG_HAVE_HW_BREAKPOINT */")
                + len("#endif /* CONFIG_HAVE_HW_BREAKPOINT */")]
    capture = SOURCE[SOURCE.index("static struct {\n    spinlock_t lock;"):
                     SOURCE.index("static void qw_capture_store")]
    context = "static struct qw_file test_context;\nstatic struct file test_file={&test_context};"
    tests = TESTS.replace("device_ioctl(NULL,", "device_ioctl(&test_file,")
    return "\n".join((PRELUDE, wire, types, globals_, context, state, helpers, hw,
                      capture, function("device_ioctl"), function("device_open"), function("device_release"), tests))


class FDDriverRegression(unittest.TestCase):
    def test_no_manual_page_walking_or_global_task_cache(self):
        for old in ("pte_offset_map(", "pfn_to_page(", "g_cached_task",
                    "strncpy_from_user(buf,", "THIS_MODULE->name,"):
            self.assertNotIn(old, SOURCE)

    def test_capture_unchanged(self):
        # Values generated from the user's original file, before any changes.
        from_capture = SOURCE.index("/* ================= netfilter 抓包实现")
        to_ioctl = SOURCE.index("/* ---------- ioctl 处理")
        cases = SOURCE[SOURCE.index("    case CMD_CAPTURE_START:"):
                       SOURCE.index("    case CMD_READ_DEC_SNAP:")]
        digest = hashlib.sha256(
            (SOURCE[from_capture:to_ioctl] + cases).encode()).hexdigest()
        self.assertEqual(digest, "ea365e1384aa193ce5df70d86d22e29577fe3b89a688a534dcc71f6c77f6d861")

    def test_fault_injection_with_and_without_hwbp(self):
        cc = os.environ.get("QW_TEST_CC") or shutil.which("clang") or shutil.which("gcc")
        if not cc:
            self.skipTest("C compiler unavailable; GKI compilation still required")
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "fd_faults.c"
            source.write_text(harness(), encoding="utf-8")
            for enabled in (False, True):
                with self.subTest(hwbp=enabled):
                    flags = ["-std=gnu11", "-Werror=implicit-function-declaration"]
                    if enabled:
                        flags.append("-DCONFIG_HAVE_HW_BREAKPOINT=1")
                    if os.name == "nt":
                        subprocess.run([cc, "--target=x86_64-linux-gnu",
                                        "-fsyntax-only", *flags, str(source)], check=True)
                    else:
                        out = Path(tmp) / ("faults-hwbp" if enabled else "faults-no-hwbp")
                        subprocess.run([cc, *flags, "-fsanitize=address,undefined",
                                        "-fno-omit-frame-pointer", "-g",
                                        str(source), "-o", str(out)], check=True)
                        subprocess.run([str(out)], check=True)


if __name__ == "__main__":
    unittest.main()
