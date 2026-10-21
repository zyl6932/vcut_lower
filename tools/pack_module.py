#!/usr/bin/env python3
"""
打包可刷入的模块 zip：ksumodule/ 全部文件 + 配套 App（app/ 的 release APK，作为 vcutlower.apk）。

用法: python tools/pack_module.py            → build/vcut_lower-<version>.zip
前置: ksumodule/vcut_lower.ko 已是 patch_crc + 验收过的产物；app 已 ./gradlew assembleRelease
"""
import os
import re
import sys
import zipfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
SRC = os.path.join(ROOT, 'ksumodule')
APK = os.path.join(ROOT, 'app', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk')


def main():
    prop = open(os.path.join(SRC, 'module.prop'), encoding='utf-8').read()
    ver = re.search(r'^version=(\S+)', prop, re.M).group(1)
    out = os.path.join(ROOT, 'build', f'vcut_lower-{ver}.zip')
    if not os.path.exists(APK):
        sys.exit(f'缺少 App：{APK}（先在 app/ 下 ./gradlew assembleRelease）')

    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        def add(path, arc):
            data = open(path, 'rb').read()
            # 脚本里混进 CRLF 在手机上会直接执行失败
            if not arc.endswith(('.ko', '.apk')) and b'\r' in data:
                sys.exit(f'{arc} 含 CRLF')
            zi = zipfile.ZipInfo(arc)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = (0o755 if arc.endswith('.sh') or arc.startswith('META-INF') else 0o644) << 16
            z.writestr(zi, data)

        for root, _, files in os.walk(SRC):
            for f in files:
                p = os.path.join(root, f)
                add(p, os.path.relpath(p, SRC).replace(os.sep, '/'))
        add(APK, 'vcutlower.apk')

    names = zipfile.ZipFile(out).namelist()
    print(f'{out}  ({os.path.getsize(out) // 1024} KB, {len(names)} 个文件)')
    for n in names:
        print('  ' + n)


if __name__ == '__main__':
    main()
