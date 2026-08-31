#!/usr/bin/env python3
"""
把 vcut_lower.c 里 resolve_offsets() 的逻辑逐行照搬成 Python，
对真机/vendor 模块干跑一遍，验证解码与锚点匹配正确。

★目的：解析器要跑在内核里，出了 bug 就是写错内核内存。
  上机前必须先在用户态证明它能在真实模块上解析出正确偏移。
"""
import struct
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection

SCAN_MAX_INSN = 256
STRUCT_REG = 19
ANCHOR_MARGIN = 50
ANCHOR_VPACK_MV = 3000
ANCHOR_WINDOW = 10
REG_ZR = 31

M_LDR32, V_LDR32 = 0xffc00000, 0xb9400000
M_LDRB, V_LDRB = 0xffc00000, 0x39400000
M_ADD32, V_ADD32 = 0xff800000, 0x11000000
M_SUBS32, V_SUBS32 = 0xff800000, 0x71000000
M_ADDS32, V_ADDS32 = 0xff800000, 0x31000000
M_SUBR32, V_SUBR32 = 0xff000000, 0x6b000000
M_RET, V_RET = 0xfffffc1f, 0xd65f0000

dec_rd = lambda i: i & 0x1f
dec_rn = lambda i: (i >> 5) & 0x1f
dec_rm = lambda i: (i >> 16) & 0x1f
dec_imm12 = lambda i: (i >> 10) & 0xfff

BASE = {
    'current': 0x9f0, 'mean_volt': 0x9fc, 'raw_soc': 0xa24, 'rm_uah': 0xa1c,
    'fcc_uah': 0xa20, 'design_uah': 0xa44, 'cycle': 0xa04, 'temp': 0xa08,
    'soc': 0xa38, 'vcut_fw': 0xa90, 'vcut_delay': 0xa94, 'vcut_sw': 0xa98,
    'vbatt_empty': 0xa9c, 'vpack_low_en': 0xaa1, 'fake_soc': 0xb04,
    'vpack': 0xb90,
}

PROBE_SYM = 'mca_battery_shutdown_check_shutdown_delay'


def load_func(path, sym):
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
    f = open(path, 'rb')
    elf = ELFFile(f)
    secs = list(elf.iter_sections())
    st = next(s for s in secs if isinstance(s, SymbolTableSection))
    for s in st.iter_symbols():
        if s.name == sym and s['st_info']['type'] == 'STT_FUNC':
            data = secs[s['st_shndx']].data()
            off, size = s['st_value'], s['st_size']
            return data[off:off + size]
    raise SystemExit("symbol not found")


def resolve(code, trace=False):
    nw = len(code) // 4
    ldr_imm = [-1] * 32
    ldr_pos = [-9999] * 32
    ldrb_imm = [-1] * 32
    ldrb_pos = [-9999] * 32
    mean = delay = vpack = gate = fsoc = -1
    delay_reg = pend_cmp_reg = -1
    anchors = 0
    n = 0

    for i in range(min(nw, SCAN_MAX_INSN)):
        ins = struct.unpack_from('<I', code, i * 4)[0]
        if (ins & M_RET) == V_RET:
            break
        n += 1

        if (ins & M_LDR32) == V_LDR32 and dec_rn(ins) == STRUCT_REG:
            rd = dec_rd(ins)
            ldr_imm[rd] = dec_imm12(ins) * 4
            ldr_pos[rd] = i
            if trace:
                print(f"  [{i:3d}] +0x{i*4:04x} LDR  w{rd},[x19,#0x{ldr_imm[rd]:x}]")
            continue

        if (ins & M_LDRB) == V_LDRB and dec_rn(ins) == STRUCT_REG:
            rd = dec_rd(ins)
            ldrb_imm[rd] = dec_imm12(ins)
            ldrb_pos[rd] = i
            if trace:
                print(f"  [{i:3d}] +0x{i*4:04x} LDRB w{rd},[x19,#0x{ldrb_imm[rd]:x}]")
            continue

        if (ins & M_ADD32) == V_ADD32:
            rd, rn, imm = dec_rd(ins), dec_rn(ins), dec_imm12(ins)
            if (rd == rn and imm == ANCHOR_MARGIN and rd != REG_ZR
                    and ldr_imm[rd] >= 0 and i - ldr_pos[rd] <= ANCHOR_WINDOW
                    and delay < 0):
                delay = ldr_imm[rd]
                delay_reg = pend_cmp_reg = rd
                anchors |= 1
                if trace:
                    print(f"  [{i:3d}] +0x{i*4:04x} *** 锚点A1 delay=0x{delay:x} (reg w{rd})")
            continue

        if (ins & M_SUBR32) == V_SUBR32 and dec_rd(ins) == REG_ZR:
            rn, rm = dec_rn(ins), dec_rm(ins)
            if (pend_cmp_reg >= 0 and rm == pend_cmp_reg and ldr_imm[rn] >= 0
                    and i - ldr_pos[rn] <= ANCHOR_WINDOW and mean < 0):
                mean = ldr_imm[rn]
                anchors |= 2
                pend_cmp_reg = -1
                if trace:
                    print(f"  [{i:3d}] +0x{i*4:04x} *** 锚点A2 mean=0x{mean:x} (reg w{rn})")
            continue

        if (ins & M_SUBS32) == V_SUBS32 and dec_rd(ins) == REG_ZR:
            rn, imm = dec_rn(ins), dec_imm12(ins)
            if imm == ANCHOR_VPACK_MV and ldr_imm[rn] >= 0 and \
                    i - ldr_pos[rn] <= ANCHOR_WINDOW and vpack < 0:
                vpack = ldr_imm[rn]
                anchors |= 4
                if trace:
                    print(f"  [{i:3d}] +0x{i*4:04x} *** 锚点B1 vpack=0x{vpack:x}")
            if imm == 1 and ldrb_imm[rn] >= 0 and i - ldrb_pos[rn] == 1 and gate < 0:
                gate = ldrb_imm[rn]
                anchors |= 8
                if trace:
                    print(f"  [{i:3d}] +0x{i*4:04x} *** 锚点B2 gate=0x{gate:x}")
            continue

        if (ins & M_ADDS32) == V_ADDS32 and dec_rd(ins) == REG_ZR:
            rn, imm = dec_rn(ins), dec_imm12(ins)
            if imm == 1 and ldr_imm[rn] >= 0 and \
                    i - ldr_pos[rn] <= ANCHOR_WINDOW and fsoc < 0:
                fsoc = ldr_imm[rn]
                anchors |= 16
                if trace:
                    print(f"  [{i:3d}] +0x{i*4:04x} *** 锚点C  fake_soc=0x{fsoc:x}")
            continue

    if mean < 0:
        return None, anchors, n

    delta = mean - BASE['mean_volt']
    offs = {k: v + delta for k, v in BASE.items()}
    offs['mean_volt'] = mean
    if delay >= 0: offs['vcut_delay'] = delay
    if vpack >= 0: offs['vpack'] = vpack
    if gate >= 0:  offs['vpack_low_en'] = gate
    if fsoc >= 0:  offs['fake_soc'] = fsoc
    return offs, anchors, n, delta


def main():
    import sys
    path = sys.argv[1]
    trace = '-v' in sys.argv
    code = load_func(path, PROBE_SYM)
    print(f";; {path}  {PROBE_SYM}  {len(code)} bytes")
    r = resolve(code, trace)
    if r[0] is None:
        print(f"!! 解析失败 anchors=0x{r[1]:x} 扫描 {r[2]} 条")
        return
    offs, anchors, n, delta = r
    print(f"\n扫描 {n} 条指令  anchors=0x{anchors:02x}  delta={delta:+d} (0x{delta:x})")
    print(f"{'字段':16s} {'解析值':>8s}  {'基线':>8s}  差")
    for k in BASE:
        d = offs[k] - BASE[k]
        mark = '' if d == 0 else ('  <== 锚点/例外' if k in
                                  ('mean_volt', 'vcut_delay', 'vpack',
                                   'vpack_low_en', 'fake_soc') else '  <== 位移')
        print(f"  {k:16s} 0x{offs[k]:06x}  0x{BASE[k]:06x}  {d:+d}{mark}")

    names = ['delay', 'mean', 'vpack', 'gate', 'fake_soc']
    miss = [names[i] for i in range(5) if not (anchors >> i) & 1]
    print(f"\n锚点命中: {5 - len(miss)}/5" + (f"  缺失: {miss}" if miss else "  全部命中"))


if __name__ == '__main__':
    main()
