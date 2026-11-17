"""Build-boundary tests using an LLVM-produced ARM64 ELF and mock build tools.

These exercise packaging and verification, not an actual kernel/module build.
Set QW_TEST_CLANG and QW_TEST_BASH when these tools are not on PATH.
"""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("verify_ko", ROOT / "scripts/verify_ko.py")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class PipelineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.clang = os.environ.get("QW_TEST_CLANG") or shutil.which("clang")
        cls.bash = os.environ.get("QW_TEST_BASH") or shutil.which("bash")
        if not cls.clang or not cls.bash:
            raise unittest.SkipTest("Clang and Bash are required")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qw-ko-tests-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.fixture = self.base / "fixture.ko"
        self.make_fixture(self.fixture)
        self.symvers = self.base / "Module.symvers"
        self.symvers.write_text(
            "0x12345678\texternal_api\tvmlinux\tEXPORT_SYMBOL_GPL\t\n"
            "0x87654321\tmodule_layout\tvmlinux\tEXPORT_SYMBOL\t\n", encoding="utf-8")

    def make_fixture(self, dest, versions=True, license="GPL", import_ns=""):
        assembly = '.text\n.globl init_module\n.type init_module,%function\ninit_module:\nb external_api\nret\n'
        assembly += '.section .modinfo,"a",%progbits\n'
        for text in ("vermagic=6.1.138-test SMP preempt mod_unload modversions aarch64", "license=" + license):
            assembly += ".asciz " + json.dumps(text) + "\n"
        if import_ns:
            assembly += ".asciz " + json.dumps("import_ns=" + import_ns) + "\n"
        if versions:
            assembly += '.section __versions,"a",%progbits\n.balign 8\n'
            for name, crc in (("external_api", 0x12345678), ("module_layout", 0x87654321)):
                assembly += f'.quad {crc}\n.asciz "{name}"\n.zero {56 - len(name) - 1}\n'
        source = dest.with_suffix(".S")
        source.write_text(assembly, encoding="utf-8")
        subprocess.run([self.clang, "--target=aarch64-linux-gnu", "-c", str(source), "-o", str(dest)],
                       capture_output=True, text=True, check=True)

    def test_real_elf_crc_and_release_match(self):
        report = VERIFY.verify(self.fixture, self.symvers, "6.1.138-test")
        self.assertEqual(report["status"], "pass")
        self.assertEqual(report["version_count"], 2)
        self.assertEqual(report["strong_imports"], ["external_api"])

    def test_crc_and_release_mismatch(self):
        self.symvers.write_text(self.symvers.read_text().replace("12345678", "00000001"))
        report = VERIFY.verify(self.fixture, self.symvers, "wrong-release")
        self.assertEqual(report["status"], "fail")
        self.assertTrue(any("crc_mismatch" in x for x in report["errors"]))
        self.assertTrue(any("release mismatch" in x for x in report["errors"]))

    def test_missing_versions_rejected_for_target(self):
        self.make_fixture(self.fixture, versions=False)
        report = VERIFY.verify(self.fixture, self.symvers)
        self.assertEqual(report["status"], "fail")
        self.assertIn("Unversioned strong import: external_api", report["errors"])

    def test_missing_export_rejected(self):
        self.symvers.write_text(self.symvers.read_text().splitlines()[1] + "\n")
        report = VERIFY.verify(self.fixture, self.symvers)
        self.assertTrue(any("not_exported" in x for x in report["errors"]))

    def test_gpl_and_namespace_requirements(self):
        self.make_fixture(self.fixture, license="Proprietary")
        self.assertIn("external_api: gpl_license_required", VERIFY.verify(self.fixture, self.symvers)["errors"])
        self.make_fixture(self.fixture)
        self.symvers.write_text(self.symvers.read_text().replace("EXPORT_SYMBOL_GPL\t", "EXPORT_SYMBOL_GPL\tTEST_NS"))
        self.assertIn("external_api: namespace_not_imported", VERIFY.verify(self.fixture, self.symvers)["errors"])
        self.make_fixture(self.fixture, import_ns="TEST_NS")
        self.assertEqual(VERIFY.verify(self.fixture, self.symvers)["status"], "pass")

    def test_truncated_elf_and_duplicate_reference_rejected(self):
        broken = self.base / "broken.ko"
        broken.write_bytes(self.fixture.read_bytes()[:70])
        with self.assertRaises(ValueError):
            VERIFY.verify(broken, self.symvers)
        self.symvers.write_text(self.symvers.read_text() * 2)
        with self.assertRaises(ValueError):
            VERIFY.verify(self.fixture, self.symvers)

    def shell_environment(self):
        env = os.environ.copy()
        env.update({"ABK_KO_LTO": "thin", "ABK_KO_TARGET_SYMVERS": str(self.symvers),
                    "ABK_KO_EXPECTED_RELEASE": "6.1.138-test", "QW_TEST_FIXTURE": str(self.fixture)})
        for key in ("ABK_KO_BAZEL_ARGS", "ABK_KO_BAZEL_ARGS_JSON", "ABK_KO_EXTRA_CFLAGS",
                    "ABK_KO_KERNEL_BUILD", "ABK_KO_BUILD_CONFIG"):
            env.pop(key, None)
        if os.name == "nt":
            # Git Bash bridges POSIX paths to native Windows Python for local tests.
            bindir = self.base / "test-bin"
            bindir.mkdir(exist_ok=True)
            wrapper = """#!/usr/bin/env bash
for key in QW_KO_SOURCE QW_KO_STAGE QW_KO_OUTPUT QW_KO_DIST QW_KO_KERNEL_ROOT; do
  value=${!key-}
  if [[ "$value" =~ ^/[a-z]/ ]]; then export "$key=$(cygpath -m "$value")"; fi
done
args=()
for arg in "$@"; do
  if [[ "$arg" =~ ^/[a-z]/ ]]; then args+=("$(cygpath -m "$arg")"); else args+=("$arg"); fi
done
export MSYS2_ARG_CONV_EXCL='*'
exec PYTHON_EXECUTABLE "${args[@]}"
""".replace("PYTHON_EXECUTABLE", shlex.quote(sys.executable.replace("\\", "/")))
            (bindir / "python3").write_text(wrapper, encoding="utf-8", newline="\n")
            env["PATH"] = str(bindir) + os.pathsep + env["PATH"]
        return env

    def run_pipeline(self, *, mode="kleaf", directory=False, makefile_only=False,
                     extra=None, no_output=False, build_fail=False, existing=False, source_contains_kernel=False):
        kernel = self.base / "kernel"
        (kernel / "common").mkdir(parents=True)
        if directory:
            src = self.base / "source"
            src.mkdir()
            (src / "testmod.c").write_text("/* fixture source */\n")
            (src / ("Makefile" if makefile_only else "Kbuild")).write_text("obj-m += testmod.o\n")
            (src / "stale.ko").write_bytes(b"stale")
        else:
            src = self.base / "source with spaces.c"
            src.write_text("/* fixture source */\n")
        out = self.base / "output"
        out.mkdir()
        if existing:
            (out / "testmod.ko").write_bytes(b"old output")
        (kernel / "unrelated").mkdir()
        (kernel / "unrelated/testmod.ko").write_bytes(b"unrelated stale module")
        if source_contains_kernel:
            src = kernel
        if mode == "kleaf":
            tool = kernel / "tools/bazel"
            tool.parent.mkdir()
            tool.write_text("""#!/usr/bin/env bash
set -euo pipefail
printf '%s\\0' "$@" >> mock-arguments.bin
if [[ "$1" == build ]]; then
  [[ ${QW_TEST_FAIL:-0} == 0 ]] || exit 42
  [[ ${QW_TEST_NO_OUTPUT:-0} == 0 ]] || exit 0
  mkdir -p declared-output
  cp "$QW_TEST_FIXTURE" declared-output/testmod.ko
elif [[ "$1" == cquery ]]; then
  [[ ${QW_TEST_NO_OUTPUT:-0} == 0 ]] || exit 0
  if command -v cygpath >/dev/null; then cygpath -m "$PWD/declared-output/testmod.ko"; else printf '%s\\n' "$PWD/declared-output/testmod.ko"; fi
fi
""", encoding="utf-8", newline="\n")
        else:
            tool = kernel / "build/build.sh"
            tool.parent.mkdir()
            tool.write_text("""#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$LTO" > mock-lto.txt
printf '%s\\n' "$EXT_MODULES" > mock-ext-modules.txt
[[ ${QW_TEST_FAIL:-0} == 0 ]] || exit 42
[[ ${QW_TEST_NO_OUTPUT:-0} == 0 ]] || exit 0
cp "$QW_TEST_FIXTURE" "$DIST_DIR/testmod.ko"
""", encoding="utf-8", newline="\n")
        tool.chmod(0o755)
        env = self.shell_environment()
        env.update({"QW_TEST_NO_OUTPUT": str(int(no_output)), "QW_TEST_FAIL": str(int(build_fail))})
        env.update(extra or {})
        result = subprocess.run([self.bash, str(ROOT / "scripts/build_external_ko.sh").replace("\\", "/"),
                                 "--kernel-root", str(kernel), "--source", str(src),
                                 "--module-name", "testmod", "--output-dir", str(out)],
                                env=env, capture_output=True, text=True, timeout=60)
        return result, kernel, out

    def test_kleaf_output_and_quoted_arguments(self):
        result, kernel, out = self.run_pipeline(extra={"ABK_KO_BAZEL_ARGS_JSON": '["--define=message=hello world"]'})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((out / "testmod.ko").read_bytes(), self.fixture.read_bytes())
        args = (kernel / "mock-arguments.bin").read_bytes().split(b"\0")
        self.assertIn(b"--define=message=hello world", args)
        self.assertEqual(json.loads((out / "metadata.json").read_text())["target_crc_checked"], True)

    def test_directory_flags_and_stale_object_exclusion(self):
        result, kernel, _ = self.run_pipeline(directory=True, extra={"ABK_KO_EXTRA_CFLAGS": "-DTEST_FLAG=1"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        stage = next(kernel.glob("abk_external_testmod_*"))
        self.assertIn("ccflags-y += -DTEST_FLAG=1", (stage / "Kbuild").read_text())
        self.assertFalse((stage / "stale.ko").exists())

    def test_makefile_only_directory_is_wrapped(self):
        result, kernel, _ = self.run_pipeline(directory=True, makefile_only=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        stage = next(kernel.glob("abk_external_testmod_*"))
        self.assertIn("obj-m += testmod.o", (stage / "Kbuild").read_text())
        self.assertIn("modules_install", (stage / "Makefile").read_text())

    def test_classic_mode_honors_lto(self):
        result, kernel, out = self.run_pipeline(mode="classic", extra={"ABK_KO_LTO": "none"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((kernel / "mock-lto.txt").read_text().strip(), "none")
        self.assertTrue((out / "testmod.ko").exists())

    def test_absent_current_output_never_falls_back_to_stale_module(self):
        result, _, out = self.run_pipeline(no_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((out / "testmod.ko").exists())

    def test_build_failure_propagates(self):
        result, _, out = self.run_pipeline(build_fail=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((out / "testmod.ko").exists())

    def test_target_mismatch_prevents_publication(self):
        result, _, out = self.run_pipeline(extra={"ABK_KO_EXPECTED_RELEASE": "wrong"})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(json.loads((out / "abi-report.json").read_text())["status"], "fail")
        self.assertFalse((out / "testmod.ko").exists())

    def test_existing_output_is_not_reused_or_overwritten(self):
        result, _, out = self.run_pipeline(existing=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((out / "testmod.ko").read_bytes(), b"old output")

    def test_generic_mode_records_missing_target_verification(self):
        result, _, out = self.run_pipeline(extra={"ABK_KO_TARGET_SYMVERS": "", "ABK_KO_EXPECTED_RELEASE": ""})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(json.loads((out / "metadata.json").read_text())["target_crc_checked"])

    def test_extra_arguments_cannot_silently_override_lto(self):
        result, _, out = self.run_pipeline(extra={"ABK_KO_BAZEL_ARGS_JSON": '["--lto=none"]'})
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((out / "testmod.ko").exists())

    def test_source_containing_staging_is_rejected(self):
        result, _, out = self.run_pipeline(source_contains_kernel=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("contains the build staging", result.stderr)
        self.assertFalse((out / "testmod.ko").exists())

    def test_classic_missing_output_does_not_use_unrelated_module(self):
        result, _, out = self.run_pipeline(mode="classic", no_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((out / "testmod.ko").exists())

    def test_qingwei_makefile_forwards_kleaf_arguments(self):
        make = os.environ.get("QW_TEST_MAKE") or shutil.which("make")
        if not make:
            self.skipTest("GNU Make is required for wrapper forwarding test")
        kernel = self.base / "fake-kernel"
        kernel.mkdir()
        (kernel / "Makefile").write_text(
            ".PHONY: modules_install\nmodules_install:\n\t@echo M=$(M)\n\t@echo O=$(O)\n\t@echo LLVM=$(LLVM)\n")
        result = subprocess.run([make, "-f", str(ROOT / "driver/Makefile"),
                                 "KERNEL_SRC=" + str(kernel), "M=../external", "O=build-out", "LLVM=1",
                                 "modules_install"], cwd=self.base, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for value in ("M=../external", "O=build-out", "LLVM=1"):
            self.assertIn(value, result.stdout)


if __name__ == "__main__":
    unittest.main()
