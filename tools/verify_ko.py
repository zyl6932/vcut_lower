#!/usr/bin/env python3
"""
insmod 前的四道验收关卡 —— 拿 donor（确证运行在本机内核上的 OOT kprobe 模块）做基准。
四项全 PASS 才允许 insmod；任一 FAIL 都不要加载。

用法: verify_ko.py <我们的.ko> <donor.ko> [pre_handler符号名]
"""
import sys, struct, io, re
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

VERS_ENT = 8 + 56          # struct modversion_info
DONOR_CFI_FN = 'noop_pre_handler'   # donor 里的 kprobe pre_handler


def load(path):
    f = open(path, 'rb')
    elf = ELFFile(f)
    return elf


def modinfo(elf):
    s = elf.get_section_by_name('.modinfo')
    d = {}
    if not s:
        return d
    for e in s.data().split(b'\0'):
        if b'=' in e:
            k, _, v = e.partition(b'=')
            d[k.decode('utf-8', 'replace')] = v.decode('utf-8', 'replace')
    return d


def versions(elf):
    s = elf.get_section_by_name('__versions')
    out = {}
    if not s:
        return out
    d = s.data()
    for i in range(0, len(d) - VERS_ENT + 1, VERS_ENT):
        crc, = struct.unpack_from('<Q', d, i)
        name = d[i + 8:i + VERS_ENT].split(b'\0')[0].decode('utf-8', 'replace')
        if name:
            out[name] = crc & 0xffffffff
    return out


def this_module_size(elf):
    s = elf.get_section_by_name('.gnu.linkonce.this_module')
    return s['sh_size'] if s else None


def this_module_fields(elf):
    """
    返回 this_module 里各函数指针字段的偏移，如 {'init': 0x188, 'exit': 0x5f8}。
    ★这才是真正能发现 struct module 布局差异的检查——段大小按 64 字节对齐，
      24 字节的差异会被填充完全掩盖（实测踩过：关掉 CONFIG_DEBUG_INFO_BTF_MODULES
      会去掉 btf_data* 四个字段共 24 字节，段大小仍然都是 1600）。
    """
    secs = list(elf.iter_sections())
    st = [s for s in secs if isinstance(s, SymbolTableSection)]
    if not st:
        return {}
    out = {}
    for s in secs:
        if s.name != '.rela.gnu.linkonce.this_module':
            continue
        for r in s.iter_relocations():
            nm = st[0].get_symbol(r['r_info_sym']).name or '?'
            key = 'init' if 'init' in nm else ('exit' if ('exit' in nm or 'cleanup' in nm) else nm)
            out[key] = r['r_offset']
    return out


def cfi_prefix(elf, fnname):
    """取函数前 4 字节 = kCFI 类型哈希"""
    secs = list(elf.iter_sections())
    st = [s for s in secs if isinstance(s, SymbolTableSection)]
    if not st:
        return None
    for sym in st[0].iter_symbols():
        if sym.name != fnname or sym['st_info']['type'] != 'STT_FUNC':
            continue
        shndx = sym['st_shndx']
        if not isinstance(shndx, int):
            continue
        data = secs[shndx].data()
        v = sym['st_value']
        if v < 4:
            return None
        return struct.unpack_from('<I', data, v - 4)[0]
    return None


def main():
    ours_p, donor_p = sys.argv[1], sys.argv[2]
    our_fn = sys.argv[3] if len(sys.argv) > 3 else 'vcut_pre'
    ours, donor = load(ours_p), load(donor_p)

    print(f"我们的模块 : {ours_p}")
    print(f"donor 基准 : {donor_p}")
    print("=" * 74)
    fails = []

    # ---- 关卡1：vermagic 尾串 ----
    om, dm = modinfo(ours), modinfo(donor)
    ov, dv = om.get('vermagic', ''), dm.get('vermagic', '')
    otail = ov.split(' ', 1)[1] if ' ' in ov else ''
    dtail = dv.split(' ', 1)[1] if ' ' in dv else ''
    ok = otail == dtail and otail != ''
    fails.append(ok)
    print(f"[关卡1] vermagic 尾串（MODVERSIONS 下内核只比这部分）")
    print(f"        我们 : {ov}")
    print(f"        donor: {dv}")
    print(f"        尾串  : '{otail}' vs '{dtail}'  -> {'PASS' if ok else 'FAIL'}")

    # ---- 关卡2：符号 CRC ----
    ovs, dvs = versions(ours), versions(donor)
    common = sorted(set(ovs) & set(dvs))
    print(f"\n[关卡2] 符号 CRC（交集 {len(common)} 个，我们共 {len(ovs)} 个）")
    bad = []
    for n in common:
        mark = 'OK ' if ovs[n] == dvs[n] else 'BAD'
        if ovs[n] != dvs[n]:
            bad.append(n)
        print(f"        {mark} {n:<28} 我们=0x{ovs[n]:08x}  donor=0x{dvs[n]:08x}")
    only = sorted(set(ovs) - set(dvs))
    if only:
        print(f"        (donor 无法对照的 {len(only)} 个: {', '.join(only)})")
        print(f"         -> 这些来自我们自建内核的 Module.symvers，靠交集项的一致性间接背书")
    ok2 = not bad and len(common) >= 3
    fails.append(ok2)
    print(f"        -> {'PASS' if ok2 else 'FAIL'}" + (f"  不一致: {bad}" if bad else ""))

    # ---- 关卡3：kCFI 类型哈希 ----
    oc = cfi_prefix(ours, our_fn)
    dc = cfi_prefix(donor, DONOR_CFI_FN)
    ok3 = oc is not None and oc == dc
    fails.append(ok3)
    print(f"\n[关卡3] kCFI 类型哈希（kprobe pre_handler 是间接调用，错了命中即 panic）")
    print(f"        我们 {our_fn:<20} 前4字节 = " + (f"0x{oc:08x}" if oc is not None else "未找到"))
    print(f"        donor {DONOR_CFI_FN:<19} 前4字节 = " + (f"0x{dc:08x}" if dc is not None else "未找到"))
    print(f"        -> {'PASS' if ok3 else 'FAIL'}")

    # ---- 关卡4：struct module 布局（init/exit 字段偏移，段大小会被对齐掩盖）----
    os_, ds_ = this_module_size(ours), this_module_size(donor)
    of, df = this_module_fields(ours), this_module_fields(donor)
    keys = sorted(set(of) | set(df))
    bad4 = [k for k in keys if of.get(k) != df.get(k)]
    ok4 = bool(keys) and not bad4
    fails.append(ok4)
    print(f"\n[关卡4] struct module 布局（this_module 里 init/exit 的字段偏移）")
    print(f"        段大小 我们={os_} donor={ds_}"
          f"   ← 仅供参考：按 64 字节对齐，24 字节的差异会被填充掩盖")
    for k in keys:
        a, b = of.get(k), df.get(k)
        mark = 'OK ' if a == b else 'BAD'
        sa = f"0x{a:x}" if a is not None else "无"
        sb = f"0x{b:x}" if b is not None else "无"
        print(f"        {mark} {k:<6} 我们={sa:<8} donor={sb}")
    print(f"        -> {'PASS' if ok4 else 'FAIL'}"
          + (f"  不一致: {bad4}" if bad4 else ""))

    print("=" * 74)
    if all(fails):
        print("★ 四关全过 —— 与已证明能在本机内核加载运行的模块二进制兼容，可以 insmod")
        return 0
    print("✗ 有关卡未通过 —— 不要 insmod，先排查")
    return 1


if __name__ == '__main__':
    sys.exit(main())
