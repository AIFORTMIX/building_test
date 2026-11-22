#include "qingwei_api.h"
#include "qingwei_api.h" // the complete header must support repeated inclusion
#include "overlay_navigation.h"
#include "memory_search.h"
#include <atomic>
#include <cassert>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <thread>
#include <fcntl.h>

static_assert(sizeof(mem_packet_t) == 168);
static_assert(offsetof(mem_packet_t, addr) == 72 && offsetof(mem_packet_t, user_buf) == 160);
static_assert(sizeof(mem_batch_item_t) == 24 && sizeof(mem_batch_packet_t) == 104);
static_assert(sizeof(qw_capture_read_t) == 24 && sizeof(qw_capture_stats_t) == 48);
static_assert(sizeof(hw_bp_cfg_t) == 56 && sizeof(decoder_snap_t) == 88);
static_assert(sizeof(tls_pac_packet_t) == 96 && sizeof(qw_caps_t) == 32);
static_assert(CMD_READ_MEM == 0xc0a85102U && CMD_CAPTURE_STOP == 0x510fU);

static int nextFd;
static int legacy;
static bool legacyNoBatch, badBatchReply, badSnapshotCount, badSnapshotRegs;
static int replyHookStatus;
static unsigned capItems = QW_BATCH_MAX_ITEMS, capBytes = QW_BATCH_MAX_SIZE;
static unsigned maxItems[64], maxBytes[64];
static int legacyFd[64], lastPid, lastSnapshotPid;
static unsigned long failCommand;
static std::atomic<int> calls{0}, inside{0};
static std::atomic<bool> closed[64];
extern "C" int __wrap_open(const char*, int flags, ...) {
    assert(flags & O_CLOEXEC);
    maxItems[nextFd] = capItems; maxBytes[nextFd] = capBytes;
    legacyFd[nextFd] = legacy;
    return nextFd++;
}
extern "C" int __wrap_close(int fd) {
    assert(inside.load() == 0); closed[fd].store(true); return 0;
}
extern "C" int __wrap_fcntl(int fd, int op, ...) {
    assert(!closed[fd].load() && op == F_DUPFD_CLOEXEC);
    maxItems[nextFd] = maxItems[fd]; maxBytes[nextFd] = maxBytes[fd];
    legacyFd[nextFd] = legacyFd[fd];
    return nextFd++;
}
extern "C" int __wrap_ioctl(int fd, unsigned long command, ...) {
    assert(!closed[fd].load()); ++calls;
    va_list args; va_start(args, command); void* arg = va_arg(args, void*); va_end(args);
    if (command == failCommand) { errno = EIO; return -1; }
    if (command == CMD_GET_CAPS) {
        if (legacy) {
            if (legacy == 2) {
                mem_packet_t oldPacket;
                memcpy(&oldPacket, arg, sizeof(oldPacket));
                assert(!oldPacket.pid && !oldPacket.pkg_name[0]);
            }
            errno = legacy == 2 ? ESRCH : ENOTTY; return -1;
        }
        auto* caps = static_cast<qw_caps_t*>(arg); *caps = {};
        caps->abi_version = QW_ABI_VERSION;
        caps->features = QW_FEATURE_BATCH_LARGE | QW_FEATURE_PER_FILE |
                         QW_FEATURE_BIND_TARGET | QW_FEATURE_HWBP;
        caps->max_items = capItems; caps->max_out_size = capBytes;
        caps->max_item_size = capBytes;
    } else if (command == CMD_GET_MODULE_INFO) {
        auto* info = static_cast<module_info_t*>(arg); *info = {};
    } else if (command == CMD_READ_BATCH) {
        if (legacyFd[fd] && legacyNoBatch) { errno = ENOTTY; return -1; }
        auto* p = static_cast<mem_batch_packet_t*>(arg);
        assert(p->item_size == 24 && p->count <= maxItems[fd] && p->out_size <= maxBytes[fd]);
        auto* items = reinterpret_cast<mem_batch_item_t*>((uintptr_t)p->items_buf);
        memset(reinterpret_cast<void*>((uintptr_t)p->out_buf), 0x5a, p->out_size);
        for (unsigned i = 0; i < p->count; ++i) items[i].status = i == 1 ? -EFAULT : 0;
        if (badBatchReply) items[0].out_offset = UINT32_MAX;
    } else if (command == CMD_READ_MEM || command == CMD_READ_PTR) {
        auto* p = static_cast<mem_packet_t*>(arg);
        assert(sizeof(*p) == 168 && p->offset_count >= 0 && p->offset_count <= 8);
        lastPid = p->pid;
        ++inside; std::this_thread::yield();
        memset(reinterpret_cast<void*>((uintptr_t)p->user_buf), 0x3c, p->size);
        --inside;
    } else if (command == CMD_GET_BASE) {
        auto* p = static_cast<mem_packet_t*>(arg); uint64_t base = 0x12340000;
        memcpy(reinterpret_cast<void*>((uintptr_t)p->user_buf), &base, sizeof(base));
    } else if (command == CMD_BIND_TARGET) {
        auto* p = static_cast<mem_packet_t*>(arg);
        if (!p->pid) p->pid = 123;
    } else if (command == CMD_SET_HOOK_CFG) {
        auto* p = static_cast<mem_packet_t*>(arg);
        reinterpret_cast<hw_bp_cfg_t*>((uintptr_t)p->user_buf)->ret = replyHookStatus;
    } else if (command == CMD_CAPTURE_STOP || command == CMD_CLEAR_HOOK) {
        assert(!arg);
    } else if (command == CMD_CAPTURE_READ) {
        auto* p = static_cast<qw_capture_read_t*>(arg); p->count_out = 0;
    } else if (command == CMD_READ_DEC_SNAP) {
        auto* p = static_cast<mem_packet_t*>(arg);
        lastSnapshotPid = p->pid;
        p->offset_count = badSnapshotCount ? (int)p->size + 1 : badSnapshotRegs ? 1 : 0;
        if (badSnapshotRegs)
            reinterpret_cast<decoder_snap_t*>((uintptr_t)p->user_buf)[0].reg_count = 9;
    } else { assert(false && "unexpected command"); }
    return 0;
}

int main() {
    qingwei::MemorySearch search;
    assert(!search.Start(5, 5, 0, false));
    assert(search.Start(0x1000, UINT64_MAX, 0, false));
    unsigned searchCalls = 0;
    search.Step([&](auto*, unsigned, void*, size_t) { ++searchCalls; });
    assert(searchCalls > 0 && searchCalls <= 4 && search.next <= 0x1000 + 256 * 1024);
    assert(search.active && search.results.empty());
    auto readable = [](auto* items, unsigned count, void* data, size_t bytes) {
        memset(data, 0, bytes); for (unsigned i=0; i<count; ++i) items[i].status = 0;
    };
    search.Start(0x1000, 0x1000000, 0, false);
    search.Step(readable);
    assert(search.limited && !search.active && search.results.size() == search.MaxResults);
    search.Start(UINT64_MAX - 16, UINT64_MAX, 0, false);
    search.Step(readable);
    assert(!search.active && search.next == UINT64_MAX && search.results.size() == 4);
    for (bool member : {false, true}) for (bool dev : {false, true}) for (bool loading : {false, true}) {
        auto nav = qingwei::VisibleMenu(member, dev, loading);
        assert(nav.Contains(PAGE_HOME) && nav.Contains(PAGE_ADVANCED) && nav.Contains(PAGE_SETTINGS));
        if (!member || loading) {
            assert(nav.count == 4 && !nav.Contains(PAGE_DRAW) && !nav.Contains(PAGE_DEBUG));
            assert(qingwei::AccessiblePage(PAGE_NETWORK, member, dev, loading) == PAGE_HOME);
        } else assert(nav.Contains(PAGE_DEBUG) == dev);
    }
    int fd = qingwei_open(); assert(fd == 0); // fd zero is a valid connection
    int copy = qingwei_dup(fd); assert(copy == 1);
    unsigned char buffer[8192]; memset(buffer, 0xff, sizeof(buffer));
    assert(qingwei_get_base(fd, 123, "target", "libtest.so") == 0x12340000);
    assert(!qingwei_get_base(fd, 123, "target", nullptr) && errno == EINVAL);
    assert(qingwei_read_mem(fd, 1, nullptr, 4) < 0 && errno == EINVAL);
    assert(qingwei_read_ptr(fd, 1, nullptr, 1, buffer, 4) < 0 && errno == EINVAL);
    assert(qingwei_set_hook(fd, 123, 4, 2, nullptr, 1) == -EINVAL);
    assert(qingwei_set_hook(fd, 123, 4, 2, nullptr, -1) == -EINVAL);
    assert(!qingwei_read_string(fd, 1, reinterpret_cast<char*>(buffer), 1) && !buffer[0]);
    int before = calls.load(); failCommand = CMD_READ_MEM;
    assert(qingwei_read_pointer_pkg(fd, "target", 1) == 0 && errno == EIO);
    assert(calls.load() == before + 1); failCommand = 0;
    errno = EIO; assert(!qingwei_read_mem(fd, 1, buffer, 4) && !errno && buffer[0] == 0x3c);
    qingwei_batch_item_t items[3]{};
    for (unsigned i = 0; i < 3; ++i) { items[i].addr = 1; items[i].size = 4; items[i].out_offset = i * 4; }
    assert(!qingwei_read_batch_pkg(fd, "target", items, 3, buffer, 12));
    assert(items[0].status == 0 && items[1].status == -EFAULT && items[2].status == 0);
    failCommand = CMD_READ_BATCH;
    assert(qingwei_read_batch_pkg(fd, "target", items, 3, buffer, 12) < 0 && errno == EIO);
    for (auto& item : items) assert(item.status == -EIO);
    for (unsigned i = 0; i < 12; ++i) assert(!buffer[i]); failCommand = 0;
    assert(!qingwei_capture_stop(fd) && !qingwei_clear_hook(fd));
    uint32_t count = 123;
    assert(qingwei_read_dec_snap(fd, nullptr, 4, &count) < 0 && !count);
    legacy = true; int old = qingwei_open(); legacy = false;
    items[0].size = sizeof(buffer); items[0].out_offset = 0;
    assert(!qingwei_read_batch_pkg(old, "target", items, 1, buffer, sizeof(buffer)));
    assert(!items[0].status && buffer[8191] == 0x3c); qingwei_close(old);
    legacy = 2; old = qingwei_open(); legacy = 0;
    assert(old >= 0); qingwei_close(old); // legacy unknown-command target-lookup bug
    std::atomic<bool> started{false};
    std::thread reader([&] {
        unsigned char local[16];
        for (int i = 0; i < 10000; ++i) {
            if (qingwei_read_mem(fd, 1, local, sizeof(local)) < 0) { assert(errno == EBADF); break; }
            started.store(true);
        }
    });
    while (!started.load()) std::this_thread::yield();
    qingwei_close(fd); reader.join();
    assert(!qingwei_read_mem(copy, 1, buffer, 4)); qingwei_close(copy);
    assert(qingwei_read_mem(copy, 1, buffer, 4) < 0 && errno == EBADF);
    int bound = qingwei_open();
    assert(!qingwei_bind_target(bound, 222, "target"));
    int boundCopy = qingwei_dup(bound);
    assert(!qingwei_read_mem(boundCopy, 1, buffer, 4) && lastPid == 222);
    assert(!qingwei_bind_target(bound, 333, "target"));
    assert(!qingwei_read_mem(boundCopy, 1, buffer, 4) && lastPid == 333);
    int register19 = 19;
    assert(!qingwei_set_hook(bound,444,4,2,&register19,1));
    assert(!qingwei_read_mem(boundCopy,1,buffer,4) && lastPid == 333);
    qingwei_dec_snap_t separateSnapshot{};
    assert(!qingwei_read_dec_snap(boundCopy,&separateSnapshot,1,&count) && lastSnapshotPid == 444);
    replyHookStatus = -EOPNOTSUPP;
    assert(qingwei_set_hook(bound, 333, 4, 2, nullptr, 0) == -EOPNOTSUPP && errno == EOPNOTSUPP);
    replyHookStatus = 0;
    qingwei_dec_snap_t snapshots[2]{};
    badSnapshotCount = true; count = 99;
    assert(qingwei_read_dec_snap(bound, snapshots, 2, &count) < 0 && !count && errno == EPROTO);
    badSnapshotCount = false; badSnapshotRegs = true;
    assert(qingwei_read_dec_snap(bound, snapshots, 2, &count) < 0 && !count && errno == EPROTO);
    badSnapshotRegs = false;
    assert(!qingwei_clear_hook(bound));
    assert(!qingwei_read_dec_snap(boundCopy,&separateSnapshot,1,&count) && lastSnapshotPid == 333);
    qingwei_batch_item_t small[5]{};
    for (unsigned i=0;i<5;i++) { small[i].addr=1; small[i].size=4; small[i].out_offset=i*4; }
    capItems=2; capBytes=8;
    int limited=qingwei_open(); capItems=QW_BATCH_MAX_ITEMS; capBytes=QW_BATCH_MAX_SIZE;
    assert(!qingwei_bind_target(limited, 444, "target"));
    before=calls.load();
    assert(!qingwei_read_batch_pkg(limited, "target", small, 5, buffer, 20));
    assert(calls.load()==before+3 && small[0].status==0 && small[1].status==-EFAULT);
    assert(small[2].status==0 && small[3].status==-EFAULT && small[4].status==0);
    assert(buffer[0]==0x5a && buffer[4]==0 && buffer[16]==0x5a);
    badBatchReply=true;
    assert(qingwei_read_batch_pkg(limited, "target", small, 5, buffer, 20)<0 && errno==EPROTO);
    for (unsigned i=0;i<20;i++) assert(!buffer[i]);
    badBatchReply=false;
    legacy=1; int oldBatch=qingwei_open(); legacy=0;
    before=calls.load();
    assert(!qingwei_read_batch_pkg(oldBatch, "target", small, 5, buffer, 20));
    assert(calls.load()==before+1); // one bounded legacy batch, not five scalar reads
    legacyNoBatch=true; before=calls.load();
    assert(!qingwei_read_batch_pkg(oldBatch, "target", small, 5, buffer, 20));
    assert(calls.load()==before+6);
    for (auto& item:small) assert(!item.status);
    legacyNoBatch=false;
    assert(qingwei_clear_hook(oldBatch)<0 && errno==EOPNOTSUPP);
    before=calls.load();
    assert(qingwei_set_hook(oldBatch,123,4,2,nullptr,0)==-EOPNOTSUPP);
    assert(qingwei_set_hw_bp(oldBatch,123,"target",4,0,0)==-EOPNOTSUPP);
    assert(calls.load()==before); // a v1 hook cannot be reliably removed
    qingwei_close(oldBatch); qingwei_close(limited);
    qingwei_close(boundCopy); qingwei_close(bound);
    puts("PASS client ABI, target binding, negotiated batching, legacy fallback, malformed replies and FD lifetime");
}
