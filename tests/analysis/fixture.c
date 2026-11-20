#include <unistd.h>
__attribute__((visibility("default"), noinline))
int qingwei_fixture_sum(int a, int b) { return a + b + (getpid() < 0); }
__attribute__((visibility("default")))
const char qingwei_fixture_text[] = "Qingwei ELF64 analysis fixture";
