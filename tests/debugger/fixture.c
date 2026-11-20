#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <signal.h>
#include <unistd.h>

volatile uint64_t qingwei_marker = UINT64_C(0x1122334455667788);
volatile uint64_t qingwei_counter = 0;
static volatile sig_atomic_t reexec_requested = 0;
static void request_exec(int signal_number) { (void)signal_number; reexec_requested = 1; }
__attribute__((noinline)) void qingwei_debug_tick(void) {
    __asm__ volatile("nop");
    qingwei_counter++;
}
static void *worker(void *unused) {
    (void)unused;
    for (;;) { qingwei_debug_tick(); usleep(5000); }
    return NULL;
}
int main(void) {
    signal(SIGUSR1, request_exec);
    pthread_t thread;
    if (pthread_create(&thread, NULL, worker, NULL) != 0) return 2;
    printf("%d %p %p %p\n", getpid(), (void *)qingwei_debug_tick, (void *)&qingwei_marker, (void *)&qingwei_counter);
    fflush(stdout);
    for (;;) {
        pause();
        if (reexec_requested) { execl("/proc/self/exe", "qingwei-debug-fixture", (char *)NULL); return 3; }
    }
}
