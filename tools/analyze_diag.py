#!/usr/bin/env python3
"""
分析其他机型发回的诊断包（collect.sh 生成的 vcut_diag_*.tar.gz）。

用法: python tools/analyze_diag.py <vcut_diag_xxx.tar.gz | 已解压目录>

检查项（对应"新机型能不能直接用 popsicle 这份 .ko"的每一道门槛）：
  1. 内核 / vermagic / 符号 CRC：module_layout 等与本 .ko patch 值是否一致（不一致 = insmod rc=8）
  2. 目标函数锚点：厂商 .ko 离线反汇编，A/B/C 三个锚点是否唯一命中、struct 是否仍在 x19
     （模块内核侧扫描用的是同一套语义；这里命中 = 手机上自解析大概率成功）
  3. 结构体字段对账：用锚点 + delta 推导出的偏移读 dump，对 sysfs 真值
  4. 模块实际运行结果（观测模式 insmod 后的 t_resolved / armed / validate 日志）
"""
import io
import os
import re
import struct
import sys
import tarfile

from elftools.elf.elffile import ELFFile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from auto_lib.resolver import (Module, ResolveError, PROBE_SYM,  # noqa: E402
                               anchor_delay_mean, anchor_gate_vpack, anchor_fake_soc)

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

PATCHED_CRC = {'module_layout': 0x797f2b3e, 'param_ops_int': 0xd0f4936b}   # patch_crc 改写的两个
OUR_KO = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'ksumodule', 'vcut_lower.ko')
OUR_IMPORTS = ['__stack_chk_fail', '_printk', 'memset', 'param_ops_int', 'register_kprobe',
               'register_kretprobe', 'unregister_kprobe', 'unregister_kretprobe', 'module_layout']
SRC = os.path.join(HERE, '..', 'vcut_lower.c')


def load_base():
    """从 vcut_lower.c 读 BASE_*，保持与模块同步"""
    base = {}
    for m in re.finditer(r'static const int BASE_(\w+)\s*=\s*(0x[0-9a-fA-F]+);',
                         open(SRC, encoding='utf-8').read()):
        base[m.group(1).lower()] = int(m.group(2), 16)
    return base


def open_pkg(path):
    if os.path.isdir(path):
        return path
    out = os.path.join(HERE, '..', 'build', 'diag')
    os.makedirs(out, exist_ok=True)
    with tarfile.open(path) as t:
        names = t.getnames()
        for n in names:                 # 只解包普通文件、不允许越出目标目录
            if n.startswith('/') or '..' in n.split('/'):
                raise SystemExit(f'可疑路径 {n}')
        t.extractall(out, filter='data') if sys.version_info >= (3, 12) else t.extractall(out)
    return os.path.join(out, names[0].split('/')[0])


def parse_diag(txt):
    kv, dump, sec = {}, {}, ''
    autooff, vcut = [], {}
    for line in txt.splitlines():
        m = re.match(r'===== (.*) =====', line)
        if m:
            sec = m.group(1)
            continue
        if sec.startswith('结构体 dump'):
            m = re.match(r'o([0-9a-f]+)=(-?\d+)$', line)
            if m:
                dump[int(m.group(1), 16)] = int(m.group(2))
        elif sec == 'autooff 试运行':
            autooff.append(line)
        elif sec == 'vcut_lower 模块状态':
            m = re.match(r'(\w+)=(.*)$', line)
            if m:
                vcut[m.group(1)] = m.group(2).strip()
        m = re.match(r'([\w./:-]+)=(.*)$', line)
        if m:
            key = m.group(1)
            if sec in ('battery', 'fg_master'):
                key = sec + '/' + key
            kv.setdefault(key, m.group(2).strip())
    return kv, dump, autooff, vcut


def ko_versions(path):
    elf = ELFFile(open(path, 'rb'))
    crcs, vermagic = {}, None
    sec = elf.get_section_by_name('__versions')
    if sec:
        d = sec.data()
        for i in range(0, len(d), 64):
            crc = struct.unpack_from('<Q', d, i)[0]
            name = d[i + 8:i + 64].split(b'\0')[0].decode()
            crcs[name] = crc & 0xffffffff
    mi = elf.get_section_by_name('.modinfo')
    if mi:
        for e in mi.data().split(b'\0'):
            if e.startswith(b'vermagic='):
                vermagic = e[9:].decode()
    return crcs, vermagic


def i32(kv, k):
    try:
        return int(kv.get(k, ''))
    except ValueError:
        return None


def main():
    root = open_pkg(sys.argv[1])
    txt = open(os.path.join(root, 'diag.txt'), encoding='utf-8', errors='replace').read()
    kv, dump, autooff, vcut = parse_diag(txt)
    base = load_base()
    verdict = []

    print(f"== 机型: {kv.get('ro.product.device')} / {kv.get('ro.product.model')} "
          f"/ {kv.get('ro.product.marketname', '')}")
    print(f"   系统: {kv.get('ro.build.version.incremental')}  SoC: {kv.get('ro.soc.model')}")
    print(f"   内核: {kv.get('uname', '').split(' ')[2] if kv.get('uname') else '?'}")

    # ---------- 1. CRC / vermagic ----------
    kos = [f for f in os.listdir(root) if f.endswith('.ko')]
    print('\n[1] 符号 CRC / vermagic')
    if not kos:
        print('   ✗ 诊断包里没有厂商 .ko')
        verdict.append('缺厂商 .ko，无法离线分析')
        return
    ko = os.path.join(root, kos[0])
    crcs, vm = ko_versions(ko)
    print(f'   厂商模块 {kos[0]}  vermagic={vm}')
    crcdir = os.path.join(root, 'crc')
    for f in sorted(os.listdir(crcdir)) if os.path.isdir(crcdir) else []:
        for k, v in ko_versions(os.path.join(crcdir, f))[0].items():
            crcs.setdefault(k, v)
    ours = ko_versions(OUR_KO)[0]          # 本 .ko（已 patch）实际携带的 CRC
    crc_bad = []
    for s in OUR_IMPORTS:
        if s not in crcs:
            print(f'   {s:22s} （诊断包里无来源）')
            continue
        exp = ours.get(s)
        ok = exp is None or crcs[s] == exp
        print(f'   {s:22s} 0x{crcs[s]:08x} ' + ('OK' if ok else f'✗ 本 .ko 为 0x{exp:08x}'))
        if not ok:
            crc_bad.append(s)
    if crc_bad:
        args = ' '.join(f'{s}={crcs[s]:08x}' for s in crc_bad)
        verdict.append(f'CRC 不同（{", ".join(crc_bad)}）→ 出该机专用 .ko：'
                       f'python tools/patch_crc.py build/vcut_lower.ko build/vcut_lower_<机型>.ko {args}'
                       '（只差 module_layout/param_ops_int 可直接 patch；其他符号也不同说明内核换代，需对应内核树重编）')
    elif 'module_layout' not in crcs:
        verdict.append('厂商 .ko 里没有 module_layout，CRC 无法离线确认，看实际 insmod rc')
    else:
        print('   → CRC 与本 .ko 一致，可直接加载')

    # ---------- 2. 锚点 ----------
    print('\n[2] 目标函数锚点（离线反汇编）')
    anchors = {}
    mod = Module(ko)
    try:
        insns = mod.func(PROBE_SYM)
        print(f'   {PROBE_SYM}: {len(insns)} 条指令')
        for nm, fn in (('A delay/mean', lambda: anchor_delay_mean(insns)),
                       ('B vpack/gate', lambda: anchor_gate_vpack(insns)),
                       ('C fake_soc', lambda: {'fake_soc': anchor_fake_soc(mod)})):
            try:
                r = fn()
                got = {k: v for k, v in r.items() if isinstance(v, int) and not k.endswith(('idx', 'cmpi'))}
                anchors.update(got)
                print(f'   ✓ {nm}: ' + ' '.join(f'{k}=0x{v:x}' for k, v in got.items()))
            except ResolveError as e:
                print(f'   ✗ {nm}: {e}')
        # 内核侧锚点 C 用的是目标函数里的 ldr + cmn #1，单独确认一次
        cmn = [it for it in insns if it.mnemonic == 'cmn' and it.op_str.endswith('#1')]
        print(f'   内核侧锚点 C（cmn w?,#1）出现 {len(cmn)} 次')
    finally:
        mod.close()
    if not {'delay', 'mean'} <= anchors.keys():
        verdict.append('锚点 A 未命中 → 模块会拒绝加载（rc=19），需人工反汇编找新锚点')

    # ---------- 3. 字段对账 ----------
    print('\n[3] 结构体字段对账（锚点定 + delta 推导，对 sysfs）')
    if not dump:
        print('   ✗ 无 dump（tracefs 不可用或目标函数未命中）')
    elif 'mean' in anchors:
        delta = anchors['mean'] - base['mean_volt']
        print(f'   delta = {delta:+d} (0x{delta:x})，dump 覆盖 0x{min(dump):x}..0x{max(dump):x}')

        def word(off):
            return dump.get(off - off % 4)

        def byte(off):
            w = word(off)
            return None if w is None else (w & 0xffffffff) >> (8 * (off % 4)) & 0xff
        dl = anchors.get('delay', base['vcut_delay'] + delta)
        rows = [
            ('mean_volt', anchors['mean'], word(anchors['mean']), f"voltage_now/1000={(i32(kv, 'battery/voltage_now') or 0)//1000}"),
            ('vcut_fw', dl - 4, word(dl - 4), f"fg cutoff_vol={kv.get('fg_master/cutoff_vol')}"),
            ('vcut_delay', dl, word(dl), '原厂关机电压闸'),
            ('vcut_sw', dl + 4, word(dl + 4), '应 ≤ delay'),
            ('vbatt_empty', dl + 8, byte(dl + 8), 'u8 0/1'),
        ]
        if 'gate' in anchors:
            rows.append(('vpack_gate', anchors['gate'], byte(anchors['gate']), 'u8，1=3000mV 硬闸启用'))
        if 'vpack' in anchors:
            rows.append(('vpack', anchors['vpack'], word(anchors['vpack']), f"fg vbatt={kv.get('fg_master/vbatt')}"))
        if 'fake_soc' in anchors:
            rows.append(('fake_soc', anchors['fake_soc'], word(anchors['fake_soc']), '应为 -1'))
        for nm, k, truth in (('current', 'current', 'battery/current_now'), ('cycle', 'cycle', 'fg_master/qmax_cyclecount'),
                             ('temp', 'temp', 'battery/temp'), ('rm', 'rm_uah', 'fg_master/rm'),
                             ('fcc', 'fcc_uah', 'fg_master/charger_full'), ('raw_soc', 'raw_soc', '≈rm/fcc×10000'),
                             ('soc', 'soc', 'battery/capacity'), ('design', 'design_uah', 'fg_master/design_capacity')):
            off = base[k] + delta
            rows.append((nm, off, word(off), f'{truth}={kv.get(truth, "")}'))
        for nm, off, val, note in rows:
            print(f'   {nm:12s} 0x{off:03x} = {str(val):>10s}   {note}')
        fw, d_, sw = word(dl - 4), word(dl), word(dl + 4)
        if None not in (fw, d_, sw) and 2400 <= sw <= d_ <= fw <= 3600:
            print(f'   → 原厂关机电压 fw/delay/sw = {fw}/{d_}/{sw} mV（结构合理）')
        else:
            verdict.append('vcut 簇读数不合理 → delay 相对位置可能变了，需人工核对 dump')

    # ---------- 4. 运行结果 ----------
    print('\n[4] 观测模式运行结果')
    for line in autooff:
        if line.startswith('autooff:') or line.startswith('ovr_'):
            print('   ' + line)
    if vcut:
        keys = ['t_resolved', 'sig_ok', 't_anchors', 't_delta', 'armed', 't_ovr', 't_cyc_ok', 't_hits', 'enable', 't_wrote']
        print('   ' + ' '.join(f'{k}={vcut.get(k)}' for k in keys))
        if vcut.get('armed') != '1':
            verdict.append('armed≠1：validate 未通过，看 boot.log/dmesg 的校验失败行')
    else:
        rc = re.findall(r'insmod rc=(\d+)', txt)
        print(f'   模块未加载；boot.log insmod rc = {rc[-3:] if rc else "无记录"}')
        if '命中 0 次' in txt and '★armed=0' in txt:
            print('   ⚠ 卸载时命中 0 次：v1.6 service.sh 只等 5s 的误判（函数约 10s 才调用一次），'
                  '不是校验失败；v1.6.1 已修')
        if rc and rc[-1] == '8':
            verdict.append('insmod rc=8：CRC/ABI 不符（见 [1]）')
        elif rc and rc[-1] == '19':
            verdict.append('insmod rc=19：锚点自解析失败（见 [2]）')
    for line in txt.splitlines():
        if '校验通过' in line or '校验失败' in line or '自解析' in line:
            print('   ' + line.strip()[:200])

    print('\n========== 结论 ==========')
    if verdict:
        for v in verdict:
            print('  ✗ ' + v)
    else:
        print('  ✓ 各项通过：可以让对方在 /data/adb/vcut_lower/config.sh 加 MODE=full 并设定关机电压')


if __name__ == '__main__':
    main()
