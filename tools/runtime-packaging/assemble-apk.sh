#!/usr/bin/env bash
#
# 第 3 步 — 组装带真实资产的 APK：铺产物 → :app:assembleDebug → APK 体检。
#
# 体检判据（任一不过 exit 1）：
#   1) APK 里 lib/arm64-v8a/*.so 条目数 == out/jniLibs/arm64-v8a 文件数；
#   2) assets/runtime/{manifest.json,pi-runtime.zip} 在包内；
#   3) AndroidManifest 的 android:extractNativeLibs="true"（useLegacyPackaging 的落地证据）；
#   4) 从 APK 解出来的 lib/arm64-v8a 目录再过一遍 smoke-elf.sh；
#   5) 记录 APK 体积。
#
# 用法：
#   编译机：GRADLE=/opt/gradle-8.13/bin/gradle bash tools/runtime-packaging/assemble-apk.sh
#   只体检（产物已在）：SKIP_BUILD=1 bash tools/runtime-packaging/assemble-apk.sh
#   本机（无 Android SDK 时只铺产物）：PREPARE_ONLY=1 bash tools/runtime-packaging/assemble-apk.sh
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUT=$HERE/out
JNI_DST=$REPO/app/src/main/jniLibs/arm64-v8a
ASSET_DST=$REPO/app/src/main/assets/runtime
APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
GRADLE=${GRADLE:-}

[ -d "$OUT/jniLibs/arm64-v8a" ] || { echo "!! 缺 $OUT/jniLibs/arm64-v8a，先跑 job A" >&2; exit 2; }
[ -f "$OUT/assets/runtime/manifest.json" ] || { echo "!! 缺 manifest.json，先跑 job B" >&2; exit 2; }

echo "== 铺产物 =="
rm -rf "$JNI_DST" "$ASSET_DST"
mkdir -p "$JNI_DST" "$ASSET_DST"
cp -a "$OUT/jniLibs/arm64-v8a/." "$JNI_DST/"
cp -a "$OUT/assets/runtime/." "$ASSET_DST/"
echo "   jniLibs: $(ls "$JNI_DST" | wc -l) 个 .so / $(du -sh "$JNI_DST" | cut -f1)"
echo "   assets : $(ls -l "$ASSET_DST" | awk 'NR>1{s+=$5} END{printf "%.1f MB", s/1048576}')（manifest.json + pi-runtime.zip）"

if [ "${PREPARE_ONLY:-0}" = 1 ]; then
    echo "== PREPARE_ONLY=1，跳过构建与体检 =="
    exit 0
fi

echo "== 构建 :app:assembleDebug =="
if [ "${SKIP_BUILD:-0}" = 1 ]; then
    echo "   SKIP_BUILD=1，复用已有 APK"
else
    if [ -z "$GRADLE" ]; then
        if command -v gradle >/dev/null 2>&1; then
            GRADLE=$(command -v gradle)
        else
            echo "!! 没有 gradle，设 GRADLE= 或 SKIP_BUILD=1 或 PREPARE_ONLY=1" >&2
            exit 2
        fi
    fi
    # 编译机 JDK 信任库缺陷：任何走 Java HTTPS 的步骤都要显式指向系统 cacerts
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"
    ( cd "$REPO" && "$GRADLE" --no-daemon :app:assembleDebug )
fi

[ -f "$APK" ] || { echo "!! 构建产物缺失：$APK" >&2; exit 3; }

echo "== APK 体检 =="
fail=0
# 列表只取一次：避免 `unzip | grep -q` 触发 SIGPIPE（pipefail 下会变 141 假失败）
listing=$(unzip -Z1 "$APK")
so_in_apk=$(printf '%s\n' "$listing" | grep -c '^lib/arm64-v8a/lib.*\.so$')
so_expected=$(ls "$OUT/jniLibs/arm64-v8a" | wc -l)
echo "   lib/arm64-v8a/*.so = $so_in_apk（期望 $so_expected）"
if [ "$so_in_apk" != "$so_expected" ]; then echo "!! so 数量不一致" >&2; fail=1; fi
for need in assets/runtime/manifest.json assets/runtime/pi-runtime.zip; do
    if ! printf '%s\n' "$listing" | grep -qx "$need"; then
        echo "!! 缺 $need" >&2; fail=1
    fi
done

aapt2=""
for cand in $(ls -r /root/android-sdk/build-tools/*/aapt2 2>/dev/null) $(ls -r "$HOME/Library/Android/sdk/build-tools/"*/aapt2 2>/dev/null); do
    if [ -x "$cand" ]; then aapt2="$cand"; break; fi
done
if [ -n "$aapt2" ]; then
    ext=$("$aapt2" dump xmltree --file AndroidManifest.xml "$APK" | grep -o 'extractNativeLibs(0x010104ea)=[^ ]*' | head -1)
    echo "   manifest: ${ext:-（未声明 extractNativeLibs）}"
    case "$ext" in
        *=true) ;;
        *) echo "!! extractNativeLibs 必须为 true（so 要被安装器解压成真实文件才能 execve）" >&2; fail=1 ;;
    esac
else
    echo "!! aapt2 不可用，无法断言 extractNativeLibs" >&2; fail=1
fi

tmpx=$(mktemp -d)
unzip -q -o "$APK" 'lib/arm64-v8a/*' -d "$tmpx"
echo "   so 存储方式（体检抽样）："
python3 - "$APK" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
rows = [i for i in z.infolist() if i.filename.startswith("lib/arm64-v8a/")]
store = sum(1 for i in rows if i.compress_type == 0)
defl = sum(1 for i in rows if i.compress_type == 8)
print(f"      stored(未压缩)={store}  deflate={defl}  "
      f"（extractNativeLibs=true 下 deflate 合法：安装期由 PackageManager 解压落盘）")
PY
bash "$HERE/smoke-elf.sh" "$tmpx/lib/arm64-v8a" | tail -3
rm -rf "$tmpx"
[ "$fail" = 0 ] || { echo "APK 体检 FAIL" >&2; exit 4; }
echo "   APK 体检 PASS"
ls -l "$APK" | awk '{printf "   APK 体积 %.1f MB（%d 字节）\n", $5/1048576, $5}'
