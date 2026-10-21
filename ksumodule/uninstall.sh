#!/system/bin/sh
# 卸载：确保模块被移除并还原（rmmod 里会写回 vcutoff 原值、fake_soc=-1、vpack 门控）
rmmod vcut_lower 2>/dev/null
# 充电增强守护进程：收到 TERM 会先撤回热限流与 curr_max 强制，再退出
pkill -f vcut_turbo.sh 2>/dev/null
# 伪装的循环数也还原（若曾用 sysfs 直接改过）
rm -rf /data/adb/vcut_lower
