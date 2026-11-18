# 手机 ABK / Kernel 6.1.138 目标构建说明

更新于 2026-11-18。已获得内核镜像、手机导出配置和恢复的内置导出符号表，
尚未完成该手机目标的驱动构建与装载验证。

## 已取得的材料

- 版本串：`6.1.138-android14-11-g5929004f86ffc-ab10023717-4k`。
- 用户构建包中的 Image，其嵌入配置与 `qingwei-config.gz` 解压结果逐字节相同。
- 从该 Image 的导出表和 CRC 表恢复的 `Module.symvers`，共 8,152 个内置导出。
  文件位于工作区 `outputs/kernel-6.1.138/`；未覆盖独立模块的导出。
- 版本串所指的 AOSP common 基线 commit：
  `5929004f86ffc02c0b750715ec5a2b1541d14065`，Makefile 的版本为 6.1.138。
- 用户实际编译 KO 时使用的 `build-ko-module.yml` 和
  `scripts/build_external_ko.sh`。本地修订、验证和下一步见
  [ABK_KO_BUILD.md](ABK_KO_BUILD.md)。

## 配置和来源的限制

ABK 的 dev 分支在快照 `8156a917fbfc6717a1054a36bd09f787b83a67d9` 的
共用 build.yml 中包含可选的 Stock Config 替换逻辑：当仓库提供
`config/stock_defconfig` 时，修改 kernel/Makefile，使 config_data 使用该文件。
没有这个文件则跳过；本次查询该快照的文件地址返回 404。
这不是手机已使用替代配置的证明，但说明 Image 与 `/proc/config.gz` 一致
不能单独证明它们就是真实编译配置。应以原始构建输出目录的最终 `.config`
及生成头文件为依据。直接从 Image 读取的导出 CRC 不依赖该配置展示逻辑。

完整核查结果和后续步骤见 [ABK_KO_WORKFLOW_REVIEW.md](ABK_KO_WORKFLOW_REVIEW.md)。

目标构建仍需核对：

- `uname -a` 与 `uname -r`
- `uname -m` 为 `aarch64`
- 对应内核源码 commit / vendor tag
- 最终构建 `.config` 和生成头文件，与已导出配置的关系
- 原始 `Module.symvers`，或使用恢复的内置导出表核对模块全部导入
- LLVM/Clang 版本及 Android kernel build 环境
- `CONFIG_MODULES`、`CONFIG_MODVERSIONS`、`CONFIG_HAVE_HW_BREAKPOINT`

典型外部模块构建形式（路径必须替换为该机型匹配的内核构建目录）：

```sh
make -C "$KERNEL_OUT" M="$PWD/driver" ARCH=arm64 LLVM=1 modules
```

不要通过在模块 Makefile 中强制定义 `CONFIG_HAVE_HW_BREAKPOINT` 来伪造目标内核配置。若目标内核未启用该能力，驱动应明确报告硬件断点不可用。

## GitHub Actions 的 GKI 构建

CI 使用 AOSP GKI manifest 选定通用内核源码，完整构建生成
`Module.symvers`，再用同一内核配置编译外部模块。`kernel_release`
和 `localversion_suffix` 是对 Kbuild 生成版本的可选校验。
产物文件名包含 GKI 分支。随包的元数据会记录源码 commit、实际
`kernelrelease`、`vermagic`、编译器和用于构建的 `Module.symvers`
校验值。

通用 GKI CI 的产物尚不能作为这份 ABK 手机内核的匹配产物。
用户 KO 工作流及其核心脚本已核查并在本地修订，采用同一内核构建目标的
Kleaf 外部模块接口或经典 build.sh 流程。下一步运行完整 Linux 构建及目标
CRC/release 校验，不通过改写模块版本串或 CRC 替代 ABI 验证。
# 2026-11-18 构建版本信息修正

后续运行 44146552447 已恢复 Git 和构建标签，余下差异为上游固定的 12 位 Git 缩写与目标镜像的 13 位缩写。工作流使用 `kernel_workspace_status.py` 包装上游 workspace status，只调整 common 的 Git 缩写长度，且必须同时匹配实际 40 位 HEAD、原始 stamp 和目标 release 中的 Git 片段。保留 dirty 标记、其他项目元数据、时间戳及构建标签。该脚本和哈希进入构建溯源产物，4 项回归覆盖正确缩写、错误源码拒绝和 dirty 保留。

Actions 44142501274 已完成模块编译，62 个版本符号的 CRC 全部匹配目标 Image；交付阶段因 `6.1.138-android14-11-maybe-dirty` 与目标完整 release 不同而失败。

Kleaf 默认关闭 stamping 时会生成 `-maybe-dirty`。工作流现在显式启用 `--config=stamp`，通过固定的 common 提交生成 Git 后缀，并将原始 Image 的构建标签设为 `BUILD_NUMBER=10023717-4k`。这是在源码构建阶段重建版本标签，CRC 参考文件、编译完成的 ELF 和最终校验均不改写。该标签及 release/CRC 匹配不证明签名、完整 ABI 或设备运行安全。其他目标应填写其原始构建标签或清空 `kernel_build_number`。

