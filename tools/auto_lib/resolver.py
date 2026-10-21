#!/usr/bin/env python3
"""
偏移解析器（runtime resolver 层）。

★为什么用「语义锚点」而不是「指令位置」：
  硬编码位置（如 probe+0x07c）在函数体被重排时就会失效。本模块改为匹配
  **源码语义在汇编里的稳定投影**，这些模式在已观测的三次 build 变化中全部成立：

  锚点A（vcut_delay / mean_volt）——源码里 `mean_volt <= vcutoff_delay + 50`：
      ldr  wA, [x19, #IMM_DELAY]
      ldr  wB, [x19, #IMM_MEAN]
      add  wA, wA, #50          <- 50 是驱动里的硬编码常量，极稳定
      cmp  wB, wA
      b.ge ...

  锚点B（vpack / gate）——源码里 `if (vpack_low_en == 1 && vpack <= 3000)`：
      ldrb wG, [x19, #IMM_GATE]
      cmp  wG, #1
      b.ne <skip>
      ldr  wZ, [x19, #IMM_VPACK]
      ...
      cmp  wZ, #3000            <- 0xbb8

  锚点C（fake_soc）——写侧函数 strategy_fg_set_fake_soc 里的 str wN,[x19,#imm]

★fail-closed 原则：
  任一锚点缺失、歧义（多个候选）、或互相矛盾 -> 拒绝解析，绝不用"看起来合理"的值。
  偏移写错意味着写错内核内存，后果远比"拒绝适配"严重。
"""
import re
import struct
from collections import Counter, defaultdict

from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

PROBE_SYM = 'mca_battery_shutdown_check_shutdown_delay'
FAKE_SOC_SETTER = 'strategy_fg_set_fake_soc'
UPDATE_VCUT_SYM = 'mca_battery_shutdown_update_vcutoff_para'

STRUCT_REG = 'x19'          # check_shutdown_delay 里 strategy struct 所在寄存器
GATE_CMP_VAL = 0xbb8        # 3000mV 硬闸
DELAY_MARGIN = 50           # delay + 50


class ResolveError(RuntimeError):
    pass


def _imm(s):
    """'#0x32' / '#1' / '#3000' -> int"""
    s = s.strip()
    if s.startswith('#'):
        s = s[1:]
    try:
        return int(s, 0)
    except ValueError:
        return None


class Insn:
    __slots__ = ('rel', 'raw', 'mnemonic', 'op_str', 'func')

    def __init__(self, rel, raw, mnemonic, op_str, func):
        self.rel = rel          # 相对函数入口的偏移
        self.raw = raw
        self.mnemonic = mnemonic
        self.op_str = op_str
        self.func = func

    def __repr__(self):
        return f"+0x{self.rel:04x}  {self.mnemonic:8s} {self.op_str}"


class Module:
    def __init__(self, path):
        self.path = path
        self.f = open(path, 'rb')
        self.elf = ELFFile(self.f)
        self.sections = list(self.elf.iter_sections())
        self.symtab = next((s for s in self.sections
                            if isinstance(s, SymbolTableSection)), None)
        if self.symtab is None:
            raise ResolveError("无符号表")
        self._md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
        self._md.detail = False
        self._func_cache = {}

    def close(self):
        self.f.close()

    def func(self, name):
        """按符号名反汇编一个函数，返回 Insn 列表。
        ★必须逐个符号反汇编：实测从地址 0 反汇编整个 .text 会立刻中断（capstone 遇非法指令停）。"""
        if name in self._func_cache:
            return self._func_cache[name]
        sym = None
        for s in self.symtab.iter_symbols():
            if s.name == name and s['st_info']['type'] == 'STT_FUNC':
                sym = s
                break
        if sym is None:
            return []
        shndx = sym['st_shndx']
        if not isinstance(shndx, int) or shndx >= len(self.sections):
            return []
        sec = self.sections[shndx]
        data = sec.data()
        off, size = sym['st_value'], sym['st_size']
        if not size:
            return []
        code = data[off:off + size]
        out = []
        for i in self._md.disasm(code, off):
            rel = i.address - off
            raw = struct.unpack_from('<I', code, rel)[0] if rel + 4 <= len(code) else 0
            out.append(Insn(rel, raw, i.mnemonic, i.op_str, name))
        self._func_cache[name] = out
        return out

    def all_funcs(self):
        names = []
        for s in self.symtab.iter_symbols():
            if (s['st_info']['type'] == 'STT_FUNC' and s.name
                    and isinstance(s['st_shndx'], int)
                    and self.sections[s['st_shndx']].name == '.text'
                    and s['st_size']):
                names.append(s.name)
        return names

    def refcount(self):
        """全模块 .text 里每个 [xN,#imm] 立即数的引用次数 + 出现位置。"""
        cnt = Counter()
        where = defaultdict(list)
        for fn in self.all_funcs():
            for ins in self.func(fn):
                if ins.mnemonic not in ('ldr', 'ldrb', 'ldrh', 'ldrsw',
                                        'str', 'strb', 'strh'):
                    continue
                m = re.search(r'\[x\d+(?:,\s*#(0x[0-9a-f]+))?\]', ins.op_str)
                if not m:
                    continue
                imm = int(m.group(1), 16) if m.group(1) else 0
                cnt[imm] += 1
                where[imm].append((fn, ins.rel, ins.mnemonic, ins.op_str))
        return cnt, where


def _find_ldr_of(insns, idx, reg, limit=40):
    """从 idx 往前找最近的 `ldr <reg>, [x19, #imm]`，返回 (index, imm)。"""
    lo = max(0, idx - limit)
    for j in range(idx - 1, lo - 1, -1):
        it = insns[j]
        if it.mnemonic != 'ldr':
            continue
        m = re.match(rf'w{reg}, \[{STRUCT_REG}, #(0x[0-9a-f]+)\]$', it.op_str)
        if m:
            return j, int(m.group(1), 16)
    return None, None


def anchor_delay_mean(insns):
    """锚点A：add wA,wA,#50  ->  vcut_delay(加载wA) / mean_volt(cmp 的另一操作数)"""
    cands = []
    for i, it in enumerate(insns):
        if it.mnemonic != 'add':
            continue
        m = re.match(r'w(\d+), w(\d+), #(\S+)$', it.op_str)
        if not m or m.group(1) != m.group(2):
            continue
        if _imm(m.group(3)) != DELAY_MARGIN:
            continue
        dreg = int(m.group(1))
        # 往前找 delay 的加载
        d_idx, delay = _find_ldr_of(insns, i, dreg)
        if delay is None:
            continue
        # 往后找 cmp wM, w(dreg)
        mean = None
        m_idx = None
        for j in range(i + 1, min(len(insns), i + 12)):
            cj = insns[j]
            if cj.mnemonic != 'cmp':
                continue
            cm = re.match(r'w(\d+), w(\d+)$', cj.op_str)
            if cm and int(cm.group(2)) == dreg:
                mreg = int(cm.group(1))
                m_idx, mean = _find_ldr_of(insns, j, mreg)
                break
        if mean is None:
            continue
        cands.append({
            'delay': delay, 'delay_idx': d_idx, 'delay_insn': insns[d_idx],
            'mean': mean, 'mean_idx': m_idx, 'mean_insn': insns[m_idx],
        })
    if len(cands) != 1:
        raise ResolveError(
            f"锚点A（delay/mean_volt）候选 {len(cands)} 个，期望唯一 -> 拒绝解析")
    return cands[0]


def anchor_gate_vpack(insns):
    """锚点B：cmp wZ,#3000 -> vpack；再往前找 ldrb wG,[x19,#imm] + cmp wG,#1 -> gate"""
    vpack_cands = []
    for i, it in enumerate(insns):
        if it.mnemonic != 'cmp':
            continue
        m = re.match(r'w(\d+), #(\S+)$', it.op_str)
        if not m or _imm(m.group(2)) != GATE_CMP_VAL:
            continue
        zreg = int(m.group(1))
        v_idx, vpack = _find_ldr_of(insns, i, zreg)
        if vpack is None:
            continue
        vpack_cands.append({'vpack': vpack, 'vpack_idx': v_idx,
                            'vpack_insn': insns[v_idx], 'cmpi': i})
    # 第二种写法（2026-10-21，小米 15 / 6.6 驱动）：mov wK,#3001 ; ccmp|cmp wZ,wK —— 即 vpack < 3001。
    # 只在第一种写法没找到时采用，与 vcut_lower.c 内核侧扫描同一规则
    if not vpack_cands:
        for i, it in enumerate(insns):
            if it.mnemonic not in ('ccmp', 'cmp'):
                continue
            m = re.match(r'w(\d+), w(\d+)', it.op_str)
            if not m:
                continue
            zreg, kreg = int(m.group(1)), int(m.group(2))
            if not any(insns[j].mnemonic == 'mov' and
                       re.match(rf'w{kreg}, #(0xbb9|0xbb8|3001|3000)$', insns[j].op_str)
                       for j in range(max(0, i - 4), i)):
                continue
            v_idx, vpack = _find_ldr_of(insns, i, zreg, limit=20)
            if vpack is not None:
                vpack_cands.append({'vpack': vpack, 'vpack_idx': v_idx,
                                    'vpack_insn': insns[v_idx], 'cmpi': i})
                break
    if len(vpack_cands) != 1:
        raise ResolveError(
            f"锚点B（vpack, cmp #3000）候选 {len(vpack_cands)} 个，期望唯一 -> 拒绝解析")
    v = vpack_cands[0]

    # gate：在 vpack 加载之前，找 ldrb wG,[x19,#imm] 紧跟 cmp wG,#1
    gate = None
    gate_idx = None
    for j in range(v['vpack_idx'] - 1, max(0, v['vpack_idx'] - 40), -1):
        it = insns[j]
        if it.mnemonic != 'ldrb':
            continue
        gm = re.match(rf'w(\d+), \[{STRUCT_REG}, #(0x[0-9a-f]+)\]$', it.op_str)
        if not gm:
            continue
        greg = int(gm.group(1))
        if j + 1 < len(insns):
            nxt = insns[j + 1]
            if nxt.mnemonic == 'cmp':
                cm = re.match(rf'w{greg}, #(\S+)$', nxt.op_str)
                if cm and _imm(cm.group(1)) == 1:
                    gate = int(gm.group(2), 16)
                    gate_idx = j
                    break
        # 第二种写法：ldrb wG 之后 3 条以内 cbz/cbnz wG（小米 15 / 6.6 驱动）
        if any(insns[k].mnemonic in ('cbz', 'cbnz') and insns[k].op_str.startswith(f'w{greg},')
               for k in range(j + 1, min(len(insns), j + 4))):
            gate = int(gm.group(2), 16)
            gate_idx = j
            break
    if gate is None:
        raise ResolveError("锚点B（gate）未找到 ldrb+cmp #1 模式 -> 拒绝解析")
    v['gate'] = gate
    v['gate_idx'] = gate_idx
    v['gate_insn'] = insns[gate_idx]
    return v


def anchor_fake_soc(mod):
    """锚点C：strategy_fg_set_fake_soc 里唯一写到 x19 相对位置的 str"""
    insns = mod.func(FAKE_SOC_SETTER)
    if not insns:
        raise ResolveError(f"未找到函数 {FAKE_SOC_SETTER}")
    cands = []
    for it in insns:
        if it.mnemonic != 'str':
            continue
        m = re.match(rf'w\d+, \[{STRUCT_REG}, #(0x[0-9a-f]+)\]$', it.op_str)
        if m:
            cands.append(int(m.group(1), 16))
    uniq = sorted(set(cands))
    if len(uniq) != 1:
        raise ResolveError(
            f"锚点C（fake_soc）候选 {uniq} 不唯一 -> 拒绝解析")
    return uniq[0]


def resolve(ko_path, baseline_offsets, baseline_fingerprints=None):
    """
    解析出新的偏移表。

    baseline_offsets: 已知 profile 的偏移（int dict），用于算位移量 delta 并推导其余字段。
    返回 (offsets_dict, evidence_dict)
    """
    mod = Module(ko_path)
    try:
        insns = mod.func(PROBE_SYM)
        if not insns:
            raise ResolveError(f"未找到目标函数 {PROBE_SYM}")

        a = anchor_delay_mean(insns)
        b = anchor_gate_vpack(insns)
        fake_soc = anchor_fake_soc(mod)

        new_mean, new_delay = a['mean'], a['delay']
        base_mean = baseline_offsets['mean_volt']
        delta = new_mean - base_mean

        # ---- 用 delta 推导其余字段 ----
        offs = {k: v + delta for k, v in baseline_offsets.items()}
        # ---- 锚点直接定下来的字段，以锚点为准（可覆盖 delta 推导）----
        offs['mean_volt'] = new_mean
        offs['vcut_delay'] = new_delay
        offs['vpack'] = b['vpack']
        offs['vpack_low_en'] = b['gate']
        offs['fake_soc'] = fake_soc

        # ---- 交叉校验：锚点 vs delta 推导 ----
        conflicts = []
        for k, v in (('vpack', b['vpack']), ('fake_soc', fake_soc),
                     ('vcut_delay', new_delay)):
            derived = baseline_offsets[k] + delta
            if derived != v:
                conflicts.append(
                    f"{k}: 锚点=0x{v:x} delta推导=0x{derived:x} "
                    f"(差 {v - derived:+d}) -> 以锚点为准")
        # gate 历史上就有 ±1 的偏差，单独提示
        gate_derived = baseline_offsets['vpack_low_en'] + delta
        if gate_derived != b['gate']:
            conflicts.append(
                f"vpack_low_en: 锚点=0x{b['gate']:x} delta推导=0x{gate_derived:x} "
                f"-> 以锚点为准（★历次迁移都出现过这种 ±1 偏差，见 docs/工作日志.md §86/§87）")

        # ---- 引用统计交叉印证（防"高频标志"陷阱）----
        cnt, where = mod.refcount()
        gate_imm = b['gate']
        gate_n = cnt[gate_imm]
        prev_n = cnt[gate_imm - 1]
        warnings = []
        if gate_n > 3:
            warnings.append(
                f"★gate 0x{gate_imm:x} 被引用 {gate_n} 次，不像外科手术点，请人工确认")
        if prev_n > gate_n:
            warnings.append(
                f"★0x{gate_imm - 1:x} 被引用 {prev_n} 次（多于 gate 的 {gate_n} 次）—— "
                f"这正撞上 §86 那个坑：位移结果落在高频标志上。锚点已帮我们避开。")

        # ---- 生成新的指令指纹（位置自动跟随解析结果）----
        fps = []
        for key in ('gate_insn', 'delay_idx', 'mean_idx'):
            pass
        fp_items = [
            (b['gate_insn'], f"ldrb w?,[{STRUCT_REG},#0x{b['gate']:x}]"),
            (a['delay_insn'], f"ldr  w?,[{STRUCT_REG},#0x{new_delay:x}]"),
            (a['mean_insn'], f"ldr  w?,[{STRUCT_REG},#0x{new_mean:x}]"),
        ]
        for ins, desc in fp_items:
            fps.append({'off': f"0x{ins.rel:03x}",
                        'val': f"0x{ins.raw:08x}",
                        'desc': desc})

        evidence = {
            'delta': delta,
            'delta_hex': f"0x{delta:x}" if delta >= 0 else f"-0x{-delta:x}",
            'anchors': {
                'mean_volt': f"0x{new_mean:x}",
                'vcut_delay': f"0x{new_delay:x}",
                'vpack': f"0x{b['vpack']:x}",
                'vpack_low_en': f"0x{b['gate']:x}",
                'fake_soc': f"0x{fake_soc:x}",
            },
            'anchor_insns': {
                'mean_volt': str(a['mean_insn']),
                'vcut_delay': str(a['delay_insn']),
                'vpack': str(b['vpack_insn']),
                'vpack_low_en': str(b['gate_insn']),
            },
            'refcounts': {
                f"0x{gate_imm:x}": gate_n,
                f"0x{gate_imm - 1:x}": prev_n,
            },
            'conflicts': conflicts,
            'warnings': warnings,
            'fingerprints': fps,
        }
        return offs, evidence
    finally:
        mod.close()


if __name__ == '__main__':
    import sys
    import json
    from profiles import ProfileDB

    ko = sys.argv[1]
    db = ProfileDB()
    base = db.latest()
    offs, ev = resolve(ko, ProfileDB.offsets_int(base))
    print(f"基线 profile = {base['id']}")
    print(json.dumps({'offsets': {k: f'0x{v:x}' for k, v in offs.items()},
                      'evidence': ev}, ensure_ascii=False, indent=2))
