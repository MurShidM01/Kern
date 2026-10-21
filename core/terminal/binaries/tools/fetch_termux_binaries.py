#!/usr/bin/env python3
"""Fetch, verify and vendor the Termux binaries that Kern ships as `lib*.so`.

Android only keeps files under `lib/<abi>/lib*.so` executable (nativeLibraryDir), so proot and its
dependencies are renamed to `lib*.so` and dropped into `src/main/jniLibs/<abi>/`. Their DT_NEEDED
entries are rewritten in place to the new names (the replacements are shorter, so the string table
layout is preserved).

This is the auditable generator for the vendored binaries. It is pure standard library so it runs on
any Python 3.8+ with no extra packages. Run it from anywhere:

    python core/terminal/binaries/tools/fetch_termux_binaries.py

Network is only used to populate the local cache (`~/.cache/kern-termux` by default); pass
`--cache <dir>` to point elsewhere. Every artifact is checked against the pinned SHA-256 below before
it is used, so a tampered mirror fails the run.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import struct
import sys
import tarfile
import urllib.request

BASE_URL = "https://packages.termux.dev/apt/termux-main"
TERMUX_PREFIX = "./data/data/com.termux/files/usr"

# Pinned packages (name -> version, url path, sha256 per ABI). SHA-256 values come from the Termux
# `Packages` index for `dists/stable/main/binary-<arch>`.
PACKAGES = {
    "proot": {
        "version": "5.1.107.96",
        "path": "pool/main/p/proot/proot_5.1.107.96_{arch}.deb",
        "sha256": {
            "aarch64": "8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0",
            "x86_64": "77ea45540071ca543adda2b51aca2bc3761c52904d013fd0890682288eff9455",
        },
    },
    "libtalloc": {
        "version": "2.5.0",
        "path": "pool/main/libt/libtalloc/libtalloc_2.5.0_{arch}.deb",
        "sha256": {
            "aarch64": "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7",
            "x86_64": "b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530",
        },
    },
    "libandroid-shmem": {
        "version": "0.7",
        "path": "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_{arch}.deb",
        "sha256": {
            "aarch64": "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6",
            "x86_64": "ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797",
        },
    },
}

# ABI mapping: Android ABI name -> Termux architecture.
ABIS = {"arm64-v8a": "aarch64", "x86_64": "x86_64"}

# (package, member path inside the .deb, output name, DT_NEEDED rewrites).
PAYLOADS = [
    ("proot", f"{TERMUX_PREFIX}/bin/proot", "libproot.so", {"libtalloc.so.2": "libtalloc.so"}),
    ("proot", f"{TERMUX_PREFIX}/libexec/proot/loader", "libproot-loader.so", {}),
    ("libtalloc", f"{TERMUX_PREFIX}/lib/libtalloc.so.2.5.0", "libtalloc.so", {}),
    ("libandroid-shmem", f"{TERMUX_PREFIX}/lib/libandroid-shmem.so", "libandroid-shmem.so", {}),
]

# License texts vendored under assets/terminal/licenses (source -> destination file).
LICENSES = {
    "proot-GPL-2.0.txt": "https://raw.githubusercontent.com/termux/termux-packages/master/LICENSE",
    "libtalloc-LGPL-3.0.txt": "https://raw.githubusercontent.com/talloc/talloc/master/COPYING",
    "libandroid-shmem-MIT.txt": "https://raw.githubusercontent.com/termux/libandroid-shmem/master/LICENSE",
}


def read_ar(blob: bytes):
    """Yield (name, body) for each member of a Unix `ar` archive (the `.deb` container)."""
    if blob[:8] != b"!<arch>\n":
        raise ValueError("not an ar archive")
    off = 8
    while off + 60 <= len(blob):
        header = blob[off : off + 60]
        name = header[0:16].decode("ascii", "replace").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        off += 60
        yield name, blob[off : off + size]
        off += size
        if off % 2 == 1:  # members are 2-byte aligned
            off += 1


def deb_payload(deb: bytes, member: str) -> bytes:
    """Extract `member` from the `data.tar.xz` inside a `.deb`."""
    for name, body in read_ar(deb):
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(body), mode="r:xz") as tar:
                extracted = tar.extractfile(member)
                if extracted is None:
                    raise KeyError(f"{member} not found in data.tar")
                return extracted.read()
    raise KeyError("data.tar not found in .deb")


def patch_dt_needed(blob: bytes, mapping: dict) -> bytes:
    """Rewrite DT_NEEDED strings in place. Replacements must be <= the original length."""
    if blob[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = blob[4] == 2
    if blob[5] != 1:
        raise ValueError("only little-endian ELF is supported")

    if is64:
        e_phoff = struct.unpack_from("<Q", blob, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", blob, 0x36)[0]
        e_phnum = struct.unpack_from("<H", blob, 0x38)[0]
    else:
        e_phoff = struct.unpack_from("<I", blob, 0x1C)[0]
        e_phentsize = struct.unpack_from("<H", blob, 0x2A)[0]
        e_phnum = struct.unpack_from("<H", blob, 0x2C)[0]

    loads = []
    dynamic = None
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        if is64:
            p_type, _flags, p_offset, p_vaddr, _pa, p_filesz, _ms, _al = struct.unpack_from(
                "<IIQQQQQQ", blob, base
            )
        else:
            p_type, p_offset, p_vaddr, _pa, p_filesz, _ms, _flags, _al = struct.unpack_from(
                "<IIIIIIII", blob, base
            )
        if p_type == 1:  # PT_LOAD
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:  # PT_DYNAMIC
            dynamic = (p_offset, p_filesz)

    if dynamic is None:
        return blob  # statically linked; nothing to rewrite

    def vaddr_to_off(vaddr: int):
        for start, offset, size in loads:
            if start <= vaddr < start + size:
                return offset + (vaddr - start)
        return None

    dyn_off, dyn_size = dynamic
    entry_size = 16 if is64 else 8
    needed = []
    strtab_vaddr = None
    for i in range(dyn_size // entry_size):
        off = dyn_off + i * entry_size
        if is64:
            d_tag, d_val = struct.unpack_from("<QQ", blob, off)
        else:
            d_tag, d_val = struct.unpack_from("<II", blob, off)
        if d_tag == 0:
            break
        if d_tag == 1:  # DT_NEEDED
            needed.append(d_val)
        elif d_tag == 5:  # DT_STRTAB
            strtab_vaddr = d_val

    strtab_off = vaddr_to_off(strtab_vaddr) if strtab_vaddr is not None else None
    if strtab_off is None:
        return blob

    out = bytearray(blob)
    for value in needed:
        start = strtab_off + value
        end = blob.index(b"\x00", start)
        original = blob[start:end].decode("ascii", "replace")
        replacement = mapping.get(original)
        if replacement is None:
            continue
        encoded = replacement.encode("ascii")
        if len(encoded) > len(original):
            raise ValueError(f"{replacement} does not fit in the space of {original}")
        span = end - start  # bytes available including the terminating NUL
        out[start : start + span] = encoded + b"\x00" * (span - len(encoded))
    return bytes(out)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def download(url: str, cache: str) -> bytes:
    os.makedirs(cache, exist_ok=True)
    cached = os.path.join(cache, os.path.basename(url))
    if os.path.exists(cached):
        with open(cached, "rb") as handle:
            return handle.read()
    sys.stderr.write(f"  downloading {url}\n")
    with urllib.request.urlopen(url) as response:  # noqa: S310 (pinned https URL)
        data = response.read()
    with open(cached, "wb") as handle:
        handle.write(data)
    return data


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    module = os.path.dirname(here)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", default=os.path.join(os.path.expanduser("~"), ".cache", "kern-termux"))
    parser.add_argument("--licenses", action="store_true", help="also refresh the license assets")
    args = parser.parse_args()

    manifest = {"note": "Generated by tools/fetch_termux_binaries.py", "abis": {}}

    for abi, arch in ABIS.items():
        out_dir = os.path.join(module, "src", "main", "jniLibs", abi)
        os.makedirs(out_dir, exist_ok=True)
        manifest["abis"][abi] = {}

        debs = {}
        for name, spec in PACKAGES.items():
            url = f"{BASE_URL}/{spec['path'].format(arch=arch)}"
            data = download(url, args.cache)
            actual = sha256(data)
            expected = spec["sha256"][arch]
            if actual != expected:
                raise SystemExit(f"sha256 mismatch for {name} ({arch}): {actual} != {expected}")
            debs[name] = data
            sys.stderr.write(f"  verified {name} {spec['version']} ({arch})\n")

        for package, member, out_name, rewrites in PAYLOADS:
            payload = deb_payload(debs[package], member)
            if rewrites:
                payload = patch_dt_needed(payload, rewrites)
            with open(os.path.join(out_dir, out_name), "wb") as handle:
                handle.write(payload)
            manifest["abis"][abi][out_name] = {
                "sha256": sha256(payload),
                "size": len(payload),
                "source": f"{package} {PACKAGES[package]['version']} ({arch})",
                "member": member,
                "dt_needed_rewrites": rewrites,
            }
            sys.stderr.write(f"  wrote {abi}/{out_name} ({len(payload)} bytes)\n")

    manifest_path = os.path.join(module, "binaries.lock.json")
    with open(manifest_path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")
    sys.stderr.write(f"  wrote {manifest_path}\n")

    if args.licenses:
        lic_dir = os.path.join(module, "src", "main", "assets", "terminal", "licenses")
        os.makedirs(lic_dir, exist_ok=True)
        for filename, url in LICENSES.items():
            data = download(url, args.cache)
            with open(os.path.join(lic_dir, filename), "wb") as handle:
                handle.write(data)
            sys.stderr.write(f"  wrote licenses/{filename}\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
