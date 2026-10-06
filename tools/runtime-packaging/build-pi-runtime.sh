#!/usr/bin/env bash
#
# job B — pi bundle → assets/runtime/{manifest.json, pi-runtime.zip}
#
# 产出结构（方案 §4.4 的 runtime 根 = filesDir/runtime）：
#   pi/**                 # @earendil-works/pi-coding-agent 整包（dist/bundle 全量保留）
#   libexec/<git 脚本>     # Termux git libexec/git-core 里的**非 ELF、非软链**数据文件，
#                          # 扁平化到 libexec/（GIT_EXEC_PATH=runtime/libexec）
#   ca-bundle.crt         # Termux ca-certificates 的 etc/tls/cert.pem
#   （bin/*、libexec/git-* 可执行项全部走 manifest.symlinks，指向 nativeLibraryDir 的 libpi_*.so；
#     zip 内不放任何 symlink 条目——Java ZipInputStream 不还原软链，见 REPORT.md 第 6 节）
#
# runtimeVersion = pi-<PI_VERSION>-<job A 版本戳>，可追溯到具体 Termux 包集合。
# 确定性：条目按路径排序、mtime 固定 1980-01-01、deflate；重复跑 zip 字节一致。
#
# 用法（编译机）：bash tools/runtime-packaging/build-pi-runtime.sh
# 可调：WORK OUT PI_SRC PI_VERSION
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
WORK=${WORK:-/root/runtime-pkg}
OUT=${OUT:-$HERE/out}
REPO_BASE=${REPO_BASE:-https://packages.termux.dev/apt/termux-main}
PINS=$HERE/termux-pins.tsv
PREFIX=data/data/com.termux/files/usr
PI_VERSION=${PI_VERSION:-1.0.4}
PI_SRC=${PI_SRC:-$WORK/pi-src}
GIT_PKG_DIR=$WORK/arm64/git
STAGE=$OUT/stage
ASSETS=$OUT/assets/runtime

[ -f "$OUT/jniLibs-version.txt" ] || { echo "!! 先跑 job A（需要 out/jniLibs-version.txt 与 runtime-symlinks.tsv）" >&2; exit 2; }
STAMP=$(head -1 "$OUT/jniLibs-version.txt")
RUNTIME_VERSION="pi-${PI_VERSION}-${STAMP}"
echo "== runtimeVersion = $RUNTIME_VERSION =="

# ---------------------------------------------------------------- 1. pi bundle 源
[ -d "$PI_SRC" ] || { echo "!! pi 源不存在：$PI_SRC（从本机 rsync：
   rsync -az -e ssh /root/pi/.capture/node_modules/@earendil-works/pi-coding-agent/ root@build:/root/runtime-pkg/pi-src/）" >&2; exit 2; }
got=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['version'])" "$PI_SRC/package.json")
[ "$got" = "$PI_VERSION" ] || { echo "!! pi 版本不符：期望 $PI_VERSION 实际 $got" >&2; exit 2; }
echo "pi 源 ok：$PI_SRC（$PI_VERSION, $(du -sh "$PI_SRC" | cut -f1)）"

# ---------------------------------------------------------------- 2. ca-certificates
row=$(awk -F'\t' -v p=ca-certificates '$1==p{print $2"\t"$3"\t"$4; exit}' "$PINS")
IFS=$'\t' read -r ca_ver ca_file ca_sha <<<"$row"
ca_deb=$WORK/dl/$(basename "$ca_file")
if [ ! -f "$ca_deb" ]; then
    curl -fsSL --connect-timeout 20 --retry 3 -o "$ca_deb.part" "$REPO_BASE/$ca_file"
    mv "$ca_deb.part" "$ca_deb"
fi
[ "$(sha256sum "$ca_deb" | cut -d' ' -f1)" = "$ca_sha" ] || { echo "!! ca-certificates sha256 不符" >&2; rm -f "$ca_deb"; exit 3; }
[ -d "$WORK/arm64/ca-certificates" ] || { mkdir -p "$WORK/arm64/ca-certificates"; dpkg-deb -x "$ca_deb" "$WORK/arm64/ca-certificates"; }
CA_PEM=$WORK/arm64/ca-certificates/$PREFIX/etc/tls/cert.pem
[ -s "$CA_PEM" ] || { echo "!! 找不到 $CA_PEM" >&2; exit 4; }
echo "ca ok：ca-certificates $ca_ver → $(grep -c 'BEGIN CERTIFICATE' "$CA_PEM") 张证书"

# ---------------------------------------------------------------- 3. 组 staging 树
rm -rf "$STAGE" "$ASSETS"
mkdir -p "$STAGE" "$ASSETS"
cp -a "$PI_SRC" "$STAGE/pi"
find "$STAGE/pi" -name '.git*' -prune -exec rm -rf {} + 2>/dev/null || true
mkdir -p "$STAGE/libexec"
# git-core 里的普通文件（shell/perl/ruby 脚本与 mergetools），ELF 与软链不入 zip
GCore=$GIT_PKG_DIR/$PREFIX/libexec/git-core
[ -d "$GCore" ] || { echo "!! git-core 目录缺失，先跑 job A 解包 git" >&2; exit 2; }
python3 - "$GCore" "$STAGE/libexec" <<'PY'
import os, shutil, sys
src, dst = sys.argv[1], sys.argv[2]


def is_elf(p):
    try:
        with open(p, "rb") as fh:
            return fh.read(4) == b"\x7fELF"
    except OSError:
        return False


n = 0
for dirpath, dirnames, filenames in os.walk(src):
    rel = os.path.relpath(dirpath, src)
    for f in filenames:
        p = os.path.join(dirpath, f)
        if os.path.islink(p) or is_elf(p):
            continue          # 软链→manifest.symlinks；ELF→jniLibs
        outp = os.path.join(dst, rel if rel != "." else "", f)
        os.makedirs(os.path.dirname(outp), exist_ok=True)
        shutil.copyfile(p, outp)
        n += 1
print("git-core 数据文件入 zip: %d" % n)
PY
cp "$CA_PEM" "$STAGE/ca-bundle.crt"

# ---------------------------------------------------------------- 4. zip + manifest
python3 - "$STAGE" "$ASSETS" "$RUNTIME_VERSION" "$OUT/runtime-symlinks.tsv" "$PI_VERSION" "$ca_ver" <<'PY'
import hashlib, json, os, sys, zipfile

stage, assets, version, links_tsv, pi_ver, ca_ver = sys.argv[1:7]

files = []
for dirpath, dirnames, filenames in os.walk(stage):
    dirnames.sort()
    for f in sorted(filenames):
        p = os.path.join(dirpath, f)
        if not os.path.isfile(p) or os.path.islink(p):
            continue
        files.append((os.path.relpath(p, stage).replace(os.sep, "/"), p))
files.sort()

zip_path = os.path.join(assets, "pi-runtime.zip")
entries = []
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
    for rel, p in files:
        data = open(p, "rb").read()
        sha = hashlib.sha256(data).hexdigest()
        info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0
        zf.writestr(info, data)
        entries.append({"path": rel, "sha256": sha})

links = []
for line in open(links_tsv, encoding="utf-8"):
    line = line.rstrip("\n")
    if not line or line.startswith("#"):
        continue
    path, target = line.split("\t")
    links.append({"path": path, "target": target})
links.sort(key=lambda e: e["path"])

manifest = {
    "runtimeVersion": version,
    "files": entries,
    "symlinks": links,
    "generator": "job B build-pi-runtime.sh (pi %s + %s)" % (pi_ver, ca_ver),
}
with open(os.path.join(assets, "manifest.json"), "w", encoding="utf-8") as fh:
    json.dump(manifest, fh, separators=(",", ":"), sort_keys=False)
    fh.write("\n")

print("zip 条目 %d，manifest.files %d，symlinks %d" % (len(entries), len(entries), len(links)))
print("zip 大小 %.1f MB，未压缩 %.1f MB" % (
    os.path.getsize(zip_path) / 1048576,
    sum(os.path.getsize(p) for _r, p in files) / 1048576))

# 预飞行复核：按 manifest 再验一遍 zip 里的字节（AssetInstaller 的 gate 必然通过）
with zipfile.ZipFile(zip_path) as zf:
    bad = 0
    for e in entries:
        if hashlib.sha256(zf.read(e["path"])).hexdigest() != e["sha256"]:
            bad += 1
    print("复核不符条目: %d" % bad)
    sys.exit(1 if bad else 0)
PY

echo "== job B 完成 =="
ls -l "$ASSETS"
