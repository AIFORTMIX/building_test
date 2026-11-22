"""Run the real C client, and connect it to the extracted real driver dispatcher."""
import os
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import test_fd_driver as kernel

FIXTURE = kernel.ROOT / "tests/fd-client"

BRIDGE = r"""
static struct file bridge_files[64];
static struct file *bridge_fds[64];
static unsigned bridge_file_refs[64];
static unsigned bridge_next;
static struct perf_event bridge_events[1024];
static unsigned bridge_event_next;
static bool bridge_initialized;
static struct perf_event *bridge_register(struct perf_event_attr *a,
        perf_overflow_handler_t handler,void *context,struct task_struct *task) {
    struct pt_regs regs={0};
    struct perf_event *event=&bridge_events[bridge_event_next++];
    CHECK(bridge_event_next<=1024 && !event->live && !rcu_depth && !irqs_disabled);
    event->live=1;
    regs.pc=a->bp_addr; regs.regs[19]=4096+17;
    handler(event,NULL,&regs);
    return event;
}
static void bridge_unregister(struct perf_event *event) {
    CHECK(event->live); event->live=0;
}
int bridge_open(void) {
    unsigned fd=bridge_next++;
    if(!bridge_initialized) {
        unsigned i;
        for(i=0;i<1026;i++) {
            tasks[i].pid=i+1; tasks[i].refs=1; tasks[i].group_leader=&tasks[i];
        }
        for(i=0;i<sizeof(remote_mem);i++) remote_mem[i]=i%251;
        g_register_user_hw_bp=bridge_register; g_unregister_hw_bp=bridge_unregister;
        bridge_initialized=true;
    }
    CHECK(fd<64 && !device_open(NULL,&bridge_files[fd]));
    bridge_fds[fd]=&bridge_files[fd]; bridge_file_refs[fd]=1;
    return fd;
}
int bridge_dup(int fd) {
    unsigned copy=bridge_next++, index;
    CHECK(fd>=0 && bridge_fds[fd] && copy<64);
    index=bridge_fds[fd]-bridge_files;
    bridge_fds[copy]=bridge_fds[fd]; ++bridge_file_refs[index];
    return copy;
}
int bridge_close(int fd) {
    unsigned index;
    CHECK(fd>=0 && bridge_fds[fd]);
    index=bridge_fds[fd]-bridge_files;
    if(!--bridge_file_refs[index]) CHECK(!device_release(NULL,bridge_fds[fd]));
    bridge_fds[fd]=NULL;
    return 0;
}
long bridge_ioctl(int fd,unsigned int cmd,unsigned long arg) {
    CHECK(fd>=0 && bridge_fds[fd]);
    return device_ioctl(bridge_fds[fd],cmd,arg);
}
int bridge_live_allocations(void) { return live_allocs; }
int bridge_task_refs(int pid) { return tasks[pid-1].refs; }
void bridge_authorized(int enabled) { authorized=enabled; }
void bridge_short_access(int enabled) { short_access=enabled; }
void bridge_fail_next_copy(void) { copy_fail_at=copy_count+1; }
"""

INTEGRATION = r"""
#include "qingwei_api.h"
#include <cassert>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
extern "C" {
int bridge_open(void);
int bridge_dup(int);
int bridge_close(int);
long bridge_ioctl(int,unsigned int,unsigned long);
int bridge_live_allocations(void);
int bridge_task_refs(int);
void bridge_authorized(int);
void bridge_short_access(int);
void bridge_fail_next_copy(void);
int __wrap_open(const char*,int flags,...) { assert(flags&O_CLOEXEC); return bridge_open(); }
int __wrap_close(int fd) { return bridge_close(fd); }
int __wrap_fcntl(int fd,int op,...) { assert(op==F_DUPFD_CLOEXEC); return bridge_dup(fd); }
int __wrap_ioctl(int fd,unsigned long command,...) {
    va_list args; va_start(args,command); void *arg=va_arg(args,void*); va_end(args);
    long result=bridge_ioctl(fd,command,reinterpret_cast<unsigned long>(arg));
    if(result<0) { errno=-result; return -1; }
    errno=0; return result;
}
}
int main() {
    int fd=qingwei_open(); assert(fd>=0);
    qw_caps_t caps{}; assert(!qingwei_get_caps(fd,&caps));
    assert(caps.abi_version==2 && (caps.features&QW_FEATURE_BIND_TARGET));
    unsigned char bytes[8208]; memset(bytes,0x55,sizeof(bytes));
    assert(qingwei_read_mem(fd,4096,bytes,8)<0 && errno==ENOTCONN);
    assert(!qingwei_bind_target(fd,1,"target"));
    assert(!qingwei_read_mem(fd,4096+7,bytes,8));
    for(int i=0;i<8;i++) assert(bytes[i]==7+i);
    int copy=qingwei_dup(fd); assert(copy>=0);
    bridge_fail_next_copy();
    assert(qingwei_bind_target(copy,2,"other")<0 && errno==EFAULT);
    assert(!qingwei_read_mem(copy,4096+7,bytes,8));
    assert(bridge_task_refs(1)==2 && bridge_task_refs(2)==1);
    qingwei_batch_item_t items[2]{};
    items[0].addr=4096+16; items[0].size=8192;
    items[1].addr=1; items[1].size=4; items[1].out_offset=8192;
    assert(!qingwei_read_batch_pkg(copy,nullptr,items,2,bytes,sizeof(bytes)));
    assert(!items[0].status && items[1].status==-EFAULT);
    for(int i=0;i<8192;i++) assert(bytes[i]==(i+16)%251);
    for(int i=8192;i<sizeof(bytes);i++) assert(!bytes[i]);
    unsigned long offsets[1]={3}; uint64_t ptr=5000;
    assert(!qingwei_write_mem(fd,4096,&ptr,sizeof(ptr)));
    assert(!qingwei_read_ptr(copy,4096,offsets,1,bytes,4));
    for(int i=0;i<4;i++) assert(bytes[i]==(5003-4096+i)%251);
    int reg=19;
    bridge_fail_next_copy();
    assert(qingwei_set_hook(fd,1,4096,2,&reg,1)==-EFAULT);
    qingwei_hwbp_stats_t failedStats{};
    assert(!qingwei_get_hwbp_stats(fd,&failedStats) && !failedStats.active);
    assert(!qingwei_set_hook(fd,1,4096,2,&reg,1));
    qingwei_dec_snap_t snapshots[4]{}; uint32_t count=0;
    qingwei_close(fd); // worker duplicate keeps both target and hook alive
    assert(!qingwei_read_dec_snap(copy,snapshots,4,&count) && count==1);
    assert(snapshots[0].regs[0]==4096+17 && snapshots[0].mode==2);
    int other=qingwei_open(); assert(other>=0);
    assert(!qingwei_bind_target(other,2,"other"));
    assert(qingwei_clear_hook(other)<0 && errno==EPERM);
    assert(qingwei_set_hook(other,2,4096,2,&reg,1)==-EBUSY);
    assert(!qingwei_clear_hook(copy));
    qingwei_hwbp_stats_t stats{}; assert(!qingwei_get_hwbp_stats(copy,&stats) && !stats.active);
    bridge_short_access(1);
    assert(qingwei_read_mem(copy,4096,bytes,8)<0 && errno==EFAULT);
    for(int i=0;i<8;i++) assert(!bytes[i]);
    bridge_short_access(0);
    qingwei_close(copy); qingwei_close(other);
    assert(!bridge_live_allocations() && bridge_task_refs(1)==1 && bridge_task_refs(2)==1);
    bridge_authorized(0);
    assert(qingwei_open()<0 && errno==EPERM);
    assert(!bridge_live_allocations());
    puts("PASS actual client/driver protocol integration: bind, dup, batch, pointers, hooks, errors and lifetime");
}
"""


def bridge_source():
    return kernel.harness().split("static void reset(void) {", 1)[0] + BRIDGE


class FDClientRegression(unittest.TestCase):
    def test_staged_client_and_project_uapi(self):
        manifest = json.loads((FIXTURE / "workspace-source-sha256.json").read_text())
        for path, digest in manifest["sha256_lf"].items():
            data = (FIXTURE / Path(path).name).read_text(encoding="utf-8").encode()
            self.assertEqual(hashlib.sha256(data).hexdigest(), digest, path)
        project = (FIXTURE / "project_driver.c").read_text(encoding="utf-8")
        header = (FIXTURE / "qingwei_uapi.h").read_text(encoding="utf-8")
        embedded = project.split("/* BEGIN GENERATED UAPI */\n", 1)[1].split(
            "\n/* END GENERATED UAPI */", 1)[0]
        self.assertEqual(embedded.rstrip(), header.rstrip())

    def test_actual_client_and_driver_protocol(self):
        cc = os.environ.get("QW_TEST_CC") or shutil.which("clang") or shutil.which("gcc")
        cxx = os.environ.get("QW_TEST_CXX") or shutil.which("clang++") or shutil.which("g++")
        if not cc or not cxx:
            self.skipTest("C/C++ compilers unavailable")
        if os.name == "nt":
            self.skipTest("Run sanitizer executables on Android or Linux, not Windows")
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            flags = ["-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-g", "-O1"]
            client = out / "client.o"
            subprocess.run([cc, "-std=gnu11", *flags, "-I", str(FIXTURE), "-c",
                            str(FIXTURE / "qingwei_api.c"), "-o", str(client)], check=True)
            wraps = ["-Wl,--wrap=" + f for f in ("open", "close", "ioctl", "fcntl")]
            exe = out / "client-regression"
            subprocess.run([cxx, "-std=c++20", *flags, "-I", str(FIXTURE),
                            str(FIXTURE / "driver_client_tests.cpp"), str(client),
                            "-pthread", *wraps, "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True)
            source = out / "driver.c"
            source.write_text(bridge_source(), encoding="utf-8")
            driver = out / "driver.o"
            subprocess.run([cc, "-std=gnu11", *flags, "-DCONFIG_HAVE_HW_BREAKPOINT=1",
                            "-c", str(source), "-o", str(driver)], check=True)
            source = out / "integration.cpp"
            source.write_text(INTEGRATION, encoding="utf-8")
            exe = out / "client-driver-integration"
            subprocess.run([cxx, "-std=c++20", *flags, "-I", str(FIXTURE), str(source),
                            str(client), str(driver), "-pthread", *wraps, "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    unittest.main()
