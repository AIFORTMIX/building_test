# 0.5.0 静态分析与动态调试

## 实际能力

静态 ELF64 分析、ARM64 解码以及动态调试都在主 APK 中，界面、内置 AI 与已授权外部 MCP 客户端共用工具后端。现有 KO / ioctl ABI 不变。

静态分析使用工作区授权与临时只读快照，至多 64 MiB；支持 ELF 段、节、符号、导入导出、符号表中的函数、ASCII 字符串、PT_LOAD 偏移换算和 Capstone 5.0.6 ARM64 指令。无节表时明确缺失符号能力，不等于没有符号。不提供反编译或完整函数、控制流与交叉引用恢复。

动态后端为 NDK 28.2.13676358 自带的 LLDB 19.0.1，预构建 ARM64 / x86_64 服务随 APK 打包为提取式原生可执行文件，许可证随包保留。正式运行在已有 libsu Root 服务中，独立于驱动节点是否打开。

## 使用顺序

1. `debug_capabilities` 检查打包状态与 Root 身份，`debug_target_status(pid)` 读取目标身份。
2. `debug_attach(pid, expected_identity)` 附加并暂停目标，取得本次 `session_id`。
3. `debug_status(session_id)` 确认 `state=stopped`，再读取线程、寄存器、内存或管理断点。线程 ID 为 GDB 的十六进制字符串。
4. `debug_step` / `debug_continue` 返回请求后的状态；继续查询 `debug_status` 获取真实停止、信号或退出事件。阻塞系统调用中的单步可能持续等待，可以发送 `debug_pause`。
5. 完成后 `debug_detach`，检查 `cleanup_confirmed`。未收到确认时不把“连接已关闭”写成“目标已恢复”。

会话只允许一个目标，采用 all-stop 模式。旧会话 ID 不能用于新目标；目标退出、exec、身份变化后结束旧会话。当前仅提供读取寄存器和少量内存，不提供任意命令执行、DWARF 调用栈、源码级步进或寄存器写入。原驱动 `callstack_capture` 仍未实现。

## 断点与地址

`debug_breakpoint_set` 的 type 分别为 0 软件执行、1 硬件执行、2 写监视、3 读监视、4 读写监视。ARM64 执行断点长度为 4，x86_64 为 1；硬件资源不足或不支持的组合明确失败，不换成另一种断点。LLDB 会话断点和原驱动全局硬件断点分别列出。

静态虚拟地址是 ELF 链接地址，不是运行时地址；需要依据当前进程模块的 load bias 换算。动态地址以字符串传输，检查对齐与范围溢出。`debug_disassemble` 只接受已确认的小端 ARM64 目标，其他架构可以使用线程、寄存器、内存和断点工具。

## 连接、超时与清理

- Root Binder 只接受本应用 UID 或 Root；应用不因 LLDB 可以启动就声称已获得附加权限。
- LLDB 反向连接随机名称的抽象 Unix socket，核对对端 UID，不开放无鉴权的调试 TCP 端口。
- 先握手并成功设置 `QSetDetachOnError:1`，再发送附加命令。连接丢失时由 LLDB 恢复软件断点并脱离。
- 读取线程可能持有阻塞的 socket 引用，`close()` 单独调用未必立刻通知对端；因此关闭前执行双向 shutdown，再关闭。超时不自动重发原命令。
- 协议确认或响应超时后立即关闭该连接，让后续状态查询看到连接丢失，防止下一条命令误收前一条命令的迟到响应。
- UI 与 AI 的状态更新比较会话创建顺序和事件版本，迟到的旧响应不能覆盖新目标。
- 闲置两分钟自动脱离；界面打开或 AI/客户端查询会更新活动时间。关闭 Root 服务也会请求结束调试。

原理参考 [LLDB Remote Protocol](https://lldb.llvm.org/resources/lldbgdbremote.html)。权限、系统强行终止和不可中断的内核等待仍需真机检查，不能承诺绕过厂商策略。

## 事件与内存边界

事件保留最近 64 项，每页至多 20 项。`last_event` 是状态版本；分页使用 `next_after_event`，并检查 `has_more_events`。`events_truncated` 说明更早历史已超出保留范围。界面只展示最近少量事件，不假装是完整持久日志。第三方 MCP 调用仍由原审计记录器记录。

单次内存读取至多 4096 字节，单次 ARM64 解码至多 256 条；寄存器数据和协议包有大小限制，事件队列满时明确终止连接，不静默丢事件。大工具结果仍通过归档和 `tool_result_read` 查看，不重做设备操作。

## 验证范围

API 37 模拟器已经用自有多线程程序实际验证附加、线程暂停、当前寄存器、内存、单步、软件断点命中、移除、继续、暂停、脱离和目标退出。界面操作与模拟模型 → HTTP MCP → 实际 Root 后端 → 模型也已通过。exec 和事件分页回归也已在 API 37 完整 42 项验收中通过；最终 Release / CI 结果见后续验证记录。

AOSP 模拟器的 su 只授权 shell UID，不能把 App 未获 Root 的结果当成成功。仪器测试从 shell 以 Root 启动**同一份实际调试后端代码**，通过测试专用 Unix socket 调用，未模拟 ptrace 或改成演示数据；该启动入口和自有目标仅存在于 Debug 构建。正式 App 的 libsu 授权与 Binder 入口仍保留完整检查，需要在具有 Magisk / KernelSU 授权的真机验收。

用户当前不能连接真机，因此 ARM64 真机动态调试、锁屏网络切换、长期 CPU/内存/耗电和厂商后台策略均保留待验收，不记为已完成。

ELF 解析还检查节的文件区间不重叠，并为长名称提供有界缓存。大符号表分页不再受旧的百万游标门槛限制；空筛选会先跳过原始符号索引，避免为前面所有符号创建 JSON。节的布局约束依据 [ELF gABI](https://gabi.xinuos.com/elf/03-sheader.html)。
