#!/usr/bin/env python3
# 列出指定地址区间内所有 bl/b 的重定位目标（真实被调函数名），并标出所在函数
# 用法: reloc_calls.py <ko> <start_hex> <end_hex>
import sys
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

path, start, end = sys.argv[1], int(sys.argv[2], 16), int(sys.argv[3], 16)
f = open(path, 'rb'); elf = ELFFile(f)
secs = list(elf.iter_sections())
st = [s for s in secs if isinstance(s, SymbolTableSection)][0]

funcs = []
for sym in st.iter_symbols():
    if sym['st_info']['type'] == 'STT_FUNC' and isinstance(sym['st_shndx'], int) and sym['st_size']:
        funcs.append((sym.name, sym['st_value'], sym['st_size'], sym['st_shndx']))
funcs.sort(key=lambda x: x[1])

def fn_of(addr, sh):
    for n, v, sz, s in funcs:
        if s == sh and v <= addr < v + sz:
            return n
    return '?'

reloc = {}
for s in secs:
    if not s.name.startswith('.rela'):
        continue
    tgt = s['sh_info']
    for r in s.iter_relocations():
        sym = st.get_symbol(r['r_info_sym'])
        nm = sym.name
        if not nm and isinstance(sym['st_shndx'], int):
            nm = fn_of(sym['st_value'] + r['r_addend'], sym['st_shndx']) + \
                 f"+0x{r['r_addend']:x}"
        reloc[(tgt, r['r_offset'])] = nm

ti = [i for i, s in enumerate(secs) if s.name == '.text'][0]
data = secs[ti].data()
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM); md.skipdata = True
for i in md.disasm(data[start:end], start):
    nm = reloc.get((ti, i.address))
    if i.mnemonic in ('bl', 'b', 'adrp') and nm:
        print(f"  {i.address:6x}: {i.mnemonic:5s} {i.op_str:24s}  -> {nm}   [in {fn_of(i.address, ti)}]")
    elif i.mnemonic == 'bl':
        print(f"  {i.address:6x}: {i.mnemonic:5s} {i.op_str:24s}  -> (无重定位/本模块内)  [in {fn_of(i.address, ti)}]")
