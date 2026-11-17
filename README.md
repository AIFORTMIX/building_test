# Qingwei 驱动构建

本仓库用于通过 GitHub Actions 编译 Qingwei ARM64 内核模块，并检查生成模块
是否匹配所提供手机镜像的导出符号 CRC 和完整 kernel release。

## 运行编译

1. 打开 [编译自定义 KO 模块](https://github.com/AIFORTMIX/building_test/actions/workflows/build-ko-module.yml)。
2. 点击 **Run workflow**，选择 `main`。
3. 手机目标参数已经预填，确认参数后启动。
4. 运行结束后，在页面的 **Artifacts** 下载 KO 和校验报告；失败时下载 diagnostics 查看原因。

默认源码为 `driver/`，模块为 `qingwei_mcp`，目标为
`android14 / 6.1.138 / 2025-06`，LTO 为 `thin`。
基线 common commit 固定为 `5929004f86ffc02c0b750715ec5a2b1541d14065`。

手机参考 release：

```text
6.1.138-android14-11-g5929004f86ffc-ab10023717-4k
```

手机 CRC 参考表来自用户提供的 Image，共 8,152 个内核内置导出。
脚本在构建后比较 CRC，不改写生成模块的 CRC 或 vermagic。
如果原始 ABK 补丁、真实配置或构建命名参数存在差异，检查会中止并输出诊断。
通过 CRC/release 检查仍需继续进行签名、CFI、结构体布局及真机稳定性验收。

## 通用与批量构建

保留单个 C 文件、模块目录、额外编译参数、LTO、localversion、批量版本和 LTS 选项。
批量通用构建需要清空 `target_symvers`、`expected_kernel_release` 和 `kernel_commit`，
再启用 `build_all_known`。通用模式会记录未执行手机 CRC 验证。

推送到 main 和提交 PR 时自动运行 **Validate build tools**，检查构建脚本及 19 项回归。
完整内核编译通过手动工作流启动。

详细参数、已完成验证和限制见 [构建说明](docs/ABK_KO_BUILD.md)。
驱动保留原有 GPL-2.0 声明；版本目录来源和上游说明见 [data/README.md](data/README.md)。
