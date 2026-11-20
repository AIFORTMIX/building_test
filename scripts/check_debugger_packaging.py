"""Verify executable target ABIs/licenses and exclude native test fixtures from Release."""
from pathlib import Path
import struct
import sys
import zipfile

MACHINES = {"arm64-v8a": 183, "x86_64": 62}

def check(path, release):
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        for license in ("capstone.txt", "capstone-llvm.txt", "ndk-debugger-notice.txt"):
            if not archive.read("assets/licenses/" + license).strip():
                raise SystemExit("Missing native debugger license")
        for abi, machine in MACHINES.items():
            name = f"lib/{abi}/libqingwei_debugger.so"
            data = archive.read(name)
            if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != machine:
                raise SystemExit("Wrong debugger executable architecture: " + name)
            if struct.unpack_from("<Q", data, 24)[0] == 0:
                raise SystemExit("Debugger was packaged as a non-executable library")
            fixture = f"lib/{abi}/libqingwei_debug_fixture.so"
            if release and fixture in names:
                raise SystemExit("Debug-only fixture leaked into Release")
            if not release and fixture not in names:
                raise SystemExit("Native debugger fixture is missing from Debug")
    print("Debugger packaging: PASS (" + ("release" if release else "debug") + ")")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: check_debugger_packaging.py DEBUG_APK RELEASE_APK")
    check(Path(sys.argv[1]), False)
    check(Path(sys.argv[2]), True)
