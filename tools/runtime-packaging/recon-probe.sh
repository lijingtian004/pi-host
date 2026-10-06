#!/usr/bin/env bash
# 第 0 步侦察探针：把"哪些文件该进 jniLibs、哪些该进 runtime.zip"的事实一次性打全。
# 在编译机上跑（只读，不改动任何东西）。
set -uo pipefail
ROOT=${ROOT:-/root/runtime-pkg/arm64}
P=data/data/com.termux/files/usr

echo "### 1. git-core 分类"
G=$ROOT/git/$P/libexec/git-core
for f in "$G"/*; do
  t=$(file -b --mime-type "$f")
  case "$t" in
    application/x-sharedlib) echo "  ELF      $(stat -c%s "$f")  $(basename "$f")" ;;
    inode/symlink)           ;;
    *)                       echo "  SCRIPT   $(stat -c%s "$f")  $t  $(basename "$f")" ;;
  esac
done
echo "  symlink 条目数: $(find "$G" -maxdepth 1 -type l | wc -l)"
echo "  ELF 条目数:     $(find "$G" -maxdepth 1 -type f -exec sh -c 'file -b --mime-type "$1" | grep -q sharedlib' _ {} \; -print | wc -l)"

echo
echo "### 2. coreutils 结构（multicall 证据）"
ls -l "$ROOT/coreutils/$P/bin/ls" "$ROOT/coreutils/$P/bin/cat" "$ROOT/coreutils/$P/bin/[" 2>/dev/null
echo "  bin/coreutils 大小: $(stat -c%s "$ROOT/coreutils/$P/bin/coreutils")"
echo "  bin 下真实 ELF: $(find "$ROOT/coreutils/$P/bin" -maxdepth 1 -type f ! -size -100c | wc -l)"
ls "$ROOT/coreutils/$P/libexec/coreutils" 2>/dev/null | head

echo
echo "### 3. ca 证书来源"
find "$ROOT/ca-certificates" -type f \( -name '*.pem' -o -name '*.crt' \) -printf '  %s  %p\n' 2>/dev/null | head
find "$ROOT/openssl" -path "*etc*" -type f -printf '  %s  %p\n' 2>/dev/null | head

echo
echo "### 4. SONAME 复核（AGP 改名必需性）"
for l in libandroid-support/libandroid-support.so libsqlite/libsqlite3.so.3.53.4 zlib/libz.so.1.3.2 openssl/libcrypto.so.3 ncurses/libncursesw.so.6.5; do
  f=$ROOT/$l
  [ -f "$f" ] || { echo "  缺 $f"; continue; }
  printf '  %-34s SONAME=%s\n' "$(basename "$f")" "$(readelf -d "$f" | awk -F'[][]' '/SONAME/{print $2}')"
done

echo
echo "### 5. 所有闭包 ELFs 的 RUNPATH 实测"
for f in "$ROOT"/*/$P/bin/* "$ROOT"/*/$P/lib/*.so*; do
  [ -f "$f" ] || continue
  file -b --mime-type "$f" | grep -q sharedlib || continue
  rp=$(readelf -d "$f" 2>/dev/null | awk -F'[][]' '/RUNPATH|RPATH/{print $2; exit}')
  [ -n "$rp" ] && echo "  ${rp}"
done | sort | uniq -c | sort -rn | head -12

echo
echo "### 6. ELF 架构/类型抽查"
readelf -h "$ROOT/nodejs-lts/$P/bin/node" | grep -E 'Machine|Type|Byte'

echo
echo "### 7. 磁盘"
df -h / | tail -1
du -sh "$ROOT" 2>/dev/null
