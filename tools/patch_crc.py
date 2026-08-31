#!/usr/bin/env python3
"""
改写 .ko 的 __versions（以及 EXTENDED_MODVERSIONS 的 __version_ext_crcs）里某个符号的 CRC。

⚠️ 这是**刻意绕过内核的一项 ABI 断言**，只有在已用独立手段（设备 BTF 字段级比对 +
   this_module 的 init/exit 偏移）证明相关结构体布局确实一致时才可使用。
   每次只改必要的那一个符号，并记录原值。

用法: patch_crc.py <in.ko> <out.ko> <符号名>=<新CRC十六进制> [<符号名>=<CRC> ...]
"""
import sys, struct, io, shutil
from elftools.elf.elffile import ELFFile

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ENT = 8 + 56   # struct modversion_info


def main():
    src, dst = sys.argv[1], sys.argv[2]
    want = {}
    for a in sys.argv[3:]:
        k, _, v = a.partition('=')
        want[k] = int(v, 16)

    shutil.copyfile(src, dst)
    with open(dst, 'r+b') as f:
        elf = ELFFile(f)
        done = {}

        # --- 主 __versions 表 ---
        vs = elf.get_section_by_name('__versions')
        if vs:
            base, data = vs['sh_offset'], vs.data()
            for i in range(0, len(data) - ENT + 1, ENT):
                name = data[i + 8:i + ENT].split(b'\0')[0].decode('utf-8', 'replace')
                if name in want:
                    old, = struct.unpack_from('<Q', data, i)
                    f.seek(base + i)
                    f.write(struct.pack('<Q', want[name]))
                    done[name] = (old & 0xffffffff, want[name])
                    print(f"  __versions        {name}: "
                          f"0x{old & 0xffffffff:08x} -> 0x{want[name]:08x}")

        # --- EXTENDED_MODVERSIONS 的并行表（长符号名用）---
        ec = elf.get_section_by_name('__version_ext_crcs')
        en = elf.get_section_by_name('__version_ext_names')
        if ec and en:
            names = en.data().split(b'\0')
            crcs = ec.data()
            order = [n.decode('utf-8', 'replace') for n in names if n]
            for idx, nm in enumerate(order):
                if nm in want and idx * 4 + 4 <= len(crcs):
                    old, = struct.unpack_from('<I', crcs, idx * 4)
                    f.seek(ec['sh_offset'] + idx * 4)
                    f.write(struct.pack('<I', want[nm]))
                    print(f"  __version_ext_crcs {nm}: 0x{old:08x} -> 0x{want[nm]:08x}")

    missing = set(want) - set(done)
    if missing:
        print(f"  ⚠️ 未在 __versions 中找到: {', '.join(sorted(missing))}")
        return 1
    print(f"已写入 {dst}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
