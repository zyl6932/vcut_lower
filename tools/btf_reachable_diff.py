#!/usr/bin/env python3
"""
在两份 BTF 的 C 形式 dump 之间，找出「从 module_layout 的 5 个根类型可达」且「两边不同」的类型。
用于判断 module_layout CRC 差异究竟落在哪个类型上，以及它是否与我们模块的 ABI 有关。

用法: btf_reachable_diff.py dev_all.c our_all.c
"""
import sys, re, io
from collections import deque

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

ROOTS = ['module', 'modversion_info', 'kernel_param', 'kernel_symbol', 'tracepoint']
DEDUP = re.compile(r'___\d+')
REF = re.compile(r'\b(?:struct|union)\s+([A-Za-z_]\w*)')


def parse(path):
    """返回 {类型名: 定义文本}，并去掉 BTF 去重后缀"""
    defs = {}
    cur, name = None, None
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            l = DEDUP.sub('', line)
            m = re.match(r'^(?:struct|union)\s+([A-Za-z_]\w*)\s*\{', l)
            if m:
                name, cur = m.group(1), [l]
                continue
            if cur is not None:
                cur.append(l)
                if l.startswith('};'):
                    defs.setdefault(name, ''.join(cur))
                    cur, name = None, None
    return defs


def main():
    dev, our = parse(sys.argv[1]), parse(sys.argv[2])
    print(f"设备类型定义 {len(dev)} 个，我们 {len(our)} 个")

    # 从根类型 BFS 求可达闭包
    seen, q = set(), deque(ROOTS)
    while q:
        t = q.popleft()
        if t in seen:
            continue
        seen.add(t)
        body = dev.get(t) or our.get(t)
        if not body:
            continue
        for r in REF.findall(body):
            if r not in seen:
                q.append(r)
    print(f"从 module_layout 的 5 个根类型可达的类型闭包: {len(seen)} 个")

    both = seen & set(dev) & set(our)
    diff = [t for t in sorted(both) if dev[t] != our[t]]
    only_dev = sorted((seen & set(dev)) - set(our))
    only_our = sorted((seen & set(our)) - set(dev))

    print(f"\n★可达且定义不同的类型: {len(diff)} 个")
    for t in diff[:25]:
        dl, ol = dev[t].count('\n'), our[t].count('\n')
        print(f"  - {t}  (设备 {dl} 行 / 我们 {ol} 行)")
    if len(diff) > 25:
        print(f"  ... 另有 {len(diff) - 25} 个")

    if only_dev:
        print(f"\n只有设备侧有的可达类型 ({len(only_dev)}): {', '.join(only_dev[:15])}")
    if only_our:
        print(f"\n只有我们侧有的可达类型 ({len(only_our)}): {', '.join(only_our[:15])}")

    # 打印前若干个差异的具体内容
    for t in diff[:4]:
        print(f"\n===== struct {t} 的差异 =====")
        import difflib
        for line in list(difflib.unified_diff(
                dev[t].splitlines(), our[t].splitlines(),
                fromfile='设备', tofile='我们', lineterm=''))[:30]:
            print("  " + line)


if __name__ == '__main__':
    main()
