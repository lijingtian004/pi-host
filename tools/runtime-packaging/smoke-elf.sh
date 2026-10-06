#!/usr/bin/env bash
#
# smoke-elf.sh — jniLibs 冒烟体检（可在编译机对 out/jniLibs 或从 APK 解出来的 lib/ 目录跑）
#
# 判据（任一不过即 exit 1）：
#   1) `file` 认定是 ELF 64-bit ARM aarch64；
#   2) e_machine = AArch64、e_type = DYN（PIE，bionic 才能加载/exec）；
#   3) DT_RUNPATH 必须是 $ORIGIN（Termux 原包是绝对前缀路径，没改干净就是漏 patch）；
#   4) 每条 DT_NEEDED 要么在系统库白名单里，要么同目录有同名实体文件；
#   5) 库文件的 DT_SONAME == 文件名（改名没同步 patchelf 会在这里暴露）；
#   6) 单个文件不超过 100MB（Play/APK 尺寸红线，也是 patchelf 膨胀的哨兵）。
#
# 用法：bash tools/runtime-packaging/smoke-elf.sh [目录]
set -uo pipefail

DIR=${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/out/jniLibs/arm64-v8a}
SYSTEM_LIBS="libc.so libm.so libdl.so liblog.so libandroid.so libmediandk.so libstdc++.so libGLESv2.so libEGL.so libz.so"
# 注意：libz.so 在白名单里只是"允许出现"，本流水线不会引到它——所有 zlib 引用都被改写成了 libpi_z.so。

[ -d "$DIR" ] || { echo "!! 目录不存在：$DIR" >&2; exit 2; }
shopt -s nullglob
files=("$DIR"/*.so)
[ ${#files[@]} -gt 0 ] || { echo "!! 目录里没有 .so：$DIR" >&2; exit 2; }

fail=0
printf '%-32s %10s %-6s %-24s %s\n' NAME BYTES TYPE SONAME NEEDED_CHECK
for f in "${files[@]}"; do
    name=$(basename "$f")
    bytes=$(stat -c%s "$f")
    problems=()

    fdesc=$(file -b "$f")
    case "$fdesc" in
        *"ELF 64-bit"*ARM* | *"ELF 64-bit, architecture: ARM aarch64"*) ;;
        *) problems+=("file 不是 arm64 ELF: $fdesc") ;;
    esac

    hdr=$(readelf -h "$f")
    echo "$hdr" | grep -q 'Machine:.*AArch64' || problems+=("e_machine 非 AArch64")
    etype=$(echo "$hdr" | awk -F: '/Type:/{gsub(/^ +/,"",$2);print $2}')
    echo "$etype" | grep -q 'DYN' || problems+=("e_type=$etype 非 DYN/PIE")

    dyn=$(readelf -d "$f")
    rp=$(echo "$dyn" | awk -F'[][]' '/\(RUNPATH\)/{print $2}')
    [ "$rp" = '$ORIGIN' ] || problems+=("RUNPATH='$rp'")
    [ -z "$rp" ] && problems+=("无 RUNPATH")

    needed=$(echo "$dyn" | awk -F'[][]' '/\(NEEDED\)/{print $2}')
    for n in $needed; do
        case " $SYSTEM_LIBS " in *" $n "*) continue ;; esac
        [ -f "$DIR/$n" ] || problems+=("NEEDED $n 无实体")
    done

    if [ "$etype" != "EXEC (Executable file)" ]; then
        :
    fi
    son=$(echo "$dyn" | awk -F'[][]' '/\(SONAME\)/{print $2}')
    if [ -n "$son" ] && [ "$son" != "$name" ]; then
        problems+=("SONAME=$son != 文件名")
    fi

    if [ "$bytes" -gt $((100 * 1024 * 1024)) ]; then
        problems+=("单文件 >100MB")
    fi

    if [ ${#problems[@]} -eq 0 ]; then
        printf '%-32s %10s %-6s %-24s %s\n' "$name" "$bytes" "$(echo "$etype" | cut -c1-4)" "${son:--}" "OK ($(echo "$needed" | wc -w) NEEDED)"
    else
        fail=1
        printf '%-32s %10s %-6s %-24s %s\n' "$name" "$bytes" "$(echo "$etype" | cut -c1-4)" "${son:--}" "FAIL"
        for p in "${problems[@]}"; do echo "    !! $p" >&2; done
    fi
done

total=$(du -sh "$DIR" | cut -f1)
echo "-- $DIR：${#files[@]} 个 .so，合计 $total"
if [ "$fail" = 0 ]; then
    echo "smoke-elf: PASS"
else
    echo "smoke-elf: FAIL" >&2
fi
exit "$fail"
