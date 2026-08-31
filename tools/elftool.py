#!/usr/bin/env python3
"""ELF .ko analysis: list symbols and disassemble AArch64 functions with capstone.
Usage:
  elftool.py <module.ko> syms [pattern]
  elftool.py <module.ko> dis <funcname|addr> [count]
  elftool.py <module.ko> xrefs <string|funcname>
"""
import sys, re
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

def load(path):
    f = open(path, 'rb')
    elf = ELFFile(f)
    sections = list(elf.iter_sections())
    byname = {s.name: s for s in sections}
    symtab = None
    for s in sections:
        if isinstance(s, SymbolTableSection):
            symtab = s
            break
    symbols = []
    for sym in symtab.iter_symbols():
        if sym['st_info']['type'] == 'STT_FUNC':
            symbols.append({
                'name': sym.name,
                'value': sym['st_value'],
                'size': sym['st_size'],
                'sec': sym['st_shndx'],
            })
    return elf, sections, byname, symbols

def sec_of(elf, idx):
    # convert section index to name
    secs = list(elf.iter_sections())
    if 0 <= idx < len(secs):
        return secs[idx].name
    return f"sec{idx}"

def get_sec_bytes(sections, name):
    for s in sections:
        if s.name == name:
            return s.data()
    return b''

def resolve_addr_to_sec(elf, addr):
    for s in elf.iter_sections():
        a = s['sh_addr']
        sz = s['sh_size']
        if a <= addr < a + sz:
            return s.name, addr - a
    return None, None

def main():
    if len(sys.argv) < 3:
        print(__doc__); return
    path, cmd = sys.argv[1], sys.argv[2]
    elf, sections, byname, symbols = load(path)
    symtab = None
    for s in sections:
        if isinstance(s, SymbolTableSection):
            symtab = s
            break
    if cmd == 'syms':
        pat = sys.argv[3] if len(sys.argv) > 3 else ''
        for s in sorted(symbols, key=lambda x: x['name']):
            if pat.lower() in s['name'].lower():
                print(f"{s['name']:60s} 0x{s['value']:x} size={s['size']} sec={s['sec']}")
    elif cmd == 'dis':
        target = sys.argv[3]
        count = int(sys.argv[4]) if len(sys.argv) > 4 else 0
        fn = next((s for s in symbols if s['name'] == target), None)
        if fn is None:
            print(f"func {target} not found"); return
        secname = sec_of(elf, fn['sec'])
        data = get_sec_bytes(sections, secname)
        if not data:
            print(f"no bytes for {secname}"); return
        code = data[fn['value']:fn['value']+fn['size']]
        md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
        md.detail = False
        print(f";; {target} @ {secname}+0x{fn['value']:x} size=0x{fn['size']:x} ({len(code)} bytes)")
        # build rodata string index
        rodata = b''
        roaddr = 0
        for s in elf.iter_sections():
            if s.name in ('.rodata', '.rodata.str1.1', '.rodata.str1.8', '.rodata.str1.32'):
                rodata = s.data(); roaddr = s['sh_addr']; break
        rostrs = {}
        if rodata:
            for m in re.finditer(rb'[ -~]{3,}', rodata):
                rostrs[roaddr + m.start()] = m.group(0).decode()
        # ---- build relocation map for this text section: reloc_offset -> target(sec, addr) ----
        # find the section index of the func's text section
        text_idx = fn['sec']
        relocs = {}   # offset_in_sec -> (secname, addr_in_sec)
        for s in elf.iter_sections():
            if not s.name.startswith('.rela'):
                continue
            # s['sh_info'] = section index the relocations apply to
            if s['sh_info'] != text_idx:
                continue
            for rel in s.iter_relocations():
                r_off = rel['r_offset']
                r_sym = rel['r_info_sym']
                r_type = rel['r_info_type']
                r_add = rel['r_addend']
                # resolve target symbol -> (secname, addr_in_sec)
                sym = symtab.get_symbol(r_sym) if symtab else None
                if sym is None:
                    continue
                shndx = sym['st_shndx']
                if not isinstance(shndx, int):
                    continue
                t_secname = sec_of(elf, shndx)
                t_addr = sym['st_value'] + r_add
                relocs[r_off] = (t_secname, t_addr)
        # ---- build rodata string index keyed by (secname, offset) ----
        rostrs = {}
        for s in elf.iter_sections():
            if s.name.startswith('.rodata'):
                for m in re.finditer(rb'[ -~]{3,}', s.data()):
                    rostrs[(s.name, m.start())] = m.group(0).decode()
        emitted = 0
        for i in md.disasm(code, fn['value']):
            txt = f"  {i.address:08x}:  {i.mnemonic:8s} {i.op_str}"
            # check if this instruction has a relocation
            rel = relocs.get(i.address, None)
            if rel:
                tsec, taddr = rel
                if tsec.startswith('.rodata'):
                    txt += f"   ; -> .rodata+0x{taddr:x}"
                    if (tsec, taddr) in rostrs:
                        txt += f" = \"{rostrs[(tsec,taddr)]}\""
                else:
                    txt += f"   ; -> {tsec}+0x{taddr:x}"
            print(txt)
            emitted += 1
            if count and emitted >= count:
                break
    else:
        print(__doc__)

if __name__ == '__main__':
    main()
