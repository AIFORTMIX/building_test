# Qingwei KO 编译流程修复与使用

更新于 2026-11-18。原始下载文件保持不变，修订版本放在本项目中。
已完成构建脚本修正、本地与云端回归和完整 Actions 内核构建。
[运行 44245345141](https://github.com/AIFORTMIX/building_test/actions/runs/44245345141)生成的 KO 完整 release 与 62 个版本符号 CRC 均匹配目标镜像。手机装载、签名策略、完整 ABI 和运行稳定性仍未验收。

## 修正内容

- 传统 build.sh 与 Kleaf 都采用请求的 LTO，不再在传统分支硬编码 thin。
- 单文件、Kbuild 目录、仅 Makefile 目录均可输入；目录模式也应用额外 CFLAGS。
- 每次构建使用独立源码副本，排除旧的对象文件和模块；拒绝输入目录包含构建副本，
  避免递归复制。原始模块源码不被修改。
- Kleaf 通过 cquery 取得当前目标声明的输出；传统路径只读取本次 dist。
  不再扫描整个内核目录选择第一个同名 KO，也能处理 Bazel 输出符号链接。
- 新输出目录必须为空；构建失败、缺失当前产物、歧义产物或验证失败时不发布最终 KO。
- 解析生成模块的 ELF、未定义符号、modversions、license 和 import_ns。
  手机模式逐项比较目标导出和 CRC，检查完整 kernel release，并记录完整 vermagic。
  不通过覆盖 CRC、修改 ELF 或忽略 modpost 错误来通过检查。
- 移除工作流中占位 BTF、pahole 无条件成功和静默禁用 BTF 的回退。
  真实工具或配置问题保留为构建失败及诊断信息。
- 支持固定源码 common commit，检查实际子版本，并保存 resolved manifest、
  源码差异、模块源码哈希和构建元数据。
- Qingwei Makefile 支持 Kleaf 提供的 KERNEL_SRC、M、O 和 modules_install。
- 保留单文件/目录模式、额外选项、LTO、localversion、版本矩阵、LTS、缓存及 swap。

## GitHub Actions 使用

将修复包内容按原路径放到要运行的仓库根目录。主要文件为：

```text
.github/workflows/build-ko-module.yml
scripts/build_external_ko.sh
scripts/verify_ko.py
driver/qingwei_mcp.c
driver/Kbuild
driver/Makefile
build-inputs/android14-6.1.138/
data/
tests/build/
```

在 Actions 中选择“编译自定义 KO 模块”，运行单版本构建。手机参考参数已经预填：

| 输入 | 默认值 |
| --- | --- |
| source_path | driver |
| module_name | qingwei_mcp |
| android_version / kernel_version | android14 / 6.1 |
| sub_level / os_patch_level | 138 / 2025-06 |
| bazel_lto | thin |
| kernel_commit | 5929004f86ffc02c0b750715ec5a2b1541d14065 |
| target_symvers | build-inputs/android14-6.1.138/Module.symvers |
| expected_kernel_release | 6.1.138-android14-11-g5929004f86ffc-ab10023717-4k |

其中 commit 是版本串对应的 AOSP 基线，尚未证明包含手机构建时的全部 ABK 补丁。
工具链由该 manifest / kernel build 配置选择，基线 build.config.constants
固定 Clang r487747c，与 Image 记录一致。

`kernel_localversion` 保留为可选的源代码构建后缀输入，不是兼容性保证。
不同构建环境可能生成不同的完整 release。若 release 或 CRC 检查失败，
应根据原始构建的命名参数、补丁、真实配置和生成头文件调整构建环境；
不要把关掉检查或替换 CRC 当作手机适配完成。

成功产物包含 KO、abi-report.json、metadata.json/txt、源码哈希和 manifest。
失败产物只上传构建日志及检查报告，不打包本次失败生成的 KO。
遇到 BTF/工具链错误，应保留日志修复真实原因，不再生成占位数据。

批量通用构建时，将 `target_symvers`、`expected_kernel_release`、`kernel_commit`
三个手机限定输入清空，再开启 build_all_known；include_lts 可保留。
单独构建 LTS 也可指定 X / lts。通用模式元数据会明确标记没有手机 CRC 验证。

## 本地调用

需要 Linux 和包含 common、tools/bazel 或 build/build.sh 的 Android 内核 workspace。
脚本在 workspace 中沿用其实际内核构建目标，不读取 Image 里的配置来覆盖构建配置。

```sh
export KERNEL_ROOT
export BUILD_NUMBER=10023717-4k
cp scripts/kernel_workspace_status.py "$KERNEL_ROOT/qw_workspace_status.py"
chmod +x "$KERNEL_ROOT/qw_workspace_status.py"
export ABK_KO_BAZEL_ARGS_JSON=$(python3 -c 'import json,os; print(json.dumps(["--config=stamp", "--workspace_status_command=" + os.path.join(os.environ["KERNEL_ROOT"], "qw_workspace_status.py")]))')
export ABK_KO_LTO=thin
export ABK_KO_TARGET_SYMVERS="$PWD/build-inputs/android14-6.1.138/Module.symvers"
export ABK_KO_EXPECTED_RELEASE=6.1.138-android14-11-g5929004f86ffc-ab10023717-4k
bash scripts/build_external_ko.sh \
  --kernel-root "$KERNEL_ROOT" --source driver \
  --module-name qingwei_mcp --output-dir "$PWD/ko-output/new-run"
```

额外 Bazel 选项可使用 ABK_KO_BAZEL_ARGS_JSON 字符串数组，旧的
ABK_KO_BAZEL_ARGS 使用 shlex 分词且不执行 eval。LTO 由 ABK_KO_LTO 指定，
不允许在额外选项里重复覆盖。

## 已执行的验证

- 24 项构建回归在 Linux Actions 通过，其中 5 项验证版本生成脚本；另 19 项：使用 Clang 生成的真实 AArch64 ELF 测试 CRC、release、GPL、
  namespace、未版本化导入及损坏输入；使用模拟构建工具测试两个构建分支的
  产物选择、失败传播、目录选项、LTO、通用模式和 Makefile 参数传递。
- 之前的真实 Qingwei ARM64 Debian 验证 KO，在它对应的 Debian symvers 上
  56 项版本检查通过；对手机恢复表和 release 进行检查时失败，包含 30 项错误。
  这验证了旧对象不会被新检查误认为手机匹配产物。
- 10 组工作流矩阵检查通过，覆盖五种 Android/KMI 批量含 LTS、手机默认值、
  单独 LTS，以及拒绝混用手机输入和不支持的组合。
- actionlint 1.7.12 检查新工作流通过；本次未启用其可选 ShellCheck/pyflakes。
- Bash 语法检查和原有 C/Kotlin ABI/source 回归通过。

完整内核构建已在 Actions 执行，本机 Windows 未重复完整内核编译。CRC/release 检查不能证明 CFI、签名策略、
所有结构体偏移或手机运行稳定性都已通过。后续依次验收加载/卸载、内存操作、
进程退出、断点生命周期、并发与持续内存压力。

接口依据：[Kleaf 外部模块实现](https://android.googlesource.com/kernel/build/+/346241d8c7d74ba03b68c5275f8e1545b0838daa/kleaf/impl/kernel_module.bzl)、
[内核模块版本检查](https://docs.kernel.org/kbuild/modules.html#module-versioning)。
