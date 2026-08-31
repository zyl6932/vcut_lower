#!/usr/bin/env python3
"""扫描新 vendor 模块里目标函数中对 strategy struct (x19) 的所有 [x19,#imm] 访问。
同时定位 vpack 硬闸分支（cmp wN,#0xbb8 = 3000）与 vpack gate 字节（ldrb + cmp wN,#1）。
用法: scan19.py <module.ko> [funcname]
"""
import sys, re
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

FUNC = "mca_battery_shutdown_check_shutdown_delay"

def main():
    path = sys.argv[1]
    func = sys.argv[2] if len(sys.argv) > 2 else FUNC
    f = open(path, 'rb')
    elf = ELFFile(f)
    sections = list(elf.iter_sections())
    symtab = next(s for s in sections if isinstance(s, SymbolTableSection))

    fn = None
    for sym in symtab.iter_symbols():
        if sym.name == func and sym['st_info']['type'] == 'STT_FUNC':
            fn = sym
            break
    if fn is None:
        print("func not found:", func)
        return
    secname = sections[fn['st_shndx']].name
    data = next(s.data() for s in sections if s.name == secname)
    off, size = fn['st_value'], fn['st_size']
    code = data[off:off + size]
    print(f";; {func} @ {secname}+0x{off:x} size=0x{size:x} ({len(code)} bytes)")

    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = False
    insns = list(md.disasm(code, off))
    md.detail = True
    insns = list(md.disasm(code, off))

    print(f"\n=== [1] 函数体所有 x19 相对访问（按地址序）===")
    seen = {}
    for i in insns:
        if i.mnemonic in ('ldr', 'ldrb', 'ldrh', 'ldrsw', 'str', 'strb', 'strh', 'ldrsb', 'ldrsh'):
            m = re.search(r'\[x19(?:,\s*#(0x[0-9a-f]+))?\]', i.op_str)
            if m:
                imm = int(m.group(1), 16) if m.group(1) else 0
                scale = 1 if i.mnemonic.endswith('b') else (2 if i.mnemonic.endswith('h') else 4)
                proven = ''
                if imm % scale != 0:
                    proven = '  <!! imm 非对齐>'
                seen.setdefault((imm, i.mnemonic), []).append(i.address - off)
                print(f"  +0x{i.address-off:04x}  {i.mnemonic:6s} {i.op_str:32s} imm=0x{imm:03x}{proven}")

    print(f"\n=== [2] 立即数汇总（imm -> 出现次数/位置）===")
    agg = {}
    for (imm, mn), addrs in seen.items():
        agg.setdefault(imm, []).append((mn, addrs))
    for imm in sorted(agg):
        ent = agg[imm]
        locs = ", ".join(f"+0x{a:04x}" for _, al in ent for a in al)
        mns = ",".join(sorted({m for m, _ in ent}))
        print(f"  0x{imm:04x}  ({mns})  n={sum(len(al) for _,al in ent)}  @ {locs}")

    print(f"\n=== [3] vpack 硬闸分支：cmp wN,#0xbb8 (3000) 上下文 ===")
    for idx, i in enumerate(insns):
        if i.mnemonic == 'cmp' and re.search(r'#0xbb8\b', i.op_str):
            lo = max(0, idx - 12)
            hi = min(len(insns), idx + 8)
            for j in range(lo, hi):
                mark = '>>' if j == idx else '  '
                print(f"  {mark} +0x{insns[j].address-off:04x}  {insns[j].mnemonic:8s} {insns[j].op_str}")
            print("  " + "-" * 50)

    print(f"\n=== [4] gate 字节候选：ldrb wN,[x19,#imm] 紧跟 cmp wN,#1 ===")
    for idx, i in enumerate(insns):
        if i.mnemonic == 'ldrb' and re.match(r'w\d+, \[x19', i.op_str):
            nxt = insns[idx + 1] if idx + 1 < len(insns) else None
            if nxt and nxt.mnemonic == 'cmp' and re.match(r'w\d+, #0x1$', nxt.op_str):
                print(f"  +0x{i.address-off:04x}  {i.mnemonic} {i.op_str}")
                print(f"  +0x{nxt.address-off:04x}  {nxt.mnemonic} {nxt.op_str}   <== GATE 候选")

    print(f"\n=== [5] 三条指纹候选（供对照）===")
    # 旧指纹（OS4.0.0.20）：+0x07c ldrb x19,#0xa91 ; +0x108 ldr w8,[x19,#0xa84] ; +0x10c ldr w9,[x19,#0x9ec]
    for o in (0x07c, 0x108, 0x10c, 0x090, 0x118, 0x11c):
        i = next((z for z in insns if z.address - off == o), None)
        if i:
            raw = int.from_bytes(code[o:o+4], 'little')
            print(f"  +0x{o:03x}  0x{raw:08x}  {i.mnemonic:8s} {i.op_str}")

if __name__ == '__main__':
    main()
