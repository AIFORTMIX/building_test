# Qingwei App 与驱动构建

本仓库用于通过 GitHub Actions 编译 Qingwei ARM64 内核模块，并检查生成模块
是否匹配所提供手机镜像的导出符号 CRC 和完整 kernel release。

0.4.0 完善 Miuix 毛玻璃界面、实际思考内容与正文时间线、详细用量面板和会话工作区；工具长结果完整保存并支持分页读取。外部 MCP 通过独立前台服务保活，恢复时保留端口和令牌，慢操作返回可查询的任务 ID。进程身份改变后停止沿用旧地址。使用和限制见 [Agent App 说明](docs/AGENT_APP.md)。

毛玻璃组件、弹窗模糊的实现与迁移方法见 [毛玻璃与弹窗模糊实现方案](docs/毛玻璃与弹窗模糊实现方案.md)。

驱动此前已在 [构建 44245345141](https://github.com/AIFORTMIX/building_test/actions/runs/44245345141) 通过完整 release 和 62 项版本符号 CRC 匹配。本次保持 KO、JNI 与 ioctl ABI 不变，无需重编译 KO。2026-11-18 用户反馈 0.2.0 真机基础验收通过、可以正常使用；长期稳定性和压力场景仍需持续验证。

[App 工作流](https://github.com/AIFORTMIX/building_test/actions/workflows/build-app.yml) 自动执行构建和模拟 AI → MCP → 设备边界联调、Android UI/JNI 检查。[0.4.0 验收构建 44551789205](https://github.com/AIFORTMIX/building_test/actions/runs/44551789205) 已通过 103 项 host 回归、26 项 Android 检查及 Debug/Release/Lint，并核对 APK 实际版本和主 App/测试包的 Compose 版本一致性。正式安装只需 Artifact 中的固定签名 `qingwei.apk`，不需要安装测试 APK 或另一个 MCP 宿主。具体证据见 [App 验证记录](docs/APP_VALIDATION.md)。

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
批量通用构建需要清空 `target_symvers`、`expected_kernel_release`、`kernel_commit` 和 `kernel_build_number`，
再启用 `build_all_known`。通用模式会记录未执行手机 CRC 验证。

推送到 main 和提交 PR 时自动运行 **Validate build tools**，检查构建脚本及 24 项回归。
完整内核编译通过手动工作流启动。

详细参数、已完成验证和限制见 [构建说明](docs/ABK_KO_BUILD.md)。
驱动保留原有 GPL-2.0 声明；版本目录来源和上游说明见 [data/README.md](data/README.md)。
