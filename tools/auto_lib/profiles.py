#!/usr/bin/env python3
"""
偏移档案库读写（static profile 层）。

设计要点：
  - 索引键是 **vendor 模块二进制的 md5**，不是系统版本号。
    理由见 offset_profiles.json 的 _readme（内核串不变而 vendor 模块换 build 的实测案例）。
  - 命中 -> 偏移已知，可直接跳过解析。
  - 未命中 -> 返回"最新已知 profile"作为基线，供 resolver 算位移量 delta。
"""
import json
import os

DEFAULT_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            'offset_profiles.json')


def h2i(v):
    """'0x9fc' / 0x9fc / '9fc' -> int"""
    if isinstance(v, int):
        return v
    return int(str(v), 16)


def i2h(v):
    return f"0x{v:x}"


class ProfileDB:
    def __init__(self, path=None):
        self.path = path or DEFAULT_PATH
        with open(self.path, 'r', encoding='utf-8') as f:
            self.db = json.load(f)
        self.profiles = self.db.get('profiles', [])

    def save(self, path=None):
        p = path or self.path
        with open(p, 'w', encoding='utf-8') as f:
            json.dump(self.db, f, ensure_ascii=False, indent=2)
            f.write('\n')

    def find_by_md5(self, md5):
        if not md5:
            return None
        for pr in self.profiles:
            if pr.get('vendor_ko_md5') == md5:
                return pr
        return None

    def find_by_build(self, build_fp, kernel):
        """按 build 指纹 + 内核串匹配（md5 缺失时的兜底）。"""
        for pr in self.profiles:
            if pr.get('build_fingerprint') == build_fp and pr.get('kernel') == kernel:
                return pr
        return None

    def latest(self):
        """返回最新（数组末尾）的已验证 profile，作为解析基线。"""
        return self.profiles[-1] if self.profiles else None

    @staticmethod
    def offsets_int(pr):
        return {k: h2i(v) for k, v in pr['offsets'].items()}

    @staticmethod
    def fingerprints_int(pr):
        return [(h2i(f['off']), h2i(f['val']), f.get('desc', ''))
                for f in pr['fingerprints']]

    def add(self, profile):
        """新增或按 id 覆盖已有条目。"""
        pid = profile.get('id')
        for i, pr in enumerate(self.profiles):
            if pr.get('id') == pid:
                self.profiles[i] = profile
                return
        self.profiles.append(profile)
