#!/usr/bin/env bash
#
# job A — Termux arm64 → APK jniLibs（libpi_*.so）
#
# 做什么：
#   1. 按 termux-pins.tsv 钉死的版本下载 .deb（缓存复用），强制 sha256 校验；
#   2. dpkg-deb -x 解包；
#   3. 按 exec-map / elf-name-map 把可执行体与依赖库改名拷进 out/jniLibs/arm64-v8a/；
#   4. patchelf 分步改写（本机 patchelf 0.14.3 在单条命令里同时 --set-soname +
#      --replace-needed 会串键，见 REPORT.md 第 5 节，故必须一步一次调用）：
#        NEEDED 旧名 → libpi_ 新名 → SONAME → RUNPATH=$ORIGIN；
#   5. llvm-strip（仅对 AArch64 ELF；Termux 发布包其实已 strip，见报告）；
#   6. 自检：每个 ELF 的 NEEDED 必须能在同目录落地，否则 exit 1；
#   7. 产出清单：jniLibs-inventory.tsv / runtime-symlinks.tsv / jniLibs-version.txt，
#      供 job B 与 APK 体检使用。
#
# 幂等：输入是钉死的 sha256，中间产物每次从 deb 重新拷贝再 patch，重复跑结果一致。
#
# 用法（编译机）：bash tools/runtime-packaging/build-jniLibs.sh
# 可调环境变量：WORK(=/root/runtime-pkg) OUT(=tools/runtime-packaging/out)
#              REPO_BASE NDK_STRIP REDO_EXTRACT(=1 强制重解包)
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
WORK=${WORK:-/root/runtime-pkg}
OUT=${OUT:-$HERE/out}
REPO_BASE=${REPO_BASE:-https://packages.termux.dev/apt/termux-main}
NDK_STRIP=${NDK_STRIP:-/root/android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip}
PREFIX=data/data/com.termux/files/usr
DL=$WORK/dl
EX=$WORK/arm64
JNI=$OUT/jniLibs/arm64-v8a
SYSTEM_LIBS="libc.so libm.so libdl.so liblog.so libandroid.so libmediandk.so"

PINS=$HERE/termux-pins.tsv
LIBMAP=$HERE/elf-name-map.tsv
EXECMAP=$HERE/exec-map.tsv

for f in "$PINS" "$LIBMAP" "$EXECMAP"; do
    [ -f "$f" ] || { echo "!! 缺少映射表 $f" >&2; exit 2; }
done

pin_of() { awk -F'\t' -v p="$1" '$1==p{print $2"\t"$3"\t"$4; exit}' "$PINS"; }   # version \t file \t sha256
rows()   { grep -v '^[[:space:]]*#' "$1" | grep -v '^[[:space:]]*$'; }

# ---------------------------------------------------------------- 1. 需要哪些包
NEEDED_PKGS=$( { rows "$EXECMAP" | cut -f1; rows "$LIBMAP" | cut -f2; } | tr -d ' ' | sort -u )

mkdir -p "$DL" "$EX"
echo "== job A：包集合（$(echo "$NEEDED_PKGS" | wc -l) 个，版本钉死）=="
echo "$NEEDED_PKGS" | tr '\n' ' '; echo

# ---------------------------------------------------------------- 2. 下载 + 校验 + 解包
for pkg in $NEEDED_PKGS; do
    row=$(pin_of "$pkg")
    [ -n "$row" ] || { echo "!! termux-pins.tsv 里没有 $pkg" >&2; exit 2; }
    IFS=$'\t' read -r ver debfile sha <<<"$row"
    deb="$DL/$(basename "$debfile")"
    if [ ! -f "$deb" ]; then
        echo "-- 下载 $debfile"
        curl -fsSL --connect-timeout 20 --retry 3 -o "$deb.part" "$REPO_BASE/$debfile"
        mv "$deb.part" "$deb"
    fi
    got=$(sha256sum "$deb" | cut -d' ' -f1)
    if [ "$got" != "$sha" ]; then
        echo "!! sha256 不符：$pkg 期望 $sha 实际 $got（钉版被污染或版本已滚动）" >&2
        rm -f "$deb"
        exit 3
    fi
    dst="$EX/$pkg"
    if [ "${REDO_EXTRACT:-0}" = 1 ] || [ ! -d "$dst" ] || [ -z "$(ls -A "$dst" 2>/dev/null)" ]; then
        rm -rf "$dst"
        mkdir -p "$dst"
        dpkg-deb -x "$deb" "$dst"
    fi
    echo "   ok $pkg $ver ($(stat -c%s "$deb") bytes deb, sha256 已校验)"
done

# ---------------------------------------------------------------- 3. 改名拷贝
rm -rf "$JNI"
mkdir -p "$JNI"

copy_pair() {  # $1=源绝对路径 $2=目标名
    [ -f "$1" ] || { echo "!! 源文件不存在：$1" >&2; exit 4; }
    cp -f "$1" "$JNI/$2"
    chmod 0644 "$JNI/$2"
}

echo "== 拷贝可执行体 =="
while IFS=$'\t' read -r pkg rel new; do
    [ -n "${pkg:-}" ] || continue
    case "$pkg" in \#*) continue ;; esac
    src=$(readlink -f "$EX/$pkg/$PREFIX/$rel")
    echo "   $pkg/$rel → $new  ($(stat -c%s "$src") bytes)"
    copy_pair "$src" "$new"
done < <(rows "$EXECMAP")

echo "== 拷贝共享库 =="
while IFS=$'\t' read -r req pkg srcfile new; do
    [ -n "${req:-}" ] || continue
    case "$req" in \#*) continue ;; esac
    src=$(readlink -f "$EX/$pkg/$PREFIX/lib/$srcfile")
    copy_pair "$src" "$new"
    echo "   $req ($pkg/$srcfile) → $new  ($(stat -c%s "$src") bytes)"
done < <(rows "$LIBMAP")

# ---------------------------------------------------------------- 4. patchelf
needed_of() { readelf -d "$1" 2>/dev/null | awk -F'[][]' '/\(NEEDED\)/{print $2}'; }
map_new()   { rows "$LIBMAP" | awk -F'\t' -v n="$1" '$1==n{print $4; exit}'; }
is_lib()    { rows "$LIBMAP" | awk -F'\t' -v n="$1" '$4==n{print "y"; exit}' | grep -q y; }

echo "== patchelf（两趟：NEEDED+RUNPATH 合一，SONAME 单独）=="
for f in "$JNI"/*.so; do
    name=$(basename "$f")
    args=()
    for req in $(needed_of "$f"); do
        new=$(map_new "$req" || true)
        [ -n "$new" ] && args+=(--replace-needed "$req" "$new")
    done
    # 实测 patchelf 0.14.3：
    #   * 每条 --replace-needed 单独一次调用会把 .dynstr 反复外扩（node 45.3M→61.5M），
    #     合并成一次调用只涨 1.9M，且结果正确；
    #   * 但 --set-soname 与 --replace-needed 同一条命令里会串键（soname 被写成最后一个
    #     替换目标），所以 SONAME 必须第二趟单独做。
    patchelf --set-rpath '$ORIGIN' "${args[@]}" "$f"
    if is_lib "$name"; then
        patchelf --set-soname "$name" "$f"
    fi
done

# ---------------------------------------------------------------- 5. strip（仅 AArch64）
echo "== llvm-strip =="
if [ -x "$NDK_STRIP" ]; then
    for f in "$JNI"/*.so; do
        if readelf -h "$f" | grep -q 'Machine:.*AArch64'; then
            "$NDK_STRIP" --strip-unneeded "$f"
        else
            echo "   跳过非 arm64：$f" >&2
        fi
    done
else
    echo "!! 找不到 llvm-strip（$NDK_STRIP），跳过 strip" >&2
fi

# ---------------------------------------------------------------- 6. 自检
echo "== 自检：RUNPATH + SONAME + NEEDED 落地 =="
fail=0
for f in "$JNI"/*.so; do
    name=$(basename "$f")
    rp=$(readelf -d "$f" | awk -F'[][]' '/\(RUNPATH\)/{print $2}')
    [ "$rp" = '$ORIGIN' ] || { echo "!! $name RUNPATH=$rp（应为 \$ORIGIN）" >&2; fail=1; }
    if is_lib "$name"; then
        son=$(readelf -d "$f" | awk -F'[][]' '/\(SONAME\)/{print $2}')
        [ "$son" = "$name" ] || { echo "!! $name SONAME=$son（patchelf 串键，应等于文件名）" >&2; fail=1; }
    fi
    for n in $(needed_of "$f"); do
        case " $SYSTEM_LIBS " in *" $n "*) continue ;; esac
        [ -f "$JNI/$n" ] || { echo "!! $name 的 NEEDED $n 在 jniLibs 内无实体" >&2; fail=1; }
    done
done
[ "$fail" = 0 ] || exit 5

# ---------------------------------------------------------------- 7. 清单产物
python3 - "$EX" "$PREFIX" "$HERE" "$OUT" "$JNI" <<'PY'
import hashlib, os, sys

ex_root, prefix, here, out, jni = sys.argv[1:6]


def read(path):
    return [l.rstrip("\n").split("\t") for l in open(path, encoding="utf-8")
            if l.strip() and not l.lstrip().startswith("#")]


pins = {r[0]: r for r in read(os.path.join(here, "termux-pins.tsv"))}
execmap = read(os.path.join(here, "exec-map.tsv"))      # pkg, rel, new
libmap = read(os.path.join(here, "elf-name-map.tsv"))   # req, pkg, srcfile, new

# 7a. jniLibs 清单
rows = [(new, "exec", pkg, rel) for pkg, rel, new in execmap]
rows += [(new, "lib", pkg, "lib/" + srcfile) for req, pkg, srcfile, new in libmap]

lines = []
for new, kind, pkg, rel in rows:
    path = os.path.join(jni, new)
    lines.append("\t".join([
        new, kind, pkg, pins[pkg][1], rel,
        str(os.path.getsize(path)),
        hashlib.sha256(open(path, "rb").read()).hexdigest(),
    ]))
with open(os.path.join(out, "jniLibs-inventory.tsv"), "w") as fh:
    fh.write("# name\tkind\tpackage\ttermux_version\tsource_rel\tbytes\tsha256\n")
    fh.write("\n".join(sorted(lines)) + "\n")

# 7b. runtime symlink 农场（bin/ 与 libexec/ 一律由 AssetInstaller 安装期创建）
#     规则：释出的可执行体自身 + 包里所有指向它的软链，路径按
#     bin/* → runtime/bin/*，libexec/git-core/* → runtime/libexec/*（GIT_EXEC_PATH 扁平化）
shipped = {
    os.path.realpath(os.path.join(ex_root, pkg, prefix, rel)): new
    for pkg, rel, new in execmap
}


def own_dir(rel):
    """可执行体自身在 runtime 里的落点：git-core 下的进 libexec/（GIT_EXEC_PATH 扁平化），其余进 bin/。"""
    return "libexec/" if rel.startswith("libexec/") else "bin/"


links = {own_dir(rel) + os.path.basename(rel): new for pkg, rel, new in execmap}
links["bin/sh"] = "libpi_bash.so"        # EnvFactory 的 SHELL；pi 的 sh 也走 bash
links["bin/nodejs"] = "libpi_node.so"    # 有些脚本用 nodejs 这个名字

for pkg in sorted({p for p, _r, _n in execmap}):
    for sub, target_dir in (("bin", "bin"), ("libexec/git-core", "libexec")):
        base = os.path.join(ex_root, pkg, prefix, sub)
        if not os.path.isdir(base):
            continue
        for entry in sorted(os.listdir(base)):
            p = os.path.join(base, entry)
            if not os.path.islink(p):
                continue
            so = shipped.get(os.path.realpath(p))
            if so:
                links[target_dir + "/" + entry] = so

with open(os.path.join(out, "runtime-symlinks.tsv"), "w") as fh:
    fh.write("# runtime 相对路径\tjniLibs 目标文件名（AssetInstaller 解释为 nativeLibraryDir/<target>）\n")
    for k in sorted(links):
        fh.write(k + "\t" + links[k] + "\n")

# 7c. 版本戳：参与构建的 Termux 包集合的稳定哈希，job B 用它拼 runtimeVersion
pkgs = sorted({r[2] for r in rows})
digest_src = "\n".join("%s=%s" % (p, pins[p][1]) for p in pkgs)
stamp = hashlib.sha256(digest_src.encode()).hexdigest()[:12]
total = sum(os.path.getsize(os.path.join(jni, r[0])) for r in rows)
with open(os.path.join(out, "jniLibs-version.txt"), "w") as fh:
    fh.write("termux-" + stamp + "\n")
    fh.write("# 参与包（版本钉死，见 termux-pins.tsv）：\n")
    for p in pkgs:
        fh.write("# %s %s\n" % (p, pins[p][1]))
print("symlink 条目: %d" % len(links))
print("jniLibs 文件: %d  合计 %.1f MB" % (len(rows), total / 1048576))
print("版本戳: termux-" + stamp)
PY

echo "== job A 完成 → $JNI =="
ls -l "$JNI" | awk 'NR>1{printf "  %10d  %s\n",$5,$9}'
