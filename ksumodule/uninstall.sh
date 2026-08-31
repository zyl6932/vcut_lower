#!/system/bin/sh
# 卸载：确保模块被移除并还原（rmmod 里会写回 vcutoff 原值、fake_soc=-1、vpack 门控）
rmmod vcut_lower 2>/dev/null
# 伪装的循环数也还原（若曾用 sysfs 直接改过）
rm -rf /data/adb/vcut_lower
