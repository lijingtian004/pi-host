#!/usr/bin/env python3
"""ELF 依赖闭包实测器（第 0 步侦察的核心证据工具）。

只信 readelf：从种子可执行体出发，沿 DT_NEEDED 传递闭包，在已解包的 Termux
包目录里找每个 NEEDED 的实体文件，并记录

  * 该文件来自哪个包
  * 它的 DT_NEEDED / DT_RUNPATH / DT_SONAME
  * 需要改名成什么才能进 APK jniLibs（AGP 只认 `lib*.so` 形态的文件名，
    `libz.so.1.3.2` 这种带版本号尾巴的不会作为 native lib 打进包里）

用法：
    elf-closure.py --root /root/runtime-pkg/arm64 \
        --prefix data/data/com.termux/files/usr \
        --seed nodejs-lts:bin/node --seed bash:bin/bash --seed git:bin/git \
        --system libc.so --system libm.so --system libdl.so \
        [--json out] [--verbose]

退出码 0 = 闭包完整（所有非系统 NEEDED 都能落到实体文件）。
"""
import argparse
import json
import os
import subprocess
import sys

READELF = "readelf"


def dynamic(elf_path):
    """readelf -d → dict(needed, runpath, rpath, soname)。

    行形如：` 0x0000000000000001 (NEEDED)   Shared library: [libc.so]`。
    """
    proc = subprocess.run([READELF, "-d", elf_path], capture_output=True, text=True)
    needed, runpath, rpath, soname = [], None, None, None
    for line in proc.stdout.splitlines():
        if "(NEEDED)" in line:
            needed.append(_bracket(line.split("(NEEDED)", 1)[1]))
        elif "(RUNPATH)" in line and runpath is None:
            runpath = _bracket(line.split("(RUNPATH)", 1)[1])
        elif "(RPATH)" in line and rpath is None:
            rpath = _bracket(line.split("(RPATH)", 1)[1])
        elif "(SONAME)" in line and soname is None:
            soname = _bracket(line.split("(SONAME)", 1)[1])
    return {"needed": needed, "runpath": runpath, "rpath": rpath, "soname": soname}


def _bracket(text):
    text = text.strip()
    if "[" in text and "]" in text:
        return text[text.index("[") + 1:text.rindex("]")]
    return text.split(":")[-1].strip()


def is_elf(path):
    try:
        with open(path, "rb") as fh:
            return fh.read(4) == b"\x7fELF"
    except OSError:
        return False


def build_pool(root, prefix, packages):
    """{文件名: 实体路径}，只收 ELF；同名冲突时优先真实文件而不是软链。"""
    pool = {}
    for pkg in packages:
        base = os.path.join(root, pkg, prefix, "lib")
        if not os.path.isdir(base):
            continue
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in filenames:
                path = os.path.join(dirpath, name)
                real = os.path.realpath(path)
                if not os.path.isfile(real) or not is_elf(real):
                    continue
                cur = pool.get(name)
                if cur is None or (os.path.islink(cur) and not os.path.islink(path)):
                    pool[name] = path
    return pool


def strip_version(name):
    """libz.so.1.3.2 → libz.so；libc++_shared.so → libc++_shared.so。"""
    head, sep, _tail = name.partition(".so")
    return head + ".so" if sep else name


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="解包根，如 /root/runtime-pkg/arm64")
    ap.add_argument("--prefix", default="data/data/com.termux/files/usr")
    ap.add_argument("--seed", action="append", required=True, help="格式 <pkg>:<相对 pkg/prefix 的路径>")
    ap.add_argument("--system", action="append", default=["libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"])
    ap.add_argument("--json", help="把结果写成 JSON")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    packages = sorted(d for d in os.listdir(args.root) if os.path.isdir(os.path.join(args.root, d)))
    pool = build_pool(args.root, args.prefix, packages)
    system = set(args.system)

    resolved = {}   # 请求名 → record
    unresolved = []
    queue = []

    for seed in args.seed:
        pkg, rel = seed.split(":", 1)
        path = os.path.realpath(os.path.join(args.root, pkg, args.prefix, rel))
        if not os.path.isfile(path):
            sys.stderr.write("种子缺失: %s\n" % path)
            return 2
        dyn = dynamic(path)
        queue.extend(dyn["needed"])
        resolved["__seed__:" + seed] = {
            "package": pkg, "requested": rel, "file": path,
            "soname": dyn["soname"], "needed": dyn["needed"],
            "runpath": dyn["runpath"], "rpath": dyn["rpath"], "is_seed": True,
        }

    while queue:
        req = queue.pop(0)
        if req in resolved or req in system:
            continue
        path = pool.get(req)
        if path is None:
            # 带版本尾巴的名字常是软链名；试过去版本号后的实体名
            alt = strip_version(req)
            path = pool.get(alt)
        if path is None:
            unresolved.append(req)
            continue
        real = os.path.realpath(path)
        dyn = dynamic(real)
        resolved[req] = {
            "requested": req,
            "file": real,
            "basename": os.path.basename(real),
            "package": _owner(args.root, args.prefix, real),
            "soname": dyn["soname"],
            "needed": dyn["needed"],
            "runpath": dyn["runpath"],
            "rpath": dyn["rpath"],
        }
        queue.extend(dyn["needed"])

    libs = {k: v for k, v in resolved.items() if not k.startswith("__seed__:")}
    seeds = [v for k, v in resolved.items() if k.startswith("__seed__:")]

    print("== 种子可执行体 ==")
    for s in seeds:
        print("  %-10s %s" % (s["package"], s["requested"]))
        print("             RUNPATH=%s" % s["runpath"])
        print("             NEEDED=%s" % " ".join(s["needed"]))
    print("\n== 传递闭包内的共享库 (%d) ==" % len(libs))
    for name in sorted(libs):
        r = libs[name]
        print("  %-24s pkg=%-20s soname=%-22s file=%s" % (name, r["package"], r["soname"], r["basename"]))
        if args.verbose:
            print("      NEEDED=%s RUNPATH=%s" % (r["needed"], r["runpath"]))
    if unresolved:
        print("\n!! 未解析 NEEDED（既不是系统库也不在闭包内）: %s" % " ".join(sorted(set(unresolved))))
    if args.json:
        with open(args.json, "w") as fh:
            json.dump({"seeds": seeds, "libs": libs, "unresolved": sorted(set(unresolved))}, fh, indent=1)
    return 0 if not unresolved else 1


def _owner(root, prefix, real_path):
    parts = real_path.split(os.sep)
    if root in parts:
        i = parts.index(root)
        return parts[i + 1]
    rel = os.path.relpath(real_path, root)
    return rel.split(os.sep)[0]


if __name__ == "__main__":
    sys.exit(main())
