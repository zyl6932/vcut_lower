#!/usr/bin/env python3
"""
★自愈能力回归测试。

解析器要跑在内核里，出了 bug 就是写错内核内存。上机前必须证明它不只是能
"复述已知答案"，而是真的能应对【未知的偏移变化】。

做法：把真机模块目标函数里所有 [x19,#imm] 的立即数整体平移，合成出
"未来的 build"，再让解析器去解。平移量覆盖正负、大小、以及历史上真实
出现过的例外（gate 偏移量不遵守位移）。

判据：解析结果必须与实际平移后的偏移完全一致 —— 尤其是 gate，
因为它是唯一"写错就会中招高频标志"的字段。
"""
import struct
import sys

from resolver_dryrun import (load_func, resolve, BASE, PROBE_SYM,
                             M_LDR32, V_LDR32, M_LDRB, V_LDRB,
                             dec_rn, dec_imm12, STRUCT_REG)


def synthesize(code, delta, gate_extra=0):
    """
    把函数里所有 [x19,#imm] 的立即数平移 delta，合成"未来 build"。
    gate_extra: 额外给 gate 字节的偏移（模拟历史上出现过的 ±1 例外）
    返回 (新 code, 真实偏移表)
    """
    buf = bytearray(code)
    truth = {}
    # 先算基线里 gate 是哪个立即数（用 ldrb 且值等于 BASE 的 gate）
    base_gate = BASE['vpack_low_en']

    for i in range(len(code) // 4):
        off = i * 4
        ins = struct.unpack_from('<I', code, off)[0]

        if (ins & M_LDR32) == V_LDR32 and dec_rn(ins) == STRUCT_REG:
            imm = dec_imm12(ins)
            old = imm * 4
            new = old + delta
            if new < 0 or new % 4:
                continue
            ni = (ins & ~(0xfff << 10)) | ((new // 4) << 10)
            struct.pack_into('<I', buf, off, ni)
            truth.setdefault(old, new)

        elif (ins & M_LDRB) == V_LDRB and dec_rn(ins) == STRUCT_REG:
            imm = dec_imm12(ins)
            old = imm
            new = old + delta + (gate_extra if old == base_gate else 0)
            if new < 0:
                continue
            ni = (ins & ~(0xfff << 10)) | (new << 10)
            struct.pack_into('<I', buf, off, ni)
            truth.setdefault(old, new)

    real = {k: v + delta for k, v in BASE.items()}
    if gate_extra:
        real['vpack_low_en'] = BASE['vpack_low_en'] + delta + gate_extra
    return bytes(buf), real


def check(name, code, expect, tol_fields=('vpack_low_en',)):
    r = resolve(code)
    if r[0] is None:
        print(f"  ✗ {name}: 解析失败 anchors=0x{r[1]:x}")
        return False
    offs, anchors, n, delta = r
    bad = []
    for k, v in expect.items():
        if offs[k] != v:
            bad.append(f"{k}: 解析=0x{offs[k]:x} 实际=0x{v:x}")
    ok = not bad
    print(f"  {'PASS' if ok else 'FAIL'} {name}: anchors=0x{anchors:02x} "
          f"delta={delta:+d} 扫描{n}条" + (f"  差异: {bad}" if bad else ""))
    return ok


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else 'mca_strategy_fg_comp.ko'
    code = load_func(path, PROBE_SYM)
    print(f";; 基线模块: {path}")
    print(f";; 目标函数: {PROBE_SYM} ({len(code)} bytes)\n")

    allok = True

    print("=== [1] 基线自洽：不改动，解析结果应等于已知偏移 ===")
    allok &= check("delta=0", code, dict(BASE))

    print("\n=== [2] 正向位移（模拟 struct 后移）===")
    for d in (0x4, 0x10, 0x20, 0x40, 0x100):
        c, real = synthesize(code, d)
        allok &= check(f"delta=+0x{d:x}", c, real)

    print("\n=== [3] 负向位移（模拟 struct 前移）===")
    for d in (-0x4, -0x10, -0x40):
        c, real = synthesize(code, d)
        allok &= check(f"delta={d:+d}(0x{d:x})", c, real)

    print("\n=== [4] ★gate 例外（历史上真实出现过 ±1）===")
    print("    位移结果会落在前一个高频标志上，锚点必须把它拉回来")
    for d, ge in ((0x10, 1), (0x10, -1), (0x20, 1), (0x0, 1)):
        c, real = synthesize(code, d, gate_extra=ge)
        allok &= check(f"delta=+0x{d:x} gate额外{ge:+d}", c, real)

    print("\n" + "=" * 60)
    if allok:
        print("★全部通过 —— 解析器能应对未知的 struct 位移与 gate 例外")
    else:
        print("✗ 有失败用例 —— 不要上机")
    return 0 if allok else 1


if __name__ == '__main__':
    sys.exit(main())
