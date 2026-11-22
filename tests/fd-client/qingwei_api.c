#include "qingwei_api.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <limits.h>
#include <pthread.h>
#define QINGWEI_BATCH_MAX_ITEMS QW_BATCH_MAX_ITEMS
#define QINGWEI_BATCH_MAX_SIZE QW_BATCH_MAX_SIZE
struct client_target {
    unsigned refs;
    int pid;
    char package[64];
};
struct client_fd {
    int fd;
    qw_caps_t caps;
    struct client_target *target;
    struct client_fd *next;
};
static pthread_rwlock_t fd_lock = PTHREAD_RWLOCK_INITIALIZER;
static struct client_fd *clients;
static struct client_fd *find_client(int fd) {
    struct client_fd *c;
    for (c = clients; c; c = c->next)
        if (c->fd == fd)
            return c;
    return NULL;
}
static void free_client(struct client_fd *c) {
    if (c->target && --c->target->refs == 0)
        free(c->target);
    free(c);
}
static int target_packet_command(unsigned long cmd) {
    return cmd == CMD_GET_BASE || cmd == CMD_READ_MEM || cmd == CMD_WRITE_MEM ||
           cmd == CMD_READ_PTR || cmd == CMD_SET_HW_BP || cmd == CMD_SET_BLR_ADDRS ||
           cmd == CMD_QUERY_SNAPSHOT || cmd == CMD_READ_TLS_PAC ||
           cmd == CMD_ENUM_MODULES || cmd == CMD_READ_DEC_SNAP ||
           cmd == CMD_SET_HOOK_CFG || cmd == CMD_BIND_TARGET;
}
static int do_ioctl(int fd, unsigned long cmd, void *pkt) {
    int ret, saved, changes_target = cmd == CMD_GET_BASE || cmd == CMD_BIND_TARGET ||
                                     cmd == CMD_SET_HW_BP || cmd == CMD_SET_HOOK_CFG;
    struct client_fd *c;
    if (changes_target)
        pthread_rwlock_wrlock(&fd_lock);
    else
        pthread_rwlock_rdlock(&fd_lock);
    c = find_client(fd);
    if (!c) {
        pthread_rwlock_unlock(&fd_lock);
        errno = EBADF;
        return -1;
    }
    if (target_packet_command(cmd) && pkt) {
        mem_packet_t *p = pkt;
        if (p->pid <= 0 && !p->pkg_name[0]) {
            p->pid = c->target->pid;
            memcpy(p->pkg_name, c->target->package, sizeof(p->pkg_name));
            if (p->pid <= 0 && !p->pkg_name[0] && !(c->caps.features & QW_FEATURE_PER_FILE)) {
                pthread_rwlock_unlock(&fd_lock);
                errno = ENOTCONN;
                return -1;
            }
        }
    } else if (cmd == CMD_READ_BATCH && pkt) {
        mem_batch_packet_t *p = pkt;
        if (p->pid <= 0 && !p->pkg_name[0]) {
            p->pid = c->target->pid;
            memcpy(p->pkg_name, c->target->package, sizeof(p->pkg_name));
        }
    }
    ret = ioctl(fd, cmd, pkt);
    saved = ret < 0 ? errno : 0;
    if (ret >= 0 && changes_target &&
        (cmd != CMD_SET_HOOK_CFG ||
         ((const hw_bp_cfg_t *)(uintptr_t)((const mem_packet_t *)pkt)->user_buf)->ret == 0)) {
        const mem_packet_t *p = pkt;
        c->target->pid = p->pid > 0 ? p->pid : 0;
        memcpy(c->target->package, p->pkg_name, sizeof(c->target->package));
    }
    pthread_rwlock_unlock(&fd_lock);
    errno = saved;
    return ret < 0 ? -1 : 0;
}

// --------------------------------------------------------------

int qingwei_open(void) {
    int fd = open(QINGWEI_DEVICE, O_RDWR | O_CLOEXEC);
    struct client_fd *c;
    /* Broken v1 dispatchers read a whole mem_packet_t for unknown commands.
     * Give that probe a complete zeroed packet, not a 32-byte object. */
    union {
        mem_packet_t packet;
        qw_caps_t caps;
        module_info_t info;
    } probe = {0};
    if (fd < 0)
        return -1;
    c = calloc(1, sizeof(*c));
    if (!c) {
        close(fd);
        errno = ENOMEM;
        return -1;
    }
    c->target = calloc(1, sizeof(*c->target));
    if (!c->target) {
        close(fd);
        free(c);
        errno = ENOMEM;
        return -1;
    }
    c->target->refs = 1;
    if (ioctl(fd, CMD_GET_CAPS, &probe) < 0) {
        int saved = errno;
        int known_legacy = 0;
        if (saved == ENOTTY || saved == ESRCH || saved == EINVAL) {
            memset(&probe, 0, sizeof(probe));
            known_legacy = ioctl(fd, CMD_GET_MODULE_INFO, &probe) == 0;
        }
        if (!known_legacy) {
            free_client(c);
            close(fd);
            errno = saved;
            return -1;
        }
#if UINTPTR_MAX < UINT64_MAX
        /* v1 has no compat_ioctl; a rebuilt 32-bit client needs the v2 module. */
        free_client(c);
        close(fd);
        errno = EPROTONOSUPPORT;
        return -1;
#endif
        /* Conservative legacy limits. Large items use the scalar read command. */
        c->caps.max_items = 256;
        c->caps.max_out_size = 1024 * 1024;
        c->caps.max_item_size = (uint32_t)sysconf(_SC_PAGESIZE);
    } else {
        c->caps = probe.caps;
        if (c->caps.abi_version != QW_ABI_VERSION || !c->caps.max_items ||
            c->caps.max_items > QW_BATCH_MAX_ITEMS || !c->caps.max_out_size ||
            c->caps.max_out_size > QW_BATCH_MAX_SIZE || !c->caps.max_item_size ||
            c->caps.max_item_size > QW_BATCH_MAX_SIZE) {
            free_client(c);
            close(fd);
            errno = EPROTO;
            return -1;
        }
    }
    c->fd = fd;
    pthread_rwlock_wrlock(&fd_lock);
    c->next = clients;
    clients = c;
    pthread_rwlock_unlock(&fd_lock);
    errno = 0;
    return fd;
}

void qingwei_close(int fd) {
    if (fd < 0)
        return;
    struct client_fd **link;
    pthread_rwlock_wrlock(&fd_lock);
    for (link = &clients; *link; link = &(*link)->next) {
        if ((*link)->fd == fd) {
            struct client_fd *c = *link;
            *link = c->next;
            close(fd);
            free_client(c);
            break;
        }
    }
    pthread_rwlock_unlock(&fd_lock);
}

int qingwei_get_caps(int fd, qw_caps_t *caps) {
    struct client_fd *c;
    int found;
    if (!caps) {
        errno = EINVAL;
        return -1;
    }
    pthread_rwlock_rdlock(&fd_lock);
    c = find_client(fd);
    found = c != NULL;
    if (c)
        *caps = c->caps;
    pthread_rwlock_unlock(&fd_lock);
    errno = found ? 0 : EBADF;
    return found ? 0 : -1;
}
int qingwei_dup(int fd) {
    struct client_fd *c, *copy = calloc(1, sizeof(*copy));
    int result = -1, saved;
    if (!copy)
        return -1;
    pthread_rwlock_wrlock(&fd_lock);
    c = find_client(fd);
    if (!c)
        errno = EBADF;
    else {
        result = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (result >= 0) {
            copy->fd = result;
            copy->caps = c->caps;
            copy->target = c->target;
            ++copy->target->refs;
            copy->next = clients;
            clients = copy;
        }
    }
    saved = result < 0 ? errno : 0;
    pthread_rwlock_unlock(&fd_lock);
    if (result < 0)
        free(copy);
    errno = saved;
    return result;
}
int qingwei_clear_hook(int fd) {
    qw_caps_t caps;
    if (qingwei_get_caps(fd, &caps) < 0)
        return -1;
    if (!caps.abi_version) {
        errno = EOPNOTSUPP;
        return -1;
    }
    return do_ioctl(fd, CMD_CLEAR_HOOK, NULL);
}

int qingwei_bind_target(int fd, int pid, const char *pkg_name) {
    qw_caps_t caps;
    mem_packet_t pkt = {0};
    if ((!pkg_name || !pkg_name[0]) && pid <= 0) {
        errno = EINVAL;
        return -1;
    }
    if (pkg_name && strnlen(pkg_name, sizeof(pkt.pkg_name)) == sizeof(pkt.pkg_name)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    if (qingwei_get_caps(fd, &caps) < 0)
        return -1;
    pkt.pid = pid > 0 ? pid : 0;
    if (pkg_name)
        memcpy(pkt.pkg_name, pkg_name, strlen(pkg_name) + 1);
    if (caps.features & QW_FEATURE_BIND_TARGET)
        return do_ioctl(fd, CMD_BIND_TARGET, &pkt);
    /* Old dispatchers cannot safely probe an unknown command. Keep an explicit
     * target in this connection instead of relying on a module-global cache. */
    pthread_rwlock_wrlock(&fd_lock);
    struct client_fd *c = find_client(fd);
    const int found = c != NULL;
    if (c) {
        c->target->pid = pkt.pid;
        memcpy(c->target->package, pkt.pkg_name, sizeof(pkt.pkg_name));
    }
    pthread_rwlock_unlock(&fd_lock);
    errno = found ? 0 : EBADF;
    return found ? 0 : -1;
}

unsigned long qingwei_get_base(int fd, int pid, const char *pkg_name, const char *so_name) {
    if (pkg_name && strnlen(pkg_name, 64) == 64) {
        errno = ENAMETOOLONG;
        return 0;
    }
    mem_packet_t pkt = {0};
    /* 始终设置 pkg_name，即使提供了 PID：
     * 内核驱动需要 pkg_name 来正确缓存任务名称，
     * 后续通过 pkg_name 进行的内存读取才能命中缓存。 */
    if (pkg_name && pkg_name[0]) {
        strncpy(pkt.pkg_name, pkg_name, sizeof(pkt.pkg_name) - 1);
        pkt.pkg_name[sizeof(pkt.pkg_name) - 1] = '\0';
    }
    if (pid > 0) {
        pkt.pid = pid;
    } else {
        if (!pkg_name || !pkg_name[0]) {
            errno = EINVAL;
            return 0;
        }
        pkt.pid = 0; // 触发驱动内部 find_pid_by_name 自动查找
    }

    // 将 so_name 拷贝到 user_buf 指向的缓冲区（注意：需将 so_name 放入可写缓冲区）
    if (!so_name || !so_name[0]) {
        errno = EINVAL;
        return 0;
    }
    if (strnlen(so_name, 256) == 256) {
        errno = ENAMETOOLONG;
        return 0;
    }
    char name_buf[256] = {0};
    strncpy(name_buf, so_name, sizeof(name_buf) - 1);
    name_buf[sizeof(name_buf) - 1] = '\0';
    pkt.user_buf = (unsigned long)name_buf;

    if (do_ioctl(fd, CMD_GET_BASE, &pkt) < 0) {
        return 0;
    }

    // 基址被写回到 name_buf 的开头
    uint64_t base = 0;
    memcpy(&base, name_buf, sizeof(base));
    if (base > ULONG_MAX) {
        errno = EOVERFLOW;
        return 0;
    }
    return base;
}

int qingwei_read_mem(int fd, unsigned long addr, void *buf, size_t size) {
    if (!buf || !size || size > QW_READ_MAX_SIZE) {
        errno = EINVAL;
        return -1;
    }
    mem_packet_t pkt = {0};
    pkt.addr = addr;
    pkt.size = size;
    pkt.offset_count = 0;
    pkt.user_buf = (unsigned long)buf;
    int result = do_ioctl(fd, CMD_READ_MEM, &pkt);
    if (result < 0)
        memset(buf, 0, size);
    return result;
}

int qingwei_read_mem_pkg(int fd, const char *pkg_name, unsigned long addr, void *buf, size_t size) {
    if (pkg_name && strnlen(pkg_name, 64) == 64) {
        errno = ENAMETOOLONG;
        return -1;
    }
    if (!buf || !size || size > QW_READ_MAX_SIZE) {
        errno = EINVAL;
        return -1;
    }
    mem_packet_t pkt = {0};
    if (pkg_name && pkg_name[0]) {
        strncpy(pkt.pkg_name, pkg_name, sizeof(pkt.pkg_name) - 1);
        pkt.pkg_name[sizeof(pkt.pkg_name) - 1] = '\0';
    }
    pkt.pid = 0;
    pkt.addr = addr;
    pkt.size = size;
    pkt.offset_count = 0;
    pkt.user_buf = (unsigned long)buf;
    int result = do_ioctl(fd, CMD_READ_MEM, &pkt);
    if (result < 0)
        memset(buf, 0, size);
    return result;
}

/* Packet success means that per-item statuses are available. A transport or
 * malformed-reply failure clears the whole caller output and every status. */
int qingwei_read_batch_pkg(int fd, const char *pkg_name, qingwei_batch_item_t *items,
                           uint32_t count, void *out_buf, size_t out_size) {
    qw_caps_t caps;
    mem_batch_item_t *chunk = NULL;
    uint32_t *indices = NULL;
    unsigned char *scratch = NULL;
    size_t capacity;
    uint32_t cursor = 0;
    int error = 0, use_batch = 1;

    if (!items || !out_buf || !count || count > QW_BATCH_MAX_ITEMS ||
        !out_size || out_size > QW_BATCH_MAX_SIZE) {
        errno = EINVAL;
        return -1;
    }
    memset(out_buf, 0, out_size);
    for (uint32_t i = 0; i < count; i++)
        items[i].status = -ECANCELED;
    if (pkg_name && strnlen(pkg_name, 64) == 64) {
        error = ENAMETOOLONG;
        goto failed;
    }
    if (qingwei_get_caps(fd, &caps) < 0) {
        error = errno;
        goto failed;
    }
    capacity = out_size < caps.max_out_size ? out_size : caps.max_out_size;
    chunk = calloc(caps.max_items, sizeof(*chunk));
    indices = calloc(caps.max_items, sizeof(*indices));
    scratch = malloc(capacity);
    if (!chunk || !indices || !scratch) {
        error = ENOMEM;
        goto failed;
    }
    while (cursor < count) {
        uint32_t n = 0;
        size_t bytes = 0;
        while (cursor < count && n < caps.max_items) {
            mem_batch_item_t *item = &items[cursor];
            if (!item->size || item->size > QW_BATCH_MAX_SIZE ||
                item->addr > ULONG_MAX || item->size - 1 > ULONG_MAX - item->addr ||
                item->out_offset > out_size || item->size > out_size - item->out_offset) {
                item->status = -EINVAL;
                ++cursor;
                continue;
            }
            if (!use_batch || item->size > caps.max_item_size || item->size > capacity) {
                if (n)
                    break; /* preserve request order when output spans overlap */
                int r = qingwei_read_mem_pkg(fd, pkg_name, (unsigned long)item->addr,
                    (char *)out_buf + item->out_offset, item->size);
                item->status = r < 0 ? -errno : 0;
                ++cursor;
                continue;
            }
            if (item->size > capacity - bytes)
                break;
            chunk[n] = *item;
            chunk[n].out_offset = bytes;
            chunk[n].reserved = 0;
            chunk[n].status = -ECANCELED;
            indices[n] = cursor++;
            bytes += item->size;
            ++n;
        }
        if (!n)
            continue;

        /* Padded for old dispatchers that read mem_packet_t on unknown ioctls. */
        union {
            mem_packet_t padding;
            mem_batch_packet_t batch;
        } request = {0};
        mem_batch_packet_t *pkt = &request.batch;
        if (pkg_name)
            memcpy(pkt->pkg_name, pkg_name, strlen(pkg_name) + 1);
        pkt->count = n;
        pkt->item_size = sizeof(*chunk);
        pkt->items_buf = (uintptr_t)chunk;
        pkt->out_buf = (uintptr_t)scratch;
        pkt->out_size = bytes;
        memset(scratch, 0, bytes);
        if (do_ioctl(fd, CMD_READ_BATCH, pkt) < 0) {
            error = errno;
            if (!caps.abi_version && (error == ENOTTY || error == EINVAL)) {
                use_batch = 0;
                for (uint32_t j = 0; j < n; j++) {
                    mem_batch_item_t *item = &items[indices[j]];
                    int r = qingwei_read_mem_pkg(fd, pkg_name, (unsigned long)item->addr,
                        (char *)out_buf + item->out_offset, item->size);
                    item->status = r < 0 ? -errno : 0;
                }
                error = 0;
                continue;
            }
            goto failed;
        }
        size_t expected_offset = 0;
        for (uint32_t j = 0; j < n; j++) {
            mem_batch_item_t *item = &items[indices[j]];
            /* Only status may change; never use returned lengths as copy bounds. */
            if (chunk[j].addr != item->addr || chunk[j].size != item->size ||
                chunk[j].out_offset != expected_offset || chunk[j].status > 0) {
                error = EPROTO;
                goto failed;
            }
            item->status = chunk[j].status;
            void *dst = (char *)out_buf + item->out_offset;
            if (!item->status)
                memcpy(dst, scratch + expected_offset, item->size);
            else
                memset(dst, 0, item->size);
            expected_offset += item->size;
        }
    }
    free(chunk);
    free(indices);
    free(scratch);
    errno = 0;
    return 0;
failed:
    free(chunk);
    free(indices);
    free(scratch);
    if (!error)
        error = EIO;
    for (uint32_t i = 0; i < count; i++)
        items[i].status = -error;
    memset(out_buf, 0, out_size);
    errno = error;
    return -1;
}

int qingwei_get_module_info(int fd, qingwei_module_info_t *info) {
    if (!info) {
        errno = EINVAL;
        return -1;
    }

    module_info_t kinfo = {0};
    if (do_ioctl(fd, CMD_GET_MODULE_INFO, &kinfo) < 0) {
        return -1;
    }

    info->cpu_time_ns = (uint64_t)kinfo.cpu_time_ns;
    info->call_count = (uint64_t)kinfo.call_count;
    info->mem_bytes = kinfo.mem_bytes;
    return 0;
}

int qingwei_write_mem(int fd, unsigned long addr, const void *buf, size_t size) {
    if (!buf || !size || size > QW_READ_MAX_SIZE) {
        errno = EINVAL;
        return -1;
    }
    mem_packet_t pkt = {0};
    pkt.addr = addr;
    pkt.size = size;
    pkt.offset_count = 0;
    pkt.user_buf = (unsigned long)buf; // 内核会从该地址读取数据
    return do_ioctl(fd, CMD_WRITE_MEM, &pkt);
}

int qingwei_read_ptr(int fd, unsigned long addr, const unsigned long *offsets, int offset_count, void *buf,
                     size_t size) {
    if (!buf || !size || size > QW_READ_MAX_SIZE) {
        errno = EINVAL;
        return -1;
    }
    if (offset_count < 0 || offset_count > 8 || (offset_count && !offsets)) {
        errno = EINVAL;
        return -1;
    }
    mem_packet_t pkt = {0};
    pkt.addr = addr;
    pkt.size = size;
    pkt.offset_count = offset_count;
    for (int i = 0; i < offset_count; i++) {
        pkt.offsets[i] = offsets[i];
    }
    pkt.user_buf = (unsigned long)buf;
    int result = do_ioctl(fd, CMD_READ_PTR, &pkt);
    if (result < 0)
        memset(buf, 0, size);
    return result;
}

int qingwei_read_ptr_pkg(int fd, const char *pkg_name, unsigned long addr, const unsigned long *offsets,
                         int offset_count, void *buf, size_t size) {
    if (pkg_name && strnlen(pkg_name, 64) == 64) {
        errno = ENAMETOOLONG;
        return -1;
    }
    if (!buf || !size || size > QW_READ_MAX_SIZE) {
        errno = EINVAL;
        return -1;
    }
    if (offset_count < 0 || offset_count > 8 || (offset_count && !offsets)) {
        errno = EINVAL;
        return -1;
    }
    mem_packet_t pkt = {0};
    if (pkg_name && pkg_name[0]) {
        strncpy(pkt.pkg_name, pkg_name, sizeof(pkt.pkg_name) - 1);
        pkt.pkg_name[sizeof(pkt.pkg_name) - 1] = '\0';
    }
    pkt.pid = 0;
    pkt.addr = addr;
    pkt.size = size;
    pkt.offset_count = offset_count;
    for (int i = 0; i < offset_count; i++) {
        pkt.offsets[i] = offsets[i];
    }
    pkt.user_buf = (unsigned long)buf;
    int result = do_ioctl(fd, CMD_READ_PTR, &pkt);
    if (result < 0)
        memset(buf, 0, size);
    return result;
}

// --------------------------------------------------------------
// 便捷封装
// --------------------------------------------------------------

uint64_t qingwei_read_pointer(int fd, unsigned long addr) {
    uint64_t val = 0;
    if (qingwei_read_mem(fd, addr, &val, sizeof(val)) < 0)
        return 0;
    return val;
}

uint64_t qingwei_read_pointer_pkg(int fd, const char *pkg_name, unsigned long addr) {
    uint64_t val = 0;
    if (qingwei_read_mem_pkg(fd, pkg_name, addr, &val, sizeof(val)) < 0)
        return 0;
    return val;
}

uint32_t qingwei_read_u32(int fd, unsigned long addr) {
    uint32_t val = 0;
    if (qingwei_read_mem(fd, addr, &val, sizeof(val)) < 0)
        return 0;
    return val;
}

uint32_t qingwei_read_u32_pkg(int fd, const char *pkg_name, unsigned long addr) {
    uint32_t val = 0;
    if (qingwei_read_mem_pkg(fd, pkg_name, addr, &val, sizeof(val)) < 0)
        return 0;
    return val;
}

float qingwei_read_float(int fd, unsigned long addr) {
    float val = 0.0f;
    if (qingwei_read_mem(fd, addr, &val, sizeof(val)) < 0)
        return 0.0f;
    return val;
}

float qingwei_read_float_pkg(int fd, const char *pkg_name, unsigned long addr) {
    float val = 0.0f;
    if (qingwei_read_mem_pkg(fd, pkg_name, addr, &val, sizeof(val)) < 0)
        return 0.0f;
    return val;
}

int qingwei_read_string(int fd, unsigned long addr, char *buf, size_t size) {
    if (size == 0) {
        errno = EINVAL;
        return -1;
    }
    if (!buf) {
        errno = EINVAL;
        return -1;
    }
    if (size == 1) {
        buf[0] = 0;
        errno = 0;
        return 0;
    }
    // 读取 size-1 个字节，留一个给 '\0'
    int ret = qingwei_read_mem(fd, addr, buf, size - 1);
    if (ret < 0)
        return -1;
    buf[size - 1] = '\0';
    return 0;
}

int qingwei_write_u32(int fd, unsigned long addr, uint32_t val) {
    return qingwei_write_mem(fd, addr, &val, sizeof(val));
}

int qingwei_write_float(int fd, unsigned long addr, float val) {
    return qingwei_write_mem(fd, addr, &val, sizeof(val));
}

// ====== 硬件断点 (HWBP) ======

int qingwei_set_hw_bp(int fd, int pid, const char *pkg_name, unsigned long bp_addr, unsigned long blr_x8_addr,
                      unsigned long blr_x9_addr) {
    if (pkg_name && strnlen(pkg_name, 64) == 64) {
        errno = ENAMETOOLONG;
        return -ENAMETOOLONG;
    }
    mem_packet_t pkt = {0};
    /* 将 BLR 地址编码到 user_buf 缓冲区 */
    uint64_t blr_buf[2] = {blr_x8_addr, blr_x9_addr};

    if (pid > 0) {
        pkt.pid = pid;
    } else {
        if (!pkg_name || !pkg_name[0]) {
            errno = EINVAL;
            return -EINVAL;
        }
        pkt.pid = 0;
    }
    /* 始终设置 pkg_name 以确保驱动缓存一致性 */
    if (pkg_name && pkg_name[0]) {
        strncpy(pkt.pkg_name, pkg_name, sizeof(pkt.pkg_name) - 1);
        pkt.pkg_name[sizeof(pkt.pkg_name) - 1] = '\0';
    }
    pkt.addr = bp_addr;
    pkt.user_buf = (unsigned long)blr_buf;

    qw_caps_t caps;
    if (qingwei_get_caps(fd, &caps) < 0)
        return -errno;
    if (!caps.abi_version || !(caps.features & QW_FEATURE_HWBP)) {
        errno = EOPNOTSUPP; /* v1 cannot reliably release the installed breakpoint */
        return -EOPNOTSUPP;
    }
    if (do_ioctl(fd, CMD_SET_HW_BP, &pkt) < 0)
        return -errno;
    return 0;
}

int qingwei_update_blr_addrs(int fd, unsigned long blr_x8_addr, unsigned long blr_x9_addr) {
    mem_packet_t pkt = {0};
    uint64_t blr_buf[2] = {blr_x8_addr, blr_x9_addr};

    pkt.pid = 0; /* 使用缓存的进程 */
    pkt.user_buf = (unsigned long)blr_buf;

    if (do_ioctl(fd, CMD_SET_BLR_ADDRS, &pkt) < 0)
        return -errno;
    return 0;
}

int qingwei_query_snapshot(int fd, const char *pkg_name, unsigned long obj_addr, float *x, float *y,
                           float *z) {
    if (pkg_name && strnlen(pkg_name, 64) == 64) {
        errno = ENAMETOOLONG;
        return -ENAMETOOLONG;
    }
    if (!x || !y || !z) {
        errno = EINVAL;
        return -EINVAL;
    }
    mem_packet_t pkt = {0};
    uint32_t result[3] = {0, 0, 0};

    pkt.pid = 0;
    if (pkg_name && pkg_name[0]) {
        strncpy(pkt.pkg_name, pkg_name, sizeof(pkt.pkg_name) - 1);
        pkt.pkg_name[sizeof(pkt.pkg_name) - 1] = '\0';
    }
    pkt.addr = obj_addr;
    pkt.user_buf = (unsigned long)result;

    if (do_ioctl(fd, CMD_QUERY_SNAPSHOT, &pkt) < 0)
        return -errno;

    /* 驱动返回 u32 raw 值，在此转换回 float */
    memcpy(x, &result[0], sizeof(*x));
    memcpy(y, &result[1], sizeof(*y));
    memcpy(z, &result[2], sizeof(*z));
    return 0;
}

int qingwei_set_hook(int fd, int pid, unsigned long bp_addr, int mode, const int *regs, int reg_count) {
    mem_packet_t pkt = {0};
    hw_bp_cfg_t cfg;
    int i;
    memset(&cfg, 0, sizeof(cfg));
    cfg.bp_addr = bp_addr;
    cfg.mode = (uint32_t)mode;
    if (reg_count < 0 || reg_count > 8 || (reg_count && !regs) || !(mode == 0 || (mode >= 2 && mode <= 5))) {
        errno = EINVAL;
        return -EINVAL;
    }
    for (i = 0; i < reg_count; ++i)
        if (regs[i] < 0 || regs[i] > 30) {
            errno = EINVAL;
            return -EINVAL;
        }
    qw_caps_t caps;
    if (qingwei_get_caps(fd, &caps) < 0)
        return -errno;
    if (!caps.abi_version || !(caps.features & QW_FEATURE_HWBP)) {
        errno = EOPNOTSUPP;
        return -EOPNOTSUPP;
    }
    cfg.reg_count = (uint32_t)reg_count;
    for (i = 0; i < reg_count; i++)
        cfg.regs[i] = (uint32_t)regs[i];
    pkt.pid = pid;
    pkt.addr = bp_addr;
    pkt.offset_count = mode;
    pkt.user_buf = (unsigned long)&cfg;
    if (do_ioctl(fd, CMD_SET_HOOK_CFG, &pkt) < 0)
        return -errno;
    /* 优先使用驱动写回的错误码（比 errno 更准确） */
    if (cfg.ret != 0) {
        errno = cfg.ret < 0 && cfg.ret >= -4095 ? -cfg.ret : EPROTO;
        return -errno;
    }
    return 0;
}

int qingwei_read_dec_snap(int fd, qingwei_dec_snap_t *snaps, uint32_t max, uint32_t *count) {
    if (count)
        *count = 0;
    if (!snaps || !count || !max || max > QINGWEI_DEC_SNAP_MAX) {
        errno = EINVAL;
        return -1;
    }
    mem_packet_t pkt = {0};
    pkt.user_buf = (unsigned long)snaps;
    pkt.size = max;
    if (do_ioctl(fd, CMD_READ_DEC_SNAP, &pkt) < 0) {
        int saved = errno;
        memset(snaps, 0, max * sizeof(*snaps));
        errno = saved;
        return -1;
    }
    if (pkt.offset_count < 0 || (uint32_t)pkt.offset_count > max) {
        memset(snaps, 0, max * sizeof(*snaps));
        errno = EPROTO;
        return -1;
    }
    for (int i = 0; i < pkt.offset_count; i++) {
        if (snaps[i].reg_count > 8) {
            memset(snaps, 0, max * sizeof(*snaps));
            errno = EPROTO;
            return -1;
        }
    }
    *count = (uint32_t)pkt.offset_count;
    return 0;
}

int qingwei_get_hwbp_stats(int fd, qingwei_hwbp_stats_t *stats) {
    if (!stats) {
        errno = EINVAL;
        return -1;
    }

    hwbp_stats_t kstats;
    memset(&kstats, 0, sizeof(kstats));

    if (do_ioctl(fd, CMD_GET_HWBP_STATS, &kstats) < 0)
        return -1;

    stats->hit_count = kstats.hit_count;
    stats->bp_addr = kstats.bp_addr;
    stats->snapshot_count = kstats.snapshot_count;
    stats->active = kstats.active;
    return 0;
}

uint32_t qingwei_read_ptr_u32(int fd, unsigned long addr, const unsigned long *offsets, int offset_count) {
    uint32_t val = 0;
    if (qingwei_read_ptr(fd, addr, offsets, offset_count, &val, sizeof(val)) < 0)
        return 0;
    return val;
}

uint64_t qingwei_read_ptr_pointer(int fd, unsigned long addr, const unsigned long *offsets,
                                  int offset_count) {
    uint64_t val = 0;
    if (qingwei_read_ptr(fd, addr, offsets, offset_count, &val, sizeof(val)) < 0)
        return 0;
    return val;
}

// ====== TLS + PAC 密钥读取（无 ptrace） ======

int qingwei_read_tls_pac(int fd, int pid, const char *pkg_name, qingwei_tls_pac_t *out) {
    if (out)
        memset(out, 0, sizeof(*out));
    if (pkg_name && strnlen(pkg_name, 64) == 64) {
        errno = ENAMETOOLONG;
        return -1;
    }
    if (!out || fd < 0) {
        errno = EINVAL;
        return -1;
    }

    mem_packet_t pkt = {0};
    tls_pac_packet_t result;

    memset(&result, 0, sizeof(result));

    if (pid > 0) {
        pkt.pid = pid;
    } else {
        if (!pkg_name || !pkg_name[0]) {
            errno = EINVAL;
            return -1;
        }
        pkt.pid = 0;
    }
    /* 始终设置 pkg_name 以确保驱动缓存一致性 */
    if (pkg_name && pkg_name[0]) {
        strncpy(pkt.pkg_name, pkg_name, sizeof(pkt.pkg_name) - 1);
        pkt.pkg_name[sizeof(pkt.pkg_name) - 1] = '\0';
    }
    pkt.user_buf = (unsigned long)&result;

    if (do_ioctl(fd, CMD_READ_TLS_PAC, &pkt) < 0) {
        return -1;
    }

    out->tls = result.tls;
    out->apia_lo = result.apia_lo;
    out->apia_hi = result.apia_hi;
    out->apib_lo = result.apib_lo;
    out->apib_hi = result.apib_hi;
    out->apda_lo = result.apda_lo;
    out->apda_hi = result.apda_hi;
    out->apdb_lo = result.apdb_lo;
    out->apdb_hi = result.apdb_hi;
    out->apga_lo = result.apga_lo;
    out->apga_hi = result.apga_hi;
    out->pac_valid = result.pac_valid;
    out->reserved = result.reserved;
    return 0;
}

int qingwei_find_pid(int fd, const char *pkg_name) {
    if (pkg_name && strnlen(pkg_name, 64) == 64) {
        errno = ENAMETOOLONG;
        return -1;
    }
    if (fd < 0 || !pkg_name || !pkg_name[0]) {
        errno = EINVAL;
        return -1;
    }

    mem_packet_t pkt = {0};
    strncpy(pkt.pkg_name, pkg_name, sizeof(pkt.pkg_name) - 1);
    pkt.pkg_name[sizeof(pkt.pkg_name) - 1] = '\0';
    pkt.pid = 0;

    if (do_ioctl(fd, CMD_FIND_PID, &pkt) < 0)
        return -1;

    if (pkt.pid <= 0) {
        errno = ESRCH;
        return -1;
    }
    return pkt.pid;
}

int qingwei_enum_modules(int fd, int pid, const char *pkg_name, char *out_buf, size_t buf_size) {
    if (pkg_name && strnlen(pkg_name, 64) == 64) {
        errno = ENAMETOOLONG;
        return -1;
    }
    if (fd < 0 || !out_buf || buf_size < 64 || buf_size > 65536) {
        errno = EINVAL;
        return -1;
    }

    mem_packet_t pkt = {0};
    if (pkg_name && pkg_name[0]) {
        strncpy(pkt.pkg_name, pkg_name, sizeof(pkt.pkg_name) - 1);
        pkt.pkg_name[sizeof(pkt.pkg_name) - 1] = '\0';
    }
    pkt.pid = (pid > 0) ? pid : 0;
    pkt.user_buf = (unsigned long)out_buf;
    pkt.size = buf_size;
    out_buf[0] = '\0';

    if (do_ioctl(fd, CMD_ENUM_MODULES, &pkt) < 0)
        return -1;
    if (pkt.pid < 0 || (size_t)pkt.pid > buf_size || !memchr(out_buf, '\0', buf_size)) {
        out_buf[0] = '\0';
        errno = EPROTO;
        return -1;
    }

    return pkt.pid; // pkt.pid 被内核设为模块数量
}

/* ================= 坐标收发截取：内核 netfilter 抓包接口 ================= */
int qingwei_capture_start(int fd, int pid) {
    if (fd < 0 || pid <= 0) {
        errno = EINVAL;
        return -1;
    }
    mem_packet_t pkt = {0};
    pkt.pid = pid;
    return do_ioctl(fd, CMD_CAPTURE_START, &pkt);
}

int qingwei_capture_stop(int fd) {
    if (fd < 0) {
        errno = EINVAL;
        return -1;
    }
    return do_ioctl(fd, CMD_CAPTURE_STOP, 0);
}

int qingwei_capture_read(int fd, qingwei_capture_pkt_t *out, uint32_t max_pkts, uint32_t *count) {
    if (count)
        *count = 0;
    if (fd < 0 || !out || !count || max_pkts > QW_CAPTURE_RING) {
        errno = EINVAL;
        return -1;
    }
    qingwei_capture_read_t rpkt;
    memset(&rpkt, 0, sizeof(rpkt));
    rpkt.max_pkts = max_pkts;
    rpkt.out_buf = (unsigned long)out;
    if (do_ioctl(fd, CMD_CAPTURE_READ, &rpkt) < 0)
        return -1;
    *count = rpkt.count_out;
    return 0;
}

int qingwei_capture_stats(int fd, qingwei_capture_stats_t *st) {
    if (fd < 0 || !st) {
        errno = EINVAL;
        return -1;
    }
    return do_ioctl(fd, CMD_CAPTURE_STATS, st);
}
