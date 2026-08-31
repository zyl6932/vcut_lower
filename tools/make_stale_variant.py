#!/usr/bin/env python3
"""
生成一个"基线已过时"的源码变体，用于验证自愈能力。

★为什么需要它：真机上 vendor 模块没变，delta 恒为 0，只能证明"能复述已知答案"。
  把编译期基线整体平移 +0x10，就等于人为制造了"系统更新后基线过时"的状态 ——
  此时若自愈有效，解析应得出 delta=-0x10 并把所有偏移拉回真值。

  ★注意 gate：0xaa1 + 0x10 = 0xab1，这是"位移结果"；真实值是 0xaa1。
    这正是历史上那个坑（位移结果落在高频标志上），锚点必须把它拉回来。
"""
import re
import sys

src = sys.argv[1]
dst = sys.argv[2]
delta = int(sys.argv[3], 16) if len(sys.argv) > 3 else 0x10

# ★必须捕获 ';' 之后的内容：有些定义行尾带注释，甚至跨行
#   （OFF_VPACK_LOW_EN 就是：行尾 /* ... 注释延续到下一行 */）
pat = re.compile(r'^(static (?:const )?int (?:BASE|OFF)_[A-Z_]+\s*=\s*)0x([0-9a-f]+);(.*)$')
out = []
hit = []
for line in open(src, encoding='utf-8'):
    m = pat.match(line)
    if m:
        old = int(m.group(2), 16)
        new = old + delta
        if new < 0:
            raise SystemExit(f"负偏移: {line.strip()}")
        out.append(f"{m.group(1)}0x{new:x};{m.group(3)}\n")
        hit.append((m.group(1).strip(), hex(old), hex(new)))
    else:
        out.append(line)

open(dst, 'w', encoding='utf-8').write(''.join(out))
print(f"已生成 {dst}：{len(hit)} 个基线常量 {delta:+d}(0x{delta:x})")
for name, o, n in hit[:20]:
    print(f"  {name:40s} {o:>7s} -> {n:>7s}")
