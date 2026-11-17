#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: build_external_ko.sh --kernel-root DIR --source FILE_OR_DIR --module-name NAME --output-dir DIR
Environment:
  ABK_KO_BUILD_CONFIG       Classic BUILD_CONFIG (default common/build.config.gki.aarch64)
  ABK_KO_LTO                thin (default) or none; honored by both build paths
  ABK_KO_EXTRA_CFLAGS       Additional ccflags-y for file and directory inputs
  ABK_KO_BAZEL_ARGS         Extra arguments parsed with shlex, never eval
  ABK_KO_BAZEL_ARGS_JSON    JSON string array; preferred over ABK_KO_BAZEL_ARGS
  ABK_KO_BAZEL_DISK_CACHE   Bazel disk cache (default $HOME/.cache/bazel)
  ABK_KO_KERNEL_BUILD       Kleaf target (default //common:kernel_aarch64)
  ABK_KO_TARGET_SYMVERS     Optional read-only target CRC reference
  ABK_KO_EXPECTED_RELEASE   Optional exact kernel release check
An empty output directory is required. No module is delivered if verification fails.
EOF
}
die() { echo "::error::$*" >&2; exit 1; }
kernel_root= source_path= module_name= output_dir=
while (($#)); do
  case "$1" in
    --kernel-root|--source|--module-name|--output-dir)
      (($# >= 2)) && [[ -n "$2" ]] || die "Missing value for $1"
      case "$1" in
        --kernel-root) kernel_root=$2 ;;
        --source) source_path=$2 ;;
        --module-name) module_name=$2 ;;
        --output-dir) output_dir=$2 ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done
[[ -n "$kernel_root" && -n "$source_path" && -n "$module_name" && -n "$output_dir" ]] || { usage; exit 2; }
[[ "$module_name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || die "Invalid module name"
lto=${ABK_KO_LTO:-thin}
[[ "$lto" == thin || "$lto" == none ]] || die "Unsupported LTO mode: $lto"
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
kernel_root=$(realpath -e -- "$kernel_root")
source_path=$(realpath -e -- "$source_path")
[[ -d "$kernel_root/common" ]] || die "Missing kernel common directory"
mkdir -p -- "$output_dir"
output_dir=$(realpath -e -- "$output_dir")
[[ -z $(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit) ]] || die "Output directory must be empty"
target_args=()
if [[ -n ${ABK_KO_TARGET_SYMVERS:-} ]]; then
  target_symvers=$(realpath -e -- "$ABK_KO_TARGET_SYMVERS")
  [[ -s "$target_symvers" ]] || die "Empty target symvers"
  target_args+=(--target-symvers "$target_symvers")
fi
if [[ -n ${ABK_KO_EXPECTED_RELEASE:-} ]]; then
  target_args+=(--expected-release "$ABK_KO_EXPECTED_RELEASE")
fi
# A fresh package prevents concurrent jobs and stale objects from sharing state.
module_dir=$(mktemp -d "$kernel_root/abk_external_${module_name}_XXXXXXXX")
package_name=$(basename -- "$module_dir")
export QW_KO_SOURCE="$source_path" QW_KO_STAGE="$module_dir" QW_KO_NAME="$module_name"
export QW_KO_OUTPUT="$output_dir"
python3 - <<'PY'
from pathlib import Path
import hashlib, json, os, shutil
src = Path(os.environ["QW_KO_SOURCE"])
dest = Path(os.environ["QW_KO_STAGE"])
name = os.environ["QW_KO_NAME"]
if src.is_file():
    if src.suffix != ".c":
        raise SystemExit("Single-file source must be a .c file")
    shutil.copy2(src, dest / (name + ".c"))
elif src.is_dir():
    if dest.resolve().is_relative_to(src.resolve()):
        raise SystemExit("Source directory contains the build staging directory")
    shutil.copytree(src, dest, dirs_exist_ok=True, symlinks=True,
                    ignore=shutil.ignore_patterns(".git", "*.ko", "*.o", "*.mod", "*.mod.c",
                                                  ".*.cmd", "Module.symvers", "modules.order"))
else:
    raise SystemExit("Source does not exist")
kbuild, makefile = dest / "Kbuild", dest / "Makefile"
if makefile.is_file() and not kbuild.is_file():
    # A conventional obj-m-only Makefile is valid Kbuild input, but Kleaf
    # invokes make in the module directory and also needs a wrapper target.
    shutil.copy2(makefile, kbuild)
    makefile.unlink()
if not kbuild.is_file() and not makefile.is_file():
    if not (dest / (name + ".c")).is_file():
        raise SystemExit("Directory needs Kbuild/Makefile or the named C source")
    kbuild.write_text("obj-m += " + name + ".o\n", encoding="utf-8")
flags = os.environ.get("ABK_KO_EXTRA_CFLAGS", "")
if "\n" in flags or "\r" in flags:
    raise SystemExit("Extra CFLAGS must be a single line")
if flags:
    with (kbuild if kbuild.is_file() else makefile).open("a", encoding="utf-8") as f:
        f.write("\nccflags-y += " + flags + "\n")
if not makefile.is_file():
    makefile.write_text("""KERNEL_SRC ?= /lib/modules/$(shell uname -r)/build
M ?= $(CURDIR)
.PHONY: all modules modules_install clean
all: modules
modules modules_install clean:
\t$(MAKE) -C "$(KERNEL_SRC)" M="$(M)" $@
""", encoding="utf-8")
files = {str(p.relative_to(dest)): hashlib.sha256(p.read_bytes()).hexdigest()
         for p in sorted(dest.rglob("*")) if p.is_file()}
(Path(os.environ["QW_KO_OUTPUT"]) / "source-manifest.json").write_text(
    json.dumps(files, indent=2) + "\n", encoding="utf-8")
PY
echo "Module source staged at: $module_dir"
build_mode=kleaf
if [[ -f "$kernel_root/build/build.sh" ]]; then
  build_mode=build.sh
  dist_dir=$(mktemp -d "$kernel_root/abk_dist_${module_name}_XXXXXXXX")
  (
    cd "$kernel_root"
    LTO="$lto" BUILD_CONFIG="${ABK_KO_BUILD_CONFIG:-common/build.config.gki.aarch64}" \
      EXT_MODULES="$package_name" DIST_DIR="$dist_dir" build/build.sh
  ) 2>&1 | tee "$output_dir/build.log"
  # Only the dist directory from THIS invocation can supply an artifact.
  export QW_KO_DIST="$dist_dir"
  python3 - <<'PY' > "$output_dir/candidates.txt"
import os
from pathlib import Path
root = Path(os.environ["QW_KO_DIST"])
for p in sorted(root.rglob(os.environ["QW_KO_NAME"] + ".ko")):
    if p.is_file():
        print(p.resolve())
PY
else
  [[ -x "$kernel_root/tools/bazel" ]] || die "No supported build entry point"
  kernel_build=${ABK_KO_KERNEL_BUILD:-//common:kernel_aarch64}
  [[ "$kernel_build" =~ ^//[A-Za-z0-9_./+-]*:[A-Za-z0-9_.+-]+$ ]] || die "Invalid kernel build label"
  export QW_KO_KERNEL_BUILD="$kernel_build"
  python3 - <<'PY'
import json, os
from pathlib import Path
name = os.environ["QW_KO_NAME"] + "_module"
content = ('load("//build/kernel/kleaf:kernel.bzl", "kernel_module")\n'
           'kernel_module(\n    name = ' + json.dumps(name) + ',\n'
           '    srcs = glob(["**"], exclude = ["BUILD.bazel"]),\n'
           '    outs = [' + json.dumps(os.environ["QW_KO_NAME"] + ".ko") + '],\n'
           '    kernel_build = ' + json.dumps(os.environ["QW_KO_KERNEL_BUILD"]) + ',\n)\n')
(Path(os.environ["QW_KO_STAGE"]) / "BUILD.bazel").write_text(content, encoding="utf-8")
PY
  python3 - <<'PY' > "$output_dir/bazel-extra-args.bin"
import json, os, shlex, sys
raw = os.environ.get("ABK_KO_BAZEL_ARGS_JSON")
args = json.loads(raw) if raw else shlex.split(os.environ.get("ABK_KO_BAZEL_ARGS", ""))
if not isinstance(args, list) or not all(isinstance(x, str) and "\0" not in x for x in args):
    raise SystemExit("Bazel arguments must be a string array")
if any(x == "--lto" or x.startswith("--lto=") for x in args):
    raise SystemExit("Use ABK_KO_LTO instead of overriding --lto in extra arguments")
for arg in args:
    sys.stdout.buffer.write(arg.encode() + b"\0")
PY
  mapfile -d '' -t extra_args < "$output_dir/bazel-extra-args.bin"
  bazel_args=("--disk_cache=${ABK_KO_BAZEL_DISK_CACHE:-${HOME}/.cache/bazel}" --config=fast "--lto=$lto" "${extra_args[@]}")
  target="//$package_name:${module_name}_module"
  (
    cd "$kernel_root"
    tools/bazel build "${bazel_args[@]}" "$target"
    # cquery follows the target's declared output, including symlinked bazel-bin.
    tools/bazel cquery "${bazel_args[@]}" --output=files "$target" > "$output_dir/candidates.txt"
  ) 2>&1 | tee "$output_dir/build.log"
fi
export QW_KO_KERNEL_ROOT="$kernel_root" QW_KO_MODE="$build_mode" QW_KO_LTO="$lto"
python3 - <<'PY' > "$output_dir/selected-module.txt"
from pathlib import Path
import hashlib, os
root = Path(os.environ["QW_KO_KERNEL_ROOT"])
out = Path(os.environ["QW_KO_OUTPUT"])
name = os.environ["QW_KO_NAME"] + ".ko"
candidates = set()
for line in (out / "candidates.txt").read_text(encoding="utf-8").splitlines():
    p = Path(line.strip())
    if p.name != name:
        continue
    p = (p if p.is_absolute() else root / p).resolve()
    if p.is_file():
        candidates.add(p)
if not candidates:
    raise SystemExit("No declared module output from this build")
if len({hashlib.sha256(p.read_bytes()).hexdigest() for p in candidates}) != 1:
    raise SystemExit("Ambiguous module outputs with different content")
print(sorted(candidates)[0])
PY
selected_ko=$(cat "$output_dir/selected-module.txt")
python3 "$script_dir/verify_ko.py" "$selected_ko" "${target_args[@]}" --report "$output_dir/abi-report.json"
export QW_KO_SELECTED="$selected_ko" QW_KO_TARGET_SYMVERS="${ABK_KO_TARGET_SYMVERS:-}"
python3 - <<'PY'
from pathlib import Path
import hashlib, json, os, subprocess
root = Path(os.environ["QW_KO_KERNEL_ROOT"])
out = Path(os.environ["QW_KO_OUTPUT"])
report = json.loads((out / "abi-report.json").read_text(encoding="utf-8"))
module_data = Path(os.environ["QW_KO_SELECTED"]).read_bytes()
if hashlib.sha256(module_data).hexdigest() != report["module_sha256"]:
    raise SystemExit("Module changed after verification")
git = subprocess.run(["git", "-C", str(root / "common"), "rev-parse", "HEAD"],
                     capture_output=True, text=True)
metadata = {"module_name": os.environ["QW_KO_NAME"], "source": os.environ["QW_KO_SOURCE"],
            "build_mode": os.environ["QW_KO_MODE"], "requested_lto": os.environ["QW_KO_LTO"],
            "kernel_commit": git.stdout.strip() if git.returncode == 0 else "unavailable",
            "vermagic": report["vermagic"], "sha256": report["module_sha256"],
            "target_crc_checked": report["target_crc_checked"],
            "target_symvers_sha256": report["target_symvers_sha256"],
            "runtime_validated": False, "full_abi_validated": False}
args_path = out / "bazel-extra-args.bin"
if args_path.exists():
    metadata["extra_bazel_arguments"] = [s.decode() for s in args_path.read_bytes().split(b"\0") if s]
(out / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
(out / "metadata.txt").write_text("".join(f"{k}={v}\n" for k, v in metadata.items()), encoding="utf-8")
# Atomic publication of the exact bytes checked above, after metadata succeeds.
temporary = out / (os.environ["QW_KO_NAME"] + ".ko.tmp")
temporary.write_bytes(module_data)
temporary.replace(out / (os.environ["QW_KO_NAME"] + ".ko"))
PY
echo "Build and requested checks passed: $output_dir/$module_name.ko"
