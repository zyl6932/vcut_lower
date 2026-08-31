#!/usr/bin/env python3
"""
找出哪个函数引用了指定的 rodata 字符串（用 .rela 重定位表，不靠猜）
用法: find_str_ref.py <ko> <字符串>
"""
import sys, io, re
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ko, target = sys.argv[1], sys.argv[2].encode()

elf = ELFFile(open(ko, 'rb'))
secs = list(elf.iter_sections())
st = [s for s in secs if isinstance(s, SymbolTableSection)][0]

# 1) 找字符串在哪个 section 的哪个偏移
loc = []
for i, s in enumerate(secs):
    if not s.name.startswith('.rodata'):
        continue
    d = s.data()
    for m in re.finditer(re.escape(target) + b'\0', d):
        loc.append((i, s.name, m.start()))
if not loc:
    print("未找到该字符串")
    sys.exit(1)
for i, n, o in loc:
    print(f"字符串位于 {n} + 0x{o:x}")

# 2) 函数表
funcs = []
for sym in st.iter_symbols():
    if sym['st_info']['type'] == 'STT_FUNC' and isinstance(sym['st_shndx'], int) and sym['st_size']:
        funcs.append((sym.name, sym['st_value'], sym['st_size'], sym['st_shndx']))

def fn_of(addr, sh):
    for n, v, sz, s in funcs:
        if s == sh and v <= addr < v + sz:
            return n
    return None

# 3) 扫重定位：哪些代码位置指向该字符串
print("\n引用它的函数：")
hits = set()
for s in secs:
    if not s.name.startswith('.rela.'):
        continue
    tgt = s['sh_info']
    for r in s.iter_relocations():
        sym = st.get_symbol(r['r_info_sym'])
        shndx = sym['st_shndx']
        if not isinstance(shndx, int):
            continue
        addr = sym['st_value'] + r['r_addend']
        for i, n, o in loc:
            if shndx == i and addr == o:
                f = fn_of(r['r_offset'], tgt)
                if f:
                    hits.add(f)
                else:
                    hits.add(f"{secs[tgt].name}+0x{r['r_offset']:x}")
for h in sorted(hits):
    print("  " + h)
if not hits:
    print("  （无重定位引用；可能被内联或以立即数拼装）")
