#!/usr/bin/env python3
# 从 donor .ko 反推：sizeof(struct module)、this_module 里 init/exit/name 的偏移、
# 以及 kprobe pre_handler 的 kCFI 前缀哈希
# 用法: ko_layout.py <ko>
import sys, struct, io
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ko = sys.argv[1]
f = open(ko, 'rb'); elf = ELFFile(f)
secs = list(elf.iter_sections())
st = [s for s in secs if isinstance(s, SymbolTableSection)][0]

print(f"===== {ko} =====")
print("--- 各段 ---")
for i, s in enumerate(secs):
    if s.name and s['sh_size']:
        print(f"  [{i:2d}] {s.name:34s} size={s['sh_size']:<8d} align={s['sh_addralign']}")

tm = elf.get_section_by_name('.gnu.linkonce.this_module')
if tm:
    idx = secs.index(tm)
    print(f"\n--- .gnu.linkonce.this_module: sizeof(struct module) = {tm['sh_size']} ---")
    d = tm.data()
    # name 字段：找第一段可打印字符串
    for off in range(0, min(len(d), 256)):
        if d[off:off+4].isalnum():
            s = d[off:].split(b'\0')[0]
            if len(s) > 2:
                print(f"  name 字段偏移 = 0x{off:x}  值 = {s.decode('utf-8','replace')}")
                break
    print("  重定位（= 函数指针字段，如 init/exit）：")
    for s in secs:
        if s.name == '.rela.gnu.linkonce.this_module':
            for r in s.iter_relocations():
                sym = st.get_symbol(r['r_info_sym'])
                nm = sym.name or f"(sec {sym['st_shndx']})"
                print(f"    字段偏移 0x{r['r_offset']:04x}  ->  {nm} + 0x{r['r_addend']:x}")

# 找 kprobe 结构体实例：数据段里含 pre_handler 函数指针的重定位
print("\n--- 可能的 kprobe 实例（.data/.bss 里指向 .text 的重定位）---")
textidx = [i for i, s in enumerate(secs) if s.name == '.text']
for s in secs:
    if not s.name.startswith('.rela.') or 'this_module' in s.name:
        continue
    tgt = secs[s['sh_info']].name if isinstance(s['sh_info'], int) else '?'
    if not (tgt.startswith('.data') or tgt.startswith('.bss') or tgt == '.init.data'):
        continue
    for r in s.iter_relocations():
        sym = st.get_symbol(r['r_info_sym'])
        if sym['st_info']['type'] == 'STT_FUNC' or (sym.name and 'handler' in sym.name.lower()):
            print(f"  {tgt} +0x{r['r_offset']:04x} -> {sym.name or '(local func)'} +0x{r['r_addend']:x}")

# kCFI 前缀：每个被间接调用的函数前 4 字节是 type hash（mov w16/movk 序列或裸 .word）
print("\n--- .text 中带 kCFI 前缀的函数（前4字节 = type hash）---")
text = elf.get_section_by_name('.text')
if text:
    data = text.data()
    funcs = [(sym.name, sym['st_value'], sym['st_size']) for sym in st.iter_symbols()
             if sym['st_info']['type'] == 'STT_FUNC' and sym['st_shndx'] == secs.index(text)]
    funcs.sort(key=lambda x: x[1])
    for n, v, sz in funcs[:40]:
        if v >= 4:
            pre, = struct.unpack_from('<I', data, v - 4)
            print(f"  {n:40s} @0x{v:05x}  前4字节 = 0x{pre:08x}")
