#!/usr/bin/env python3
"""Lightweight source-level ABI regression checks for the C/Kotlin pair."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
c = (ROOT / "driver/qingwei_mcp.c").read_text(encoding="utf-8")
kcmd = (ROOT / "app/src/main/java/com/kernelai/app/driver/IoctlCommands.kt").read_text(encoding="utf-8")
kmcp = (ROOT / "app/src/main/java/com/kernelai/app/mcp/McpToolExecutor.kt").read_text(encoding="utf-8")

required_asserts = {
    "kai_proc_list_packet": 32,
    "kai_proc_entry": 296,
    "kai_module_list_packet": 48,
    "kai_module_entry": 288,
    "kai_vma_list_packet": 48,
    "kai_vma_entry": 48,
    "kai_thread_list_packet": 48,
    "kai_thread_entry": 44,
    "kai_raw_read_packet": 40,
    "kai_mem_search_packet": 568,
    "kai_hwbp_setup": 92,
    "kai_reg_read_packet": 280,
    "kai_resolve_pkg": 264,
    "kai_hwbp_remove": 72,
    "kai_bp_entry": 32,
    "kai_swbp_setup": 80,
    "kai_callstack_packet": 56,
    "kai_stack_frame": 48,
    "module_perf_info": 56,
    "hwbp_stats": 24,
}
for name, size in required_asserts.items():
    needle = f"static_assert(sizeof(struct {name}) == {size});"
    assert needle in c, f"missing C ABI assertion: {needle}"

kernel_commands = {}
for match in re.finditer(
    r"^#define\s+(CMD_\w+)\s+KAI_(IOWR|IOW|IOR)\((\d+),\s*(\d+|sizeof\(struct \w+\))\)",
    c,
    re.M,
):
    name, direction, number, size = match.groups()
    if size.startswith("sizeof("):
        size = required_asserts[size.removeprefix("sizeof(struct ").removesuffix(")")]
    kernel_commands[name.removesuffix("_LEGACY")] = (direction, int(number), int(size))

app_commands = {
    name: (direction.upper(), int(number), int(size))
    for name, direction, number, size in re.findall(
        r"^\s*val (CMD_\w+) = (iowr|iow|ior)\(IOCTL_MAGIC,\s*(\d+),\s*(\d+)\)",
        kcmd,
        re.M,
    )
}
assert len(kernel_commands) == 24, "unexpected number of kernel ioctl declarations"
assert kernel_commands.keys() == app_commands.keys(), "kernel/Android ioctl name mismatch"
for name, encoding in kernel_commands.items():
    assert app_commands[name] == encoding, f"{name}: kernel {encoding}, Android {app_commands[name]}"
assert len({encoding[1] for encoding in kernel_commands.values()}) == len(kernel_commands)
assert re.search(r"#define\s+IOCTL_MAGIC\s+'M'", c) and "IOCTL_MAGIC = 'M'.code" in kcmd

for token in [
    "48 + dataBytes", "40 + safeSize",
    "568 + safeResults * 8", "48 + dataBytes", "resultsOffset = 568",
]:
    assert token in kmcp, f"missing Kotlin buffer/offset contract: {token}"

kstruct = (ROOT / "app/src/main/java/com/kernelai/app/driver/IoctlStructs.kt").read_text(encoding="utf-8")
assert "PROC_HEADER_SIZE = 32" in kstruct
assert "PROC_ENTRY_SIZE = 296" in kstruct
assert "allocate(PROC_HEADER_SIZE + dataBytes)" in kstruct
assert "IoctlStructs.buildProcListPacket(maxCount, offset)" in kmcp

# Dangerous pre-M1 constructs must not regress.
assert re.search(r"if \(!entry && !list_empty\(&g_pid_cache_lru\)\)\s*\{\s*victim = list_last_entry", c), "LRU eviction must be guarded by list_empty()"
for forbidden in ["strncpy_from_user", "mm->mmap", "vma->vm_next", "pte_offset_map("]:
    assert forbidden not in c, f"unsafe/legacy memory path regressed: {forbidden}"
assert "access_process_vm(task, vaddr, kbuf, (int)len, FOLL_WRITE)" in c
assert "if (!capable(CAP_SYS_PTRACE))" in c
assert "MODULE_HIDE_NAME" not in c
assert "-DCONFIG_HAVE_HW_BREAKPOINT" not in (ROOT / "driver/Kbuild").read_text(encoding="utf-8")

print("ABI/source regression checks: PASS")
print(f"Verified {len(required_asserts)} packed C structure sizes and {len(kernel_commands)} matching ioctl declarations (direction, number, size).")
