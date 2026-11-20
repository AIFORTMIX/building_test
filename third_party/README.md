# Capstone

`capstone-5.0.6.tar.gz` 为上游未修改源码归档，供 CMake 离线构建 ARM64 解码器。

- 上游：https://github.com/capstone-engine/capstone
- 版本：5.0.6，commit `accf4df62f1fba6f92cae692985d27063552601c`
- 下载：https://codeload.github.com/capstone-engine/capstone/tar.gz/accf4df62f1fba6f92cae692985d27063552601c
- SHA-256：`ef6950c8276293a407fe374e99c95aa95faf25d022cc65974a52a01b79f5b4b3`
- 原文许可包含在归档内 `LICENSE.TXT` 与 `LICENSE_LLVM.TXT`；随 APK 分发的许可见 `app/src/main/assets/licenses/capstone.txt` 与 `capstone-llvm.txt`。

只启用 ARM64，不编译其命令行程序、测试程序或其他架构解码器。Android x86_64 测试包仍能解码 ARM64 文件，不把宿主架构误认为目标文件架构。
