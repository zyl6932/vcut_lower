#!/usr/bin/env python3
"""
深放实测数据分析 —— 标定 方案C 的三个参数
  QBOT   : 底部段(raw_soc=0 到关机)的总容量，对电流积分得到
  R_INT  : 内阻，从负载突变点的 ΔV/ΔI 取中位数（★必须用底部段的样本，低SOC下R会涨）
  f(V)   : 归一化剩余量 vs 开路电压，用来替换线性近似

用法: analyze_discharge.py <discharge_test.csv>
"""
import sys, io, csv, statistics

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')


def num(x, d=None):
    try:
        return int(x)
    except (TypeError, ValueError):
        return d


def main():
    path = sys.argv[1]
    rows, bottom_mark = [], None
    with open(path, newline='', encoding='utf-8', errors='replace') as f:
        for r in csv.DictReader(f):
            if r.get('seg') == 'BOTTOM_START':
                bottom_mark = num(r.get('uptime_s'))
                continue
            up, v, i = num(r.get('uptime_s')), num(r.get('V_mV')), num(r.get('I_uA'))
            if up is None or v is None or i is None:
                continue
            rows.append({
                'up': up, 'v': v, 'i': i,
                'raw': num(r.get('raw_soc'), -1), 'rm': num(r.get('rm_uAh'), 0),
                'temp': num(r.get('temp'), 0), 'cap': num(r.get('capacity'), -1),
                'disp': num(r.get('t_disp'), -1), 'mean': num(r.get('t_mean_volt'), v),
                'fs': num(r.get('t_failsafe'), 0), 'seg': r.get('seg', ''),
            })
    if not rows:
        print("无有效数据")
        return 1

    print(f"样本 {len(rows)} 条   时长 {(rows[-1]['up'] - rows[0]['up']) / 60:.1f} 分钟")
    print(f"电压 {rows[0]['v']} -> {rows[-1]['v']} mV     温度 {rows[0]['temp']/10:.1f} -> {rows[-1]['temp']/10:.1f} ℃")

    top = [r for r in rows if r['seg'] == 'top']
    bot = [r for r in rows if r['seg'] == 'bottom']
    print(f"上段 {len(top)} 条   底部段 {len(bot)} 条")

    # ---------- QBOT：底部段电流积分 ----------
    if len(bot) < 2:
        print("\n★底部段样本不足，无法标定 QBOT")
    else:
        mah = 0.0
        for a, b in zip(bot, bot[1:]):
            dt = b['up'] - a['up']
            if dt <= 0 or dt > 60:      # 跳过异常间隔
                continue
            iavg = (abs(a['i']) + abs(b['i'])) / 2.0    # µA
            mah += iavg / 1000.0 * dt / 3600.0          # mA·h
        print(f"\n=== QBOT（底部段实测容量）===")
        print(f"  ★QBOT = {mah:.1f} mAh      （当前配置用的是 150）")
        print(f"  底部段: {bot[0]['v']} -> {bot[-1]['v']} mV，历时 {(bot[-1]['up']-bot[0]['up'])/60:.1f} 分钟")
        print(f"  平均电流 {sum(abs(r['i']) for r in bot)/len(bot)/1000:.0f} mA")

    # ---------- R_INT：负载突变点的 ΔV/ΔI ----------
    def rint_from(seq, label):
        vals = []
        for a, b in zip(seq, seq[1:]):
            di = b['i'] - a['i']          # µA
            dv = b['v'] - a['v']          # mV
            if abs(di) < 300000:          # 至少 300mA 的跳变才算
                continue
            r = -dv * 1e6 / di            # mΩ  (V 降 -> I 增)
            if 1 < r < 200:
                vals.append(r)
        if vals:
            print(f"  {label}: 中位数 {statistics.median(vals):.1f} mΩ  "
                  f"(n={len(vals)}, 范围 {min(vals):.0f}~{max(vals):.0f})")
        else:
            print(f"  {label}: 无足够的负载突变样本")

    print(f"\n=== R_INT（内阻）===")
    rint_from(top, "上段")
    rint_from(bot, "★底部段（应以此为准，低SOC下内阻更大）")
    print(f"  （当前配置用的是 20 mΩ，52%SOC 时实测 19.3）")

    # ---------- f(V)：归一化剩余 vs 开路电压 ----------
    if len(bot) >= 4:
        total = 0.0
        segs = []
        for a, b in zip(bot, bot[1:]):
            dt = b['up'] - a['up']
            if dt <= 0 or dt > 60:
                continue
            total += (abs(a['i']) + abs(b['i'])) / 2.0 / 1000.0 * dt / 3600.0
            segs.append((b, total))
        if total > 0:
            print(f"\n=== f(V) 曲线（底部段，剩余比例 vs 开路电压）===")
            print(f"  {'Vload':>6} {'Vocv':>6} {'剩余%':>7} {'线性近似%':>10} {'偏差':>7}")
            step = max(1, len(segs) // 12)
            for b, used in segs[::step]:
                frac = 1.0 - used / total
                vocv = b['v'] + abs(b['i']) / 1000.0 * 20 / 1000.0   # 用 20mΩ 粗补
                lin = max(0.0, min(1.0, (vocv - 2700) / 330.0))
                print(f"  {b['v']:>6} {vocv:>6.0f} {frac*100:>6.1f}% {lin*100:>9.1f}% "
                      f"{(frac-lin)*100:>+6.1f}")
            print("  → 若「剩余%」系统性高于「线性近似%」，说明线性 f 低估了底部余量，"
                  "应换成实测查表")

    # ---------- 关机点 ----------
    print(f"\n=== 关机点 ===")
    last = rows[-1]
    print(f"  最后一条: V={last['v']}mV mean_volt={last['mean']}mV "
          f"capacity={last['cap']} t_disp={last['disp']} failsafe={last['fs']}")
    fs = [r for r in rows if r['fs'] not in (0, None)]
    if fs:
        print(f"  ★fail-safe 曾触发: 首次于 V={fs[0]['v']}mV (t_failsafe={fs[0]['fs']})")
    else:
        print(f"  fail-safe 全程未触发")
    # 显示值走势
    print(f"\n=== 显示值走势（capacity）===")
    seen, out = None, []
    for r in rows:
        if r['cap'] != seen:
            out.append(f"{r['cap']}@{r['v']}mV")
            seen = r['cap']
    print("  " + " -> ".join(out))
    return 0


if __name__ == '__main__':
    sys.exit(main())
