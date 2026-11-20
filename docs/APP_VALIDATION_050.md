# 0.5.0 验证记录（2026-11-21）

App 源码为 `3aaa7b5b0aaff1802fd65b2528d8f42c326526b7`。[Actions 44794300482](https://github.com/AIFORTMIX/building_test/actions/runs/44794300482) 的构建、完整 Android 检查及三个兼容性任务全部成功；同一提交的 [构建脚本回归](https://github.com/AIFORTMIX/building_test/actions/runs/44794300089) 也已通过。文档提交不改变该安装包的源码。

## 安装包

版本 `0.5.0 / 500`，包名 `com.kernelai.app`，包含 ARM64 与 x86_64。正式安装仅需主 APK，MCP 宿主和 LLDB 都已内置。

- APK SHA256：`977a089f3079babf5e51bd6922595d913bf73a27abd35d60d5347a84f8561965`
- 签名证书 SHA256：`9b22114432e135bf70c8909c8965fc4733eabe822c6e8fee5b7157a703ecca5d`
- 文件大小：47,430,399 字节。

从 Actions 下载后，再用本地 aapt2、apksigner 和 SHA256 独立核验实际 Manifest、签名及校验文件。仓库同级的交付副本为 `outputs/qingwei-0.5.0.apk`，旁有 `.sha256` 文件。

## 自动化结果

| 环境与检查 | 结果 |
| --- | --- |
| Linux 宿主测试 | 123 项，0 失败，0 跳过 |
| API 35 完整 Android UI/JNI | 42 项通过 |
| API 28、31、32 兼容性矩阵 | 各 5 项通过 |
| 本地 Windows 宿主测试 | 123 项无失败，因既有符号链接权限限制跳过 1 项 |
| 本地 API 37 / 16 KiB | 完整 42 项通过；最后新增超时保护后，11 项相关检查再次通过 |
| Debug、R8 Release、Android 测试包构建与 Lint | 通过；Lint 0 错误、78 警告 |
| App/Test Compose 版本检查 | 通过，runtime 1.11.2 |
| 既有 ioctl ABI | 20 个打包结构、24 条 ioctl 通过 |
| LLDB 打包与许可证 | ARM64/x86_64 可执行 ELF 与许可证齐全；Release 无测试目标 |

玻璃检查覆盖系统能力开关、过期回调、注销和重开、flag/半径清理、弹窗背景隔离、深色双倍字体滚动及横竖屏重建。后台 AI、外部 MCP 生命周期、媒体、用量与聊天回归继续包含在完整套件中。

ELF 检查覆盖真实 ARM64 文件、无节表、畸形与越界输入、文件变化、地址转换和真实 Capstone 解码。新增超过百万符号的分页用例，拒绝重叠节造成的重复扫描，名称读取与缓存有界。

动态调试检查使用自有多线程原生目标，实际执行 LLDB 附加、暂停、当前寄存器/内存读取、工作线程单步、软件断点命中/移除、继续、脱离、连接丢失、退出/exec 和多页事件读取。确认或响应超时后关闭连接，控制命令不自动重发；断线清理未确认时不会返回“已安全恢复”。

## 正式包覆盖安装与 AI 链路

在同一 API 37 模拟器中，从固定签名的 0.4.0 创建会话、供应商配置及由 AI 经 MCP 写入的工作区文件，再覆盖安装云端 0.5.0。安装后启动前，三个实际存储文件的 SHA256 与升级前完全一致；启动后旧正文、工具记录、模型选择和默认工作区仍可恢复。

正式包使用本机模拟模型响应，经实际 HTTP MCP 完成 `workspace_binary_info` → `workspace_binary_query` 定位 `qingwei_fixture_sum` → `workspace_binary_disassemble`，返回真实 `stp/str/mov/add` 指令。模型请求中实际包含四项静态分析工具和十五项 `debug_*` 工具。随后调用 `debug_capabilities`，在 App UID 没有 su 授权时如实返回“未获得 Root 授权”，页面也显示该错误；未伪造动态调试成功。最终正文按 SSE 输出并显示，工具链自动收起。

动态调试的成功 AI 链路另在仪器测试中验证：模拟模型 → 真实 HTTP MCP → 实际 Root LLDB 后端 → 模型，完成核实身份、附加、读取寄存器和脱离。

本轮正式包当前进程的 crash buffer 未观察到 FATAL，测试结束未发现遗留的 LLDB 服务或自有调试目标。以上为功能与边界回归，不等于长时间内存、CPU 或耗电验收。

## 待完成的真机项目

用户目前不能连接 USB／无线 ADB。AOSP 模拟器仅给 shell UID 提供 su，因此成功调试测试从 shell Root 运行同一份实际后端代码；正式 App 的 libsu 授权与 Binder 检查始终保留，没有绕过授权。ARM64 真机的正式 Root 链路、SELinux、硬件断点资源、锁屏/换网/长期后台及性能耗电仍待现场验收。

[动态调试说明](DYNAMIC_DEBUGGER.md) 包含不连接电脑时的自有目标测试方法；[实施清单](IMPLEMENTATION_PLAN.md) 继续保留这两项未完成验收。此次未修改驱动源码或 ioctl ABI；新增反汇编 JNI 和用户态 LLDB 位于 APK 中，**无需重编 KO**。

本地完整证据在仓库同级 `outputs/next-validation-2026-11-20/`：Actions 原始报告、`VALIDATION.json`、升级前后校验、模拟模型回执及正式包截图。目录以本轮开始日期命名，最终验收日期为 2026-11-21。
