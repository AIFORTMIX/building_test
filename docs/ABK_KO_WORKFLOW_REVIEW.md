# ABK KO 工作流核查

核查日期：2026-11-18。后续已取得核心脚本并完成本地修订与测试，
没有启动完整 GitHub Actions 内核构建、修改远程仓库或在手机加载模块。
修复文件、验证和使用方式见 [ABK_KO_BUILD.md](ABK_KO_BUILD.md)。

## 结论

用户提供的 `build-ko-module.yml` 是此前编译 KO 使用的入口。
真正编译由它第 596 行调用的 `scripts/build_external_ko.sh` 执行。
此前该脚本没有附上，后续用户提供了原稿，SHA-256 为
`739a1f08fbbc295cfc433d3525b1ed3aa595ebf7ba63057a7f2527871a2fce2a`。
已确认原稿没有直接改写模块 CRC 或 vermagic；但它从月份分支构建新内核，
没有复用手机 ABK 构建输出，也没有对手机 CRC 作最终核对。

另有目录模式 CFLAGS 未应用、传统分支忽略 LTO 输入、扫描全树误选旧产物、
固定暂存目录与 Bazel 输出链接处理等问题。修订版已处理这些问题，
增加只读目标符号表验证；没有把任何构建成功解释为真机稳定性通过。

## 已确认的问题与检查项

| 位置（用户 YAML 行号） | 发现 | 处理方向 |
| --- | --- | --- |
| 543–566 | pahole 失败后会构造没有类型信息的 24 字节 BTF 头；objcopy 失败也可被忽略；未识别模式同样返回成功 | 移除构造占位 BTF 和无条件成功的回退。修复真实工具链问题，或明确选择经过核验的非 BTF 构建方式，并记录差异 |
| 72–79 | `bazel_lto` 默认 none；已导出的配置声明 ThinLTO | 取得真实构建配置及核心脚本后核对 LTO/CFI。不能只因省内存而把配置差异当成已验证兼容 |
| 387–404、614 | 可以追加 localversion；安装提示主要强调版本串匹配 | 完整核对生成的 release/vermagic、实际导入 CRC、配置和签名要求；相同后缀不足以证明 ABI 兼容 |
| 42–51 | 单版本默认参数为 172 / 2025-07，而手机构建包标注为 138 / 2025-06 | 手机目标需要显式选 android14 / 6.1 / 138 / 2025-06。没有实际运行参数，不能认定之前的运行用了默认值 |
| 432–459 | 创建 BTF 禁用 fragment，并通过修改 build.config 注入 | 核查所用 Kleaf/传统脚本是否真正读取该变量，并检查最终配置，不能仅凭注释认定生效 |
| 358–380 | 按月份分支同步源码；这份 YAML 没有展示固定到手机所指 commit 的步骤 | 保留 resolved manifest、源码及补丁仓库 commit；是否另有固定操作需查看核心脚本 |
| 594–601 | 核心脚本负责实际编译，现已取得并核查 | 修正目录选项、LTO、隔离暂存、当前产物选择与最终 ABI 检查，并保留来源元数据 |

这些是工作流的缺陷或待核查项，不是已经发现手机驱动运行时崩溃的证明。
工作流中的 swap 用于缓解 CI 构建机的内存压力，不会修复手机驱动中的泄漏、越界或死锁。
单凭 Bus error / exit 135 也不能确定根因是 OOM。

## 内核矩阵与共用构建流程

用户给出的 [kernel-a14-6-1.yml](https://github.com/xingguangcuican6666/ABK/blob/8156a917fbfc6717a1054a36bd09f787b83a67d9/.github/workflows/kernel-a14-6-1.yml)
将 138 对应到 2025-06，再调用共用
[build.yml](https://github.com/xingguangcuican6666/ABK/blob/8156a917fbfc6717a1054a36bd09f787b83a67d9/.github/workflows/build.yml)。
该快照的编译阶段支持传统 build/build.sh 和 Kleaf/Bazel 两条路径，
后者编译 `//common:kernel_aarch64_dist`，并按选项传入 fragment 和 LTO 参数。
这是当前公开流程的参考，不能替代用户旧 KO 的实际脚本或原始运行记录。

共用 build.yml 的 1340–1375 行包含有条件的 stock_defconfig 配置展示替换。
应保留原始输出目录的真实 `.config` 和 generated 头文件进行匹配。
截至本次核查，无法确认该条件在手机这次构建中是否满足。

[AOSP 基线源码](https://android.googlesource.com/kernel/common/+/5929004f86ffc02c0b750715ec5a2b1541d14065)
的 Makefile 为 6.1.138。这个 commit 不包含用户后续未提交的补丁或构建参数。

## 下一步验收顺序

1. 已读取并修订用户提供的核心脚本，保留单文件/目录源码模式等既有功能，
   完成本地构建边界和检查器测试。尚未执行完整目标内核构建。
2. 使用修订后的工作流进行完整 Linux 构建，核对真实构建配置、生成头文件和补丁版本。
   如复用 Kleaf，外部模块应依赖实际的内核构建目标并使用相同的配置参数。
3. 用从手机构建包恢复的导出表检查新模块全部导入和 CRC，核对
   release/vermagic、CFI 及签名要求。输出可追溯的构建元数据。
4. 先进行装载/卸载和小范围功能测试，再验证并发 ioctl、进程退出、
   硬件断点生命周期、内存压力和持续运行；依据日志修复并回归。

## 输入标识

- 用户 YAML SHA-256：`ff180129adda8625c95abd829cc7a770529379eee34547074d7cd407f5947840`
- ABK dev 核查快照：`8156a917fbfc6717a1054a36bd09f787b83a67d9`
- 矩阵工作流 SHA-256：`4abac0a3dbc97504fb769ab80284e5e5d0dd6a01bd72dee779d422ff803ac104`
- 共用构建工作流 SHA-256：`d53f3b1f7e219cc3e0ea77b3c455de5960eba8fe6dd4cc655b7b22434cf46a49`

参考：[Linux 外部模块与版本检查](https://docs.kernel.org/kbuild/modules.html#module-versioning)、
[Kleaf 外部模块接口](https://android.googlesource.com/kernel/build/+/8e7d5de7f1fdbf9302c1683365610af9377eb37a/kleaf/docs/impl.md)。
