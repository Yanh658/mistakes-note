#!/usr/bin/env python3
"""把 web/ 同步进 android/app/src/main/assets/。

网页是源，APK 里的 assets 是副本。改了网页没同步，装出来的还是旧页面——
所以 CI 里也用 --check 卡一道。

用法：
    python3 scripts/sync_assets.py          # 同步
    python3 scripts/sync_assets.py --check  # 只检查（不一致时退出码 1）
"""
import filecmp
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"

# 只在服务器/开发时用，不需要打进 APK
SKIP = {"serve.py", "worker.js", "wrangler.toml", "_headers", "部署说明.md"}


def web_files():
    for p in sorted(WEB.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(WEB)
        if rel.name in SKIP:
            continue
        yield rel, p


def main():
    check = "--check" in sys.argv
    missing, diff, extra = [], [], []

    wanted = set()
    for rel, src in web_files():
        wanted.add(str(rel))
        dst = ASSETS / rel
        if not dst.exists():
            missing.append(rel)
        elif not filecmp.cmp(src, dst, shallow=False):
            diff.append(rel)

    if ASSETS.exists():
        for p in ASSETS.rglob("*"):
            if p.is_file() and str(p.relative_to(ASSETS)) not in wanted:
                extra.append(p.relative_to(ASSETS))

    if not (missing or diff or extra):
        print("assets 与 web 一致（%d 个文件）" % len(wanted))
        return 0

    if check:
        for rel in missing:
            print("缺少:", rel)
        for rel in diff:
            print("内容不同:", rel)
        for rel in extra:
            print("多余:", rel)
        print("\nassets 和 web 不一致，先跑一遍: python3 scripts/sync_assets.py")
        return 1

    for rel, src in web_files():
        dst = ASSETS / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    for rel in extra:
        (ASSETS / rel).unlink()
    print("已同步 %d 个文件，删除 %d 个多余文件" % (len(wanted), len(extra)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
