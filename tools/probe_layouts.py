#!/usr/bin/env python3
"""
从设备上的 .ko 反推 module_layout CRC 所覆盖的各结构体的真实大小：
  struct kernel_param   <- __param 段大小 / __param_<name> 符号个数
  struct kernel_symbol  <- __ksymtab* 段大小 / 其中符号个数
  struct tracepoint     <- __tracepoints 段
  struct modversion_info<- __versions 段 / 条目数（已知 64）
用法: probe_layouts.py <ko> [<ko> ...]
"""
import sys, io, re
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')


def analyze(path):
    try:
        elf = ELFFile(open(path, 'rb'))
    except Exception as e:
        print(f"{path}: 无法解析 {e}")
        return
    secs = {s.name: s for s in elf.iter_sections() if s.name}
    st = [s for s in elf.iter_sections() if isinstance(s, SymbolTableSection)]
    syms = list(st[0].iter_symbols()) if st else []

    out = [f"=== {path} ==="]
    hit = False

    # struct kernel_param
    if '__param' in secs:
        size = secs['__param']['sh_size']
        n = len([s for s in syms if s.name.startswith('__param_') and
                 not s.name.startswith('__param_str') and
                 not s.name.startswith('__param_arr')])
        if n:
            out.append(f"  __param       段={size:5d}  参数数={n:3d}  -> sizeof(kernel_param) = {size // n}")
            hit = True
        else:
            out.append(f"  __param       段={size:5d}  (数不出参数个数)")

    # struct kernel_symbol
    for nm in ('__ksymtab', '__ksymtab_gpl'):
        if nm in secs:
            size = secs[nm]['sh_size']
            n = len([s for s in syms if s.name.startswith('__ksymtab_')])
            out.append(f"  {nm:13s} 段={size:5d}" + (f"  符号数={n}  -> sizeof(kernel_symbol)={size // n}" if n else ""))
            hit = True

    # struct tracepoint
    if '__tracepoints' in secs:
        size = secs['__tracepoints']['sh_size']
        n = len([s for s in syms if s.name.startswith('__tracepoint_')])
        out.append(f"  __tracepoints 段={size:5d}  数={n}" + (f"  -> sizeof(tracepoint)={size // n}" if n else ""))
        hit = True

    # struct modversion_info
    if '__versions' in secs:
        size = secs['__versions']['sh_size']
        out.append(f"  __versions    段={size:5d}  /64 = {size / 64:.2f} 条 "
                   f"{'(整除✓ sizeof=64)' if size % 64 == 0 else '(不整除!)'}")
        hit = True

    # sizeof(struct module)
    if '.gnu.linkonce.this_module' in secs:
        out.append(f"  sizeof(struct module) = {secs['.gnu.linkonce.this_module']['sh_size']}")
        hit = True

    if hit:
        print("\n".join(out))


for p in sys.argv[1:]:
    analyze(p)
