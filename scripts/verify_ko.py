#!/usr/bin/env python3
"""Inspect an ARM64 module and optionally check imports against target CRCs.

This checks ELF/module metadata, not runtime safety or full kernel ABI layout.
The target symvers is read-only: it is never used to patch a built module.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys


class Elf:
    def __init__(self, path):
        self.data = Path(path).read_bytes()
        if len(self.data) < 64 or self.data[:6] != b"\x7fELF\x02\x01":
            raise ValueError("Expected a little-endian ELF64 module")
        if self.unpack("<HH", 16) != (1, 183):
            raise ValueError("Expected an AArch64 relocatable ELF module")
        offset = self.unpack("<Q", 40)[0]
        size, count, names_index = self.unpack("<HHH", 58)
        if size != 64 or not count or names_index >= count:
            raise ValueError("Unsupported or invalid section table")
        self.section_headers = [self.unpack("<IIQQQQIIQQ", offset + i * size)
                                for i in range(count)]
        names = self.section_data(self.section_headers[names_index])
        self.sections = {}
        for header in self.section_headers:
            name = self.string(names, header[0])
            if name in self.sections and name in {
                ".symtab", ".modinfo", "__versions", "__version_ext_names", "__version_ext_crcs"
            }:
                raise ValueError("Duplicate section: " + name)
            self.sections[name] = header

    def unpack(self, fmt, offset):
        if offset < 0 or offset + struct.calcsize(fmt) > len(self.data):
            raise ValueError("Truncated ELF structure")
        return struct.unpack_from(fmt, self.data, offset)

    @staticmethod
    def string(data, offset):
        if not 0 <= offset < len(data):
            raise ValueError("Invalid ELF string offset")
        end = data.find(b"\0", offset)
        if end < 0:
            raise ValueError("Unterminated ELF string")
        return data[offset:end].decode("ascii")

    def section_data(self, header):
        offset, size = header[4:6]
        if offset > len(self.data) or size > len(self.data) - offset:
            raise ValueError("Truncated ELF section")
        return self.data[offset:offset + size]

    def get(self, name):
        header = self.sections.get(name)
        return self.section_data(header) if header is not None else b""

    def imports(self):
        header = self.sections.get(".symtab")
        if header is None or header[9] != 24 or header[6] >= len(self.section_headers):
            raise ValueError("Missing or invalid symbol table")
        strings = self.section_data(self.section_headers[header[6]])
        data = self.section_data(header)
        if len(data) % 24:
            raise ValueError("Invalid symbol table size")
        strong, weak = set(), set()
        for offset in range(0, len(data), 24):
            name, info, _, section, _, _ = struct.unpack_from("<IBBHQQ", data, offset)
            if name and section == 0 and info >> 4 in (1, 2):
                target = weak if info >> 4 == 2 else strong
                target.add(self.string(strings, name))
        return strong, weak

    def versions(self):
        if "__version_ext_names" in self.sections or "__version_ext_crcs" in self.sections:
            raise ValueError("Extended modversions require a separate verifier")
        data = self.get("__versions")
        if len(data) % 64:
            raise ValueError("Invalid basic __versions size")
        versions = {}
        for offset in range(0, len(data), 64):
            crc = struct.unpack_from("<Q", data, offset)[0]
            name = self.string(data[offset + 8:offset + 64], 0)
            if not name or name in versions or crc > 0xFFFFFFFF:
                raise ValueError("Invalid/duplicate modversion entry")
            versions[name] = crc
        return versions


def load_symvers(path):
    result = {}
    for index, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = line.split("\t")
        if len(row) not in (4, 5):
            raise ValueError(f"Invalid symvers row {index}")
        if len(row) == 4:
            row.append("")
        crc = int(row[0], 16)
        if row[1] in result or not 0 <= crc <= 0xFFFFFFFF:
            raise ValueError(f"Duplicate/invalid symvers row {index}")
        result[row[1]] = (crc, row[3], row[4])
    if not result:
        raise ValueError("Empty target symvers")
    return result


def verify(module, target_symvers=None, expected_release=None):
    elf = Elf(module)
    metadata = {}
    for item in elf.get(".modinfo").split(b"\0"):
        if b"=" in item:
            key, value = item.decode("utf-8").split("=", 1)
            metadata.setdefault(key, []).append(value)
    vermagic = metadata.get("vermagic", [])
    if len(vermagic) != 1 or not vermagic[0].strip():
        raise ValueError("Expected exactly one nonempty vermagic")
    strong, weak = elf.imports()
    versions = elf.versions()
    errors = []
    if expected_release and vermagic[0].split()[0] != expected_release:
        errors.append("kernel release mismatch: " + vermagic[0].split()[0])
    rows = []
    if target_symvers:
        exports = load_symvers(target_symvers)
        if "module_layout" not in versions:
            errors.append("Missing versioned module_layout")
        for name in sorted(strong - versions.keys()):
            errors.append("Unversioned strong import: " + name)
        gpl_licenses = {"GPL", "GPL v2", "GPL and additional rights", "Dual BSD/GPL",
                        "Dual MIT/GPL", "Dual MPL/GPL"}
        gpl = any(x in gpl_licenses for x in metadata.get("license", []))
        namespaces = set(metadata.get("import_ns", []))
        for name, crc in sorted(versions.items()):
            target = exports.get(name)
            status = "match"
            if target is None:
                status = "not_exported"
            elif target[0] != crc:
                status = "crc_mismatch"
            elif target[1] == "EXPORT_SYMBOL_GPL" and not gpl:
                status = "gpl_license_required"
            elif target[2] and target[2] not in namespaces:
                status = "namespace_not_imported"
            if status != "match":
                errors.append(f"{name}: {status}")
            rows.append({"symbol": name, "module_crc": f"0x{crc:08x}",
                         "target_crc": f"0x{target[0]:08x}" if target else None,
                         "status": status})
    return {
        "status": "fail" if errors else "pass",
        "scope": "ELF and requested release/CRC checks only; runtime safety and full ABI unverified",
        "module_sha256": hashlib.sha256(elf.data).hexdigest(),
        "vermagic": vermagic[0], "modinfo": metadata,
        "strong_imports": sorted(strong), "weak_imports": sorted(weak),
        "version_count": len(versions), "target_crc_checked": bool(target_symvers),
        "target_symvers_sha256": hashlib.sha256(Path(target_symvers).read_bytes()).hexdigest()
        if target_symvers else None,
        "symbols": rows, "errors": errors,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", type=Path)
    parser.add_argument("--target-symvers", type=Path)
    parser.add_argument("--expected-release")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify(args.module, args.target_symvers, args.expected_release)
    except (ValueError, OSError, UnicodeError, struct.error) as error:
        report = {"status": "fail", "errors": [str(error)]}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Module verification: {report['status']} ({args.report})")
    if report["status"] != "pass":
        for error in report["errors"]:
            print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
