#!/usr/bin/env python3
"""第 0 步侦察辅助：从 Termux apt Packages 索引里查包元数据（Version/Filename/Size/SHA256/Depends）。

用法：
  pkg-index.py <Packages路径> <包名> [<包名> ...]
  pkg-index.py <Packages路径> --tsv <包名> ...        # 机器可读，供 build-jniLibs.sh 钉版本
"""
import sys


def parse(path):
    """返回 {package: [ stanza(dict), ... ]}，Termux 索引一个包名通常只有一个版本。"""
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    out = {}
    for block in text.split("\n\n"):
        if not block.strip():
            continue
        fields = {}
        for line in block.splitlines():
            if line.startswith(" ") or ": " not in line:
                continue  # 多行字段的续行，索引里用到的键都是单行
            key, value = line.split(": ", 1)
            fields.setdefault(key.strip(), value)
        name = fields.get("Package")
        if name:
            out.setdefault(name, []).append(fields)
    return out


def main(argv):
    if len(argv) < 3:
        sys.stderr.write(__doc__)
        return 2
    index, rest = argv[1], argv[2:]
    tsv = "--tsv" in rest
    names = [r for r in rest if not r.startswith("--")]
    pkgs = parse(index)
    for name in names:
        stanzas = pkgs.get(name)
        if not stanzas:
            sys.stderr.write("MISSING in index: " + name + "\n")
            continue
        d = stanzas[0]
        if tsv:
            print("\t".join([
                d.get("Package", ""), d.get("Version", ""), d.get("Filename", ""),
                d.get("Size", ""), d.get("SHA256", ""), d.get("Depends", ""),
            ]))
        else:
            print("Package:  " + d.get("Package", ""))
            print("  Version: " + d.get("Version", ""))
            print("  Filename: " + d.get("Filename", ""))
            print("  Size: " + d.get("Size", "") + "  SHA256: " + d.get("SHA256", ""))
            print("  Depends: " + d.get("Depends", ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
