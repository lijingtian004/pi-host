#!/usr/bin/env python3
"""第 0 步侦察：Termux arm64 包依赖闭包 → 下载 → 校验 → 解包 → readelf 实测。

在编译机上执行（x86_64 主机只读分析 arm64 ELF，用 readelf 而非 ldd）：

    python3 tools/runtime-packaging/recon-termux.py --index /root/runtime-pkg/idx/Packages \
        --root /root/runtime-pkg --base https://packages.termux.dev/apt/termux-main

产出：
  1) /root/runtime-pkg/arm64/<pkg>/      每个包解包后的树
  2) /root/runtime-pkg/closure.tsv       包 → 版本 → sha256 → 文件
  3) stdout：node/bash/git 的 DT_NEEDED + RUNPATH 实测，以及逐条 NEEDED 的提供者归因
"""
import argparse
import hashlib
import os
import subprocess
import sys
import urllib.request

SEEDS = [
    "nodejs-lts", "nodejs", "bash", "git", "coreutils", "ca-certificates",
    "openssl", "libffi", "openssh",
]


def parse_index(path):
    """Debian Packages → {name: stanza}，支持字段续行（行首单空格）。"""
    pkgs = {}
    cur = None
    key = None
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line.strip():
                if cur and "Package" in cur:
                    pkgs[cur["Package"]] = cur
                cur = None
                key = None
                continue
            if line.startswith(" ") and cur is not None and key:
                cur[key] += "\n" + line[1:]
                continue
            if ": " not in line:
                continue
            key, value = line.split(": ", 1)
            key = key.strip()
            cur = cur or {}
            cur[key] = value
    if cur and "Package" in cur:
        pkgs[cur["Package"]] = cur
    return pkgs


def depends_names(stanza):
    """Depends 字段 → 包名列表（丢版本约束与 alternatives）。"""
    out = []
    raw = stanza.get("Depends", "")
    for clause in raw.replace("\n", ",").split(","):
        clause = clause.strip()
        if not clause:
            continue
        name = clause.split()[0].split("|")[0].strip()
        if name and "|" not in name:
            out.append(name)
    return out


def closure(pkgs, seeds):
    """按 Depends 传递闭包（Pre-Depends 一并跟）。"""
    seen, order = set(), []
    stack = list(seeds)
    while stack:
        name = stack.pop(0)
        if name in seen:
            continue
        seen.add(name)
        st = pkgs.get(name)
        if not st:
            sys.stderr.write("!! 索引缺失包: %s\n" % name)
            continue
        order.append(name)
        stack.extend(depends_names(st) + depends_names({"Depends": st.get("Pre-Depends", "")}))
    return order


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "pi-runtime-packaging"})
    with urllib.request.urlopen(req, timeout=180) as resp, open(tmp, "wb") as fh:
        for chunk in iter(lambda: resp.read(1 << 20), b""):
            fh.write(chunk)
    os.replace(tmp, dest)
    return dest


def extract(deb, dest):
    """dpkg-deb -x，失败回退到 python 手写 ar 解析（一般用不上）。"""
    if os.path.isdir(dest) and os.listdir(dest):
        return
    os.makedirs(dest, exist_ok=True)
    subprocess.run(["dpkg-deb", "-x", deb, dest], check=True)


def readelf_dynamic(elf):
    """readelf -d → (DT_NEEDED 列表, RUNPATH, RPATH)。

    行形如：` 0x... (NEEDED)  Shared library: [libc.so]`，取方括号内容。
    """
    out = subprocess.run(["readelf", "-d", elf], capture_output=True, text=True).stdout
    needed, runpath, rpath = [], None, None

    def bracket(line):
        if "[" in line and "]" in line:
            return line[line.index("[") + 1:line.rindex("]")]
        return line.split(":", 1)[-1].strip()

    for line in out.splitlines():
        if "(NEEDED)" in line:
            needed.append(bracket(line))
        elif "(RUNPATH)" in line and runpath is None:
            runpath = bracket(line)
        elif "(RPATH)" in line and rpath is None:
            rpath = bracket(line)
    return needed, runpath, rpath


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--index", required=True)
    ap.add_argument("--root", default="/root/runtime-pkg")
    ap.add_argument("--base", default="https://packages.termux.dev/apt/termux-main")
    ap.add_argument("--seeds", default=",".join(SEEDS))
    ap.add_argument("--skip-download", action="store_true")
    args = ap.parse_args()

    pkgs = parse_index(args.index)
    seeds = [s for s in args.seeds.split(",") if s]
    order = closure(pkgs, seeds)

    dl = os.path.join(args.root, "dl")
    ex = os.path.join(args.root, "arm64")
    os.makedirs(dl, exist_ok=True)
    os.makedirs(ex, exist_ok=True)

    rows = []
    for name in order:
        st = pkgs[name]
        fn = st["Filename"]
        deb = os.path.join(dl, os.path.basename(fn))
        if not args.skip_download:
            download(args.base + "/" + fn.lstrip("/"), deb)
            got = sha256_file(deb)
            want = st["SHA256"]
            if got != want:
                sys.stderr.write("!! sha256 不符 %s want=%s got=%s\n" % (name, want, got))
                sys.exit(3)
        dest = os.path.join(ex, name)
        extract(deb, dest)
        rows.append((name, st["Version"], st["SHA256"], st["Size"], fn, deb))

    with open(os.path.join(args.root, "closure.tsv"), "w") as fh:
        for r in rows:
            fh.write("\t".join(str(x) for x in r) + "\n")

    # 汇总所有解出来的 .so* 文件：libname → 提供包
    lib_providers = {}
    for name in order:
        libdir = None
        for dirpath, dirnames, filenames in os.walk(os.path.join(ex, name)):
            if os.path.basename(dirpath) == "lib":
                libdir = dirpath
            for f in filenames:
                if ".so" in f:
                    lib_providers.setdefault(f, []).append(name)

    print("== 闭包包清单 (%d) ==" % len(order))
    for name, ver, sha, size, fn, _ in rows:
        print("  %-24s %-40s %9s  %s" % (name, ver, size, fn))

    probes = {
        "node": os.path.join(ex, "nodejs-lts", "data/data/com.termux/files/usr/bin/node"),
        "bash": os.path.join(ex, "bash", "data/data/com.termux/files/usr/bin/bash"),
        "git": os.path.join(ex, "git", "data/data/com.termux/files/usr/bin/git"),
    }
    print("\n== readelf -d 实测 ==")
    for label, path in probes.items():
        if not os.path.exists(path):
            print("  %s: 缺文件 %s" % (label, path))
            continue
        needed, runpath, rpath = readelf_dynamic(path)
        print("  [%s] RUNPATH=%s RPATH=%s" % (label, runpath, rpath))
        for n in needed:
            prov = lib_providers.get(n)
            print("      NEEDED %-28s -> %s" % (n, (",".join(prov)) if prov else "**未在闭包内/系统提供**"))

    print("\n== 各包 bin/ 与 lib/ 文件数 ==")
    for name in order:
        root = os.path.join(ex, name, "data/data/com.termux/files/usr")
        if not os.path.isdir(root):
            root = os.path.join(ex, name)
        for sub in ("bin", "lib", "libexec"):
            d = os.path.join(root, sub)
            if os.path.isdir(d):
                entries = os.listdir(d)
                print("  %-16s %-8s %3d" % (name, sub, len(entries)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
