#!/usr/bin/env python3
# 解析 .ko 的 __versions（符号 CRC 表）、.modinfo、以及未定义符号
# 用法: ko_versions.py <ko> [过滤关键字]
import sys, struct, io
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
# struct modversion_info { unsigned long crc; char name[MODULE_NAME_LEN]; }
# MODULE_NAME_LEN = MAX_PARAM_PREFIX_LEN = 64 - sizeof(unsigned long) = 56  -> 条目 64 字节

ko = sys.argv[1]
filt = sys.argv[2] if len(sys.argv) > 2 else None
elf = ELFFile(open(ko, 'rb'))

print(f"===== {ko} =====")
mi = elf.get_section_by_name('.modinfo')
if mi:
    print("--- .modinfo ---")
    for e in mi.data().split(b'\0'):
        if e and (not filt or filt.encode() in e):
            print("  " + e.decode('utf-8', 'replace'))

vs = elf.get_section_by_name('__versions')
if vs:
    d = vs.data()
    ent = 8 + 56
    print(f"--- __versions ({len(d)} bytes / {len(d)//ent} 条, 条目 {ent}B) ---")
    for i in range(0, len(d) - ent + 1, ent):
        crc, = struct.unpack_from('<Q', d, i)
        name = d[i+8:i+ent].split(b'\0')[0].decode('utf-8', 'replace')
        if filt and filt not in name:
            continue
        print(f"  0x{crc & 0xffffffff:08x}  {name}")
else:
    print("--- 无 __versions 段 ---")

st = [s for s in elf.iter_sections() if isinstance(s, SymbolTableSection)]
if st:
    und = sorted({s.name for s in st[0].iter_symbols()
                  if s['st_shndx'] == 'SHN_UNDEF' and s.name})
    print(f"--- 未定义(导入)符号 {len(und)} 个 ---")
    for n in und:
        if not filt or filt in n:
            print("  " + n)
