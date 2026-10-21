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
  5. 充电增强（charge.txt，v1.9 起）：控制节点、curr_max 投票、设备树原厂阶梯、温度节点、守护进程记录
     ——结论单独列，不影响 1~4 的「能否开正常模式」
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
        # 源码里 #if 分两套：先出现的是 6.12（17 系列）的编译期基线，6.6（小米 15）那套不覆盖它
        base.setdefault(m.group(1).lower(), int(m.group(2), 16))
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


def parse_sections(txt):
    """charge.txt：按 ===== 节名 ===== 切成 {节名: [行]}"""
    secs, cur = {}, ''
    for line in txt.splitlines():
        m = re.match(r'===== (.*) =====', line)
        if m:
            cur = m.group(1)
            secs.setdefault(cur, [])
        else:
            secs.setdefault(cur, []).append(line)
    return secs


def dt_props(lines):
    """collect.sh dtprop 的输出：名=s:字符串表 / 名=x:十六进制（大端 u32）→ {名: [str] 或 [int]}"""
    out = {}
    for line in lines:
        m = re.match(r'(\S+?)=(s|x):(.*)$', line)
        if not m:
            continue
        name, kind, val = m.groups()
        if kind == 's':
            out[name] = val.split()
        else:
            b = bytes(int(h, 16) for h in val.split() if re.fullmatch(r'[0-9a-fA-F]{2}', h))
            # 兜底：手机上的 tr 分不出字符串时也会写成 x:（以 \0 结尾、其余全是可打印字符 = 字符串表）
            if b.endswith(b'\0') and b.strip(b'\0') and all(32 <= c < 127 for c in b if c):
                out[name] = b.decode('ascii').replace('\0', ' ').split()
            else:
                out[name] = [struct.unpack_from('>i', b, i)[0] for i in range(0, len(b) - len(b) % 4, 4)]
    return out


def ladder_of(tab, ceil=4495):
    """原厂表（三元组：拐点 拐点前电流 拐点后电流）→ customize.sh 会设的默认阶梯（与它同一套规则）"""
    rows = [tab[i:i + 3] for i in range(0, len(tab) - 2, 3)]
    if not rows:
        return [], []
    nodes = [r[0] for r in rows if r[0] < ceil]
    ma = [rows[0][1]] + [r[2] for r in rows if r[0] < ceil]
    return nodes, ma


def cell_vendor(root):
    """diag.txt 的 fg_master 节里的 cell_vendor（atl / lwn …）"""
    sec = ''
    for line in open(os.path.join(root, 'diag.txt'), encoding='utf-8', errors='replace'):
        m = re.match(r'===== (.*) =====', line)
        if m:
            sec = m.group(1)
        elif sec == 'fg_master' and line.startswith('cell_vendor='):
            return line.split('=', 1)[1].strip()
    return ''


def charge_report(root):
    """[5] 充电增强。返回结论列表（只描述充电增强，不影响降关机电压的判定）"""
    print('\n[5] 充电增强（charge.txt）')
    path = os.path.join(root, 'charge.txt')
    if not os.path.exists(path):
        print('   （诊断包里没有 charge.txt：v1.9 之前的 collect.sh）')
        return []
    secs = parse_sections(open(path, encoding='utf-8', errors='replace').read())
    notes = []

    nodes = {}
    for line in secs.get('控制节点', []):
        k, _, v = line.partition('=')
        parts = k.split('/')
        nodes['/'.join(parts[-2:]) if 'curr_max' in parts else parts[-1]] = v
    tr_ok = nodes.get('wired_thermal_remove', '(不存在)') != '(不存在)'
    fc_ok = nodes.get('curr_max/force_val', '(不存在)') != '(不存在)'
    print('   亮屏快充节点 wired_thermal_remove : ' + ('有，当前 ' + nodes['wired_thermal_remove'] if tr_ok else '✗ 没有'))
    print('   提升电流节点 curr_max/force_val    : '
          + ('有，当前 %s（force_active=%s）' % (nodes['curr_max/force_val'], nodes.get('curr_max/force_active'))
             if fc_ok else '✗ 没有'))

    # curr_max 投票：谁在投、生效值（这台机器的电流瓶颈在哪一方）
    blk, on = [], False
    for line in secs.get('投票项（business-votable）', []):
        if line.startswith('--- '):
            on = line[4:].strip() == 'curr_max'
            continue
        if on and line.strip():
            blk.append(line.strip())
    if blk:
        print('   curr_max 投票：')
        for line in blk[:14]:
            print('     ' + line[:150])
    else:
        print('   curr_max 投票：（没有 business-votable/curr_max）')

    qc = dt_props(secs.get('dt/mca_strategy_quick_charge', []))
    bk = dt_props(secs.get('dt/mca_strategy_buckchg_class', []))
    if qc:
        tabs = sorted(k for k in qc if re.match(r'(ffc|normal)_para_\d+_\d+(_[a-z]+)?$', k))
        print('   原厂阶梯表：' + (' '.join(tabs) or '（无）'))
        for k in ('real_curr_max', 'max_vbat'):
            if qc.get(k):
                print(f'   {k} = {qc[k][0]}')
        if bk.get('ffc_temp_low') and bk.get('ffc_temp_high'):
            print(f"   FFC 温度区间 {bk['ffc_temp_low'][0]}~{bk['ffc_temp_high'][0]} ℃")
        # 照原厂驱动的索引找表（与 customize.sh 的 pick_ffc、App 的 stockBase 同一规则）：
        # batt_para 三元组按 cell_vendor 选总表，总表 9 个一行取覆盖 25℃ 那行的 FFC 表；查不到再按名字找
        cv = cell_vendor(root)
        idx = [qc.get('batt_para', [])[i:i + 3] for i in range(0, len(qc.get('batt_para', [])) - 2, 3)]
        total = next((t[2] for t in idx if t[1] == cv), idx[0][2] if idx else None)
        picked = None
        if total:
            w = qc.get(total, [])
            for i in range(0, len(w) - 8, 9):
                row = w[i:i + 9]
                if re.match(r'-?\d+$', row[0]) and re.match(r'-?\d+$', row[1]) and \
                        int(row[0]) <= 25 < int(row[1]) and row[6] != 'null':
                    picked = row[6]
                    break
        name = next((n for n in (picked, 'ffc_para_25_35', f'ffc_para_25_35_{cv}') if n and n in qc), None)
        base = qc.get(name) if name else None
        if name:
            print(f'   电芯厂商 cell_vendor={cv}，总表 {total or "（无）"} → 用 {name}')
        if base:
            tab = [int(x) for x in base if re.match(r'-?\d+$', x)]
            print(f'   {name} = ' + ' | '.join(' '.join(map(str, tab[i:i + 3]))
                                                    for i in range(0, len(tab) - 2, 3)))
            n, m = ladder_of(tab)
            print(f"   → 安装时默认阶梯：拐点 {','.join(map(str, n))} mV，电流 {','.join(map(str, m))} mA")
        else:
            notes.append('设备树没有 ffc_para_25_35 → 充电阶梯按单档 FORCE_MA 运行（customize 不会自动设阶梯）')
    else:
        notes.append('设备树没有 mca_strategy_quick_charge → 原厂阶梯未知，阶梯页没有灰色参考线')

    zones = {}
    for line in secs.get('温度节点（thermal_zone 编号 类型 温度）', []):
        f = line.split()
        if len(f) >= 3:
            zones[f[1]] = (f[0], f[2])
    # 充电电路温度：小米 15（dada）叫 charger_therm0，守护进程同样会退而求其次
    if 'charger_therm' not in zones and 'charger_therm0' in zones:
        zones['charger_therm'] = zones['charger_therm0']
        print('   （charger_therm 不存在，用 charger_therm0）')
    for z in ('charger_therm', 'conn_therm'):
        print(f'   温度节点 {z:14s}: ' + (f'{zones[z][0]} = {zones[z][1]}' if z in zones else '✗ 没有'))
    tz_ok = 'charger_therm' in zones and 'conn_therm' in zones

    st = [line for line in secs.get('充电增强守护进程', []) if line.strip()]
    if st:
        print('   守护进程：' + ' | '.join(st)[:220])
    for fn, what in (('turbo_session.log', '本次充电采样'), ('charge_hist.log', '历史充电采样')):
        fp = os.path.join(root, fn)
        if os.path.exists(fp):
            n = sum(1 for line in open(fp, encoding='utf-8', errors='replace')
                    if line.strip() and not line.startswith('E '))
            print(f'   {what}（{fn}）：{n} 点')

    if not tr_ok and not fc_ok:
        notes.append('两个控制节点都没有 → 充电增强在本机不可用（守护进程会判为「不支持」）')
    else:
        if not tz_ok:
            notes.append('缺 charger_therm/conn_therm 温度节点 → 温度护栏无法就绪，亮屏快充和提升电流都不会生效（fail-safe）')
        if not tr_ok:
            notes.append('没有 wired_thermal_remove → 亮屏快充不可用')
        if not fc_ok:
            notes.append('没有 curr_max/force_val → 提升充电电流不可用')
    return notes


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
    kos = sorted(f for f in os.listdir(root) if f.endswith('.ko'))
    # 包里还有电量计驱动 bq27z561.ko（v1.6 起采集），按字母排它在前；要反汇编的是目标函数所在模块
    m = re.search(r'目标函数所在模块: (\S+)', txt)
    if m and m.group(1) + '.ko' in kos:
        kos.remove(m.group(1) + '.ko')
        kos.insert(0, m.group(1) + '.ko')
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

    charge_notes = charge_report(root)

    print('\n========== 结论 ==========')
    if verdict:
        for v in verdict:
            print('  ✗ ' + v)
    else:
        print('  ✓ 各项通过：可以让对方在 /data/adb/vcut_lower/config.sh 加 MODE=full 并设定关机电压')
    if charge_notes:
        print('  充电增强：')
        for v in charge_notes:
            print('   ✗ ' + v)
    else:
        print('  ✓ 充电增强：控制节点与温度节点齐全，可以开（未验证机型，建议先单开亮屏快充观察）')


if __name__ == '__main__':
    main()
