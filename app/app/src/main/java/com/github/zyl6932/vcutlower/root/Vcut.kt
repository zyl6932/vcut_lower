package com.github.zyl6932.vcutlower.root

/**
 * 与 vcut_lower 内核模块打交道的全部路径与操作。
 *
 * 模块本身不为 App 做任何改动：状态读 /sys/module 参数和 boot.log，设置写回
 * /data/adb/vcut_lower/config.sh（开机由 service.sh 读取）。
 *
 * ★开关与实际功能必须一致（2026-10-21）：界面显示的是【正在运行的实际状态】（模块参数、电量计），
 *   不是 config.sh。每项改动要么当场生效，要么当场重新加载/重启，取消就什么都不写。
 */
object Vcut {
    const val MOD_DIR = "/data/adb/modules/vcut_lower"
    const val DATA = "/data/adb/vcut_lower"
    const val CFG = "$DATA/config.sh"
    const val PARAMS = "/sys/module/vcut_lower/parameters"
    const val BAT = "/sys/class/power_supply/battery"
    const val FGM = "/sys/devices/virtual/xm_power/fg_master"
    const val SFG = "/sys/devices/platform/soc/soc:strategy-fg"
    const val CHGIF = "/sys/devices/platform/soc/soc:mca_charge_interface"
    /** service.sh 运行期间持有的锁（见 service.sh「同一时间只跑一个加载流程」） */
    const val LOCK = "/dev/vcut_lower.lock"
    /** 充电增强守护进程（vcut_turbo.sh）每轮写的实时状态 */
    const val TURBO_STATE = "/dev/vcut_turbo.state"

    /** 模块内部硬下限（VCUT_FLOOR），滑块不允许低于它 */
    const val FLOOR = 2500
    const val CEIL = 3400

    enum class Health { NO_ROOT, NOT_INSTALLED, DISABLED, RELOADING, NOT_LOADED, WAITING, INVALID, PROBE, RUNNING }

    data class Status(
        val root: Boolean = false,
        val installed: Boolean = false,
        val disabled: Boolean = false,
        val loaded: Boolean = false,
        val p: Map<String, String> = emptyMap(),   // 模块参数
        val bat: Map<String, String> = emptyMap(),  // battery sysfs
        val fg: Map<String, String> = emptyMap(),   // fg_master：cyclecount / qmax_cyclecount / charger_full
        val reloading: Boolean = false,             // service.sh 正在跑（重新加载中）
        val turbo: Map<String, String> = emptyMap(),  // vcut_turbo.state：run A B online ibat btemp ctherm conn why
        val turboAlive: Boolean = false,              // 守护进程确实在跑（状态文件可能是被 kill -9 前留下的）
        val cfg: Map<String, String> = emptyMap(),  // config.sh
        val modeFile: String? = null,               // mode.sh 里的 MODE
        val version: String = "",
        val kernel: String = "",
        val lastLog: List<String> = emptyList(),
    ) {
        fun pi(k: String) = p[k]?.trim()?.toIntOrNull()
        fun bi(k: String) = bat[k]?.trim()?.toIntOrNull()
        fun fi(k: String) = fg[k]?.trim()?.toIntOrNull()

        /** 与 service.sh 同一套判定：config.sh 的 MODE 优先，其次 mode.sh，默认 full */
        val mode: String get() = cfg["MODE"] ?: modeFile ?: "full"

        val health: Health
            get() = when {
                !root -> Health.NO_ROOT
                !installed -> Health.NOT_INSTALLED
                disabled -> Health.DISABLED
                reloading -> Health.RELOADING
                !loaded -> Health.NOT_LOADED
                pi("armed") != 1 && (pi("t_hits") ?: 0) == 0 -> Health.WAITING
                pi("armed") != 1 -> Health.INVALID
                pi("enable") == 0 -> Health.PROBE
                else -> Health.RUNNING
            }

        /** 显示电量：模块接管时用它算出的值，否则系统值 */
        val displaySoc: Int? get() = pi("t_disp")?.takeIf { it in 0..100 } ?: bi("capacity")
        val meanMv: Int? get() = pi("t_mean_volt")?.takeIf { it > 0 } ?: bi("voltage_now")?.div(1000)
        /** mA，正 = 放电（本机 current_now 与模块同号） */
        val currentMa: Int? get() = (pi("t_current") ?: bi("current_now"))?.div(1000)
        val tempC: Float? get() = (pi("t_temp")?.takeIf { it > -400 } ?: bi("temp"))?.div(10f)
    }

    private val STATUS_SCRIPT = """
        echo "root=1"
        [ -d $MOD_DIR ] && echo "installed=1"
        [ -f $MOD_DIR/disable ] && echo "disabled=1"
        if [ -d $PARAMS ]; then
          echo "loaded=1"
          for f in $PARAMS/*; do echo "p.${'$'}{f##*/}=${'$'}(cat ${'$'}f 2>/dev/null)"; done
        fi
        for n in capacity voltage_now current_now temp status; do echo "bat.${'$'}n=${'$'}(cat $BAT/${'$'}n 2>/dev/null)"; done
        grep '^version=' $MOD_DIR/module.prop 2>/dev/null
        sed -n 's/^MODE=\([a-z]*\).*/mode=\1/p' $DATA/mode.sh 2>/dev/null
        sed -n 's/^\([A-Z_]*\)=\([^ #]*\).*/cfg.\1=\2/p' $CFG 2>/dev/null
        echo "kernel=${'$'}(uname -r)"
        for n in cyclecount qmax_cyclecount charger_full; do echo "fg.${'$'}n=${'$'}(cat $FGM/${'$'}n 2>/dev/null)"; done
        [ -d $LOCK ] && echo "reloading=1"
        [ -f $TURBO_STATE ] && sed 's/^/tc=/' $TURBO_STATE
        pgrep -f vcut_turbo.sh >/dev/null && echo "tc_alive=1"
        tail -4 $DATA/boot.log 2>/dev/null | sed 's/^/log=/'
    """.trimIndent()

    suspend fun status(): Status {
        val r = RootShell.run(STATUS_SCRIPT) ?: return Status(root = false)
        val p = HashMap<String, String>()
        val bat = HashMap<String, String>()
        val fg = HashMap<String, String>()
        val cfg = HashMap<String, String>()
        val log = ArrayList<String>()
        var s = Status(root = true)
        for (line in r.out.lines()) {
            val k = line.substringBefore('=', "")
            val v = line.substringAfter('=', "")
            when {
                k == "installed" -> s = s.copy(installed = true)
                k == "disabled" -> s = s.copy(disabled = true)
                k == "loaded" -> s = s.copy(loaded = true)
                k.startsWith("p.") -> p[k.removePrefix("p.")] = v
                k.startsWith("bat.") -> bat[k.removePrefix("bat.")] = v
                k.startsWith("fg.") -> fg[k.removePrefix("fg.")] = v
                k == "reloading" -> s = s.copy(reloading = true)
                k == "tc" -> s = s.copy(turbo = parseTurbo(v))
                k == "tc_alive" -> s = s.copy(turboAlive = true)
                k.startsWith("cfg.") -> cfg[k.removePrefix("cfg.")] = v
                k == "mode" -> s = s.copy(modeFile = v)
                k == "version" -> s = s.copy(version = v)
                k == "kernel" -> s = s.copy(kernel = v)
                k == "log" -> log += v
            }
        }
        return s.copy(p = p, bat = bat, fg = fg, cfg = cfg, lastLog = log)
    }

    // ---------------- 设置：实际状态 ----------------

    /**
     * 界面显示用的【实际状态】。模块在跑时全部取自运行中的模块与电量计；模块没加载、正在
     * 重新加载或没通过校验时只能退回 config.sh，此时开关整体不可操作（[editable] = false）。
     */
    data class Actual(
        val editable: Boolean,
        val full: Boolean,              // 正常模式（模块 enable=1）
        val delay: Int,                 // 当前生效的电压闸
        val socCtl: Boolean,
        val fakeCycle: Boolean,         // 伪装循环【正在生效】：参数开 且 循环偏移已坐实
        val fakeCyclePaused: Boolean,   // 参数开了但偏移没坐实，模块不写——如实显示为暂停
        val fakeFg: Boolean,            // 电量计循环数当前是伪装值
    )

    fun actualOf(s: Status): Actual {
        fun cfg(k: String, d: Int) = s.cfg[k]?.toIntOrNull() ?: d
        val running = s.loaded && !s.reloading && s.pi("armed") == 1
        val fakeParam = (s.pi("fake_cycle") ?: -1) >= 0
        val fgCyc = s.fi("cyclecount")
        val qmax = s.fi("qmax_cyclecount")
        return Actual(
            editable = s.root && running,
            full = if (s.loaded) s.pi("enable") == 1 else s.mode == "full",
            delay = (if (s.loaded) s.pi("vcut_delay") else null) ?: cfg("VCUT_DELAY", 2620),
            socCtl = if (s.loaded) s.pi("soc_ctl") == 1 else cfg("SOC_CTL", 1) == 1,
            fakeCycle = s.loaded && fakeParam && s.pi("t_cyc_ok") == 1,
            fakeCyclePaused = s.loaded && fakeParam && s.pi("t_cyc_ok") != 1,
            // 伪装值是 1；真实循环数 >5 才判得出（新电池循环 ≤5 时两者无法区分，按未伪装算）
            fakeFg = fgCyc != null && qmax != null && qmax > 5 && fgCyc <= 5,
        )
    }

    // ---------------- 充电增强 ----------------

    /** 状态行形如 "run=on A=1 B=0 online=1 ... why=电芯 470 超限"；why 在最后、可含空格 */
    private fun parseTurbo(line: String): Map<String, String> {
        val m = HashMap<String, String>()
        val head = line.substringBefore(" why=", line)
        head.split(' ').forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) m[kv.substring(0, i)] = kv.substring(i + 1)
        }
        if (" why=" in line) m["why"] = line.substringAfter(" why=").trim()
        else if (line.startsWith("why=")) m["why"] = line.removePrefix("why=").trim()
        return m
    }

    /**
     * 充电增强的【实际状态】。开关亮 = 配置开 且 守护进程真在跑；本机没有对应节点时整体不可用。
     * 「正在生效」与「开关打开」是两回事：开关开着、但没在充电或护栏超限时，守护进程会撤回，
     * 这由 [activeA]/[activeB] 和 [why] 如实反映。
     */
    data class Turbo(
        val supported: Boolean,
        val thermalRemove: Boolean,   // A 开关
        val force: Boolean,           // B 开关
        val activeA: Boolean,         // A 此刻正在生效
        val activeB: Boolean,         // B 此刻正在生效
        val charging: Boolean,
        val why: String,              // 没生效的原因（未充电 / 温度超限 / 电量达上限）
    )

    fun turboOf(s: Status): Turbo {
        val t = s.turbo
        val alive = s.turboAlive && t["run"] == "on"
        return Turbo(
            supported = t["run"] != "unsupported",
            thermalRemove = alive && s.cfg["ENABLE_THERMAL_REMOVE"] == "1",
            force = alive && s.cfg["ENABLE_FORCE"] == "1",
            activeA = alive && t["A"] == "1",
            activeB = alive && t["B"] == "1",
            charging = t["online"] == "1",
            why = t["why"].orEmpty(),
        )
    }

    /**
     * 打开 / 关闭充电增强的一项：写配置后立即重启守护进程，按新配置重新判定——即时生效，
     * 开关与实际一致。旧进程收到 TERM 会先撤回 A/B（trap all_off）；两项都关时新进程写完状态就退出。
     */
    suspend fun setTurbo(key: String, on: Boolean): Boolean =
        setConfig(mapOf(key to if (on) "1" else "0")) && restartTurbo()

    suspend fun restartTurbo(): Boolean =
        RootShell.run(
            "pkill -f vcut_turbo.sh; sleep 1; " + MOD_SH +
                "nohup ${'$'}RUN $MOD_DIR/vcut_turbo.sh >/dev/null 2>&1 &"
        )?.ok == true

    // ---------------- 设置：写入 ----------------

    /**
     * 只改 config.sh 里给定的几项（逐行替换，保留行尾注释；没有的追加在末尾）。
     * 不再整份写回：整份写会把别处刚改的值用旧值覆盖（曾把 MODE 写回 probe）。
     */
    suspend fun setConfig(values: Map<String, String>): Boolean {
        val old = RootShell.run("cat $CFG 2>/dev/null")?.out ?: return false
        val seen = HashSet<String>()
        val lines = old.lines().map { line ->
            val m = Regex("""^([A-Z_]+)=(\S*)(.*)$""").find(line)
            val key = m?.groupValues?.get(1)
            if (key != null && key in values && key !in seen) {
                seen += key
                "$key=${values[key]}${m.groupValues[3]}"
            } else line
        }.toMutableList()
        val missing = values.keys - seen
        if (missing.isNotEmpty()) {
            if (old.lines().none { it.startsWith("# ---- 由 VCut Lower App 写入") }) {
                lines += ""
                lines += "# ---- 由 VCut Lower App 写入 ----"
            }
            missing.forEach { lines += "$it=${values[it]}" }
        }
        return RootShell.writeFile(CFG, lines.joinToString("\n").trimEnd() + "\n")
    }

    /**
     * 关机电压：即时生效。fw 跟闸走、判空阈值比闸低 100 mV、守住硬下限；
     * 3000mV 硬闸跟着闸走——闸 < 3000 必须摘（否则硬闸先关机），≥ 3000 时它轮不到触发。
     * 模块每次命中时 kill_vpack_gate=1 就把闸位清 0；改回 0 时闸位要到重新加载才恢复，但那时
     * 电压闸已 ≥ 3000、先于硬闸触发，不影响实际关机点。
     */
    suspend fun setVoltage(d: Int): Boolean {
        val delay = d.coerceIn(FLOOR, CEIL)
        val sw = (delay - 100).coerceAtLeast(FLOOR)
        val gate = if (delay < 3000) 1 else 0
        val live = RootShell.run(
            "echo $delay > $PARAMS/vcut_fw && echo $delay > $PARAMS/vcut_delay && " +
                "echo $sw > $PARAMS/vcut_sw && echo $gate > $PARAMS/kill_vpack_gate"
        )?.ok == true
        return live && setConfig(
            mapOf("VCUT_FW" to "$delay", "VCUT_DELAY" to "$delay", "VCUT_SW" to "$sw", "KILL_VPACK_GATE" to "$gate")
        )
    }

    /**
     * 接管电量显示：开关都即时生效。关闭时模块不再写 fake_soc，但它会停在最后写的值上——
     * 百分比卡住、还挡着内核的关机 uevent——所以同时写回 -1 交还系统（隔 1 秒再写一次，
     * 防止关闭瞬间恰好有一次命中正在写）。
     */
    suspend fun setSocCtl(on: Boolean): Boolean {
        val live = if (on) {
            RootShell.run("echo 1 > $PARAMS/soc_ctl")?.ok == true
        } else {
            RootShell.run(
                "echo 0 > $PARAMS/soc_ctl && echo -1 > $SFG/fake_soc && sleep 1 && echo -1 > $SFG/fake_soc"
            )?.ok == true
        }
        return live && setConfig(mapOf("SOC_CTL" to if (on) "1" else "0"))
    }

    /** 伪装循环次数·打开：即时生效（偏移已坐实时模块下一次命中就写；没坐实则显示为暂停）。 */
    suspend fun enableFakeCycle(): Boolean =
        RootShell.run("echo 1 > $PARAMS/fake_cycle")?.ok == true && setConfig(mapOf("FAKE_CYCLE" to "1"))

    /**
     * 抬高满充电压·打开：即时生效。与 service.sh 同一段已验证的流程——写电量计 fake 循环数，
     * 再断充 5 秒触发 jeita 重新投票（投票是粘的，不触发就要等下一次插拔）。约 11 秒。
     */
    suspend fun enableFakeFg(): Boolean {
        val r = RootShell.runDetached(
            "[ -w $FGM/cyclecount ] || exit 1; echo 1 > $FGM/cyclecount; " +
                "if [ -w $CHGIF/input_suspend ]; then echo \"micharge buck 1\" > $CHGIF/input_suspend; " +
                "sleep 5; echo \"micharge buck 0\" > $CHGIF/input_suspend; sleep 6; fi"
        )
        return r?.ok == true && setConfig(mapOf("FAKE_FG_CYCLE" to "1"))
    }

    /** 需要重新加载才能生效的改动：先写配置，再按开机流程重新加载。 */
    suspend fun applyWithReload(values: Map<String, String>): Boolean = setConfig(values) && reload()

    /** 需要重启才能生效的改动（电量计的伪装循环数只有重启能清掉）：先写配置，再重启。 */
    suspend fun applyWithReboot(values: Map<String, String>): Boolean =
        setConfig(values) && RootShell.run("svc power reboot || reboot")?.ok == true

    // ---------------- 操作 ----------------

    /**
     * 用 root 管理器自带的 busybox（ASH_STANDALONE=1）跑模块脚本——与开机时 KernelSU 执行
     * service.sh 的环境一致。直接用 sh 会落到系统的 mksh，autooff.sh 在 mksh 下会解析失败
     * （2026-10-21 从 App 重新加载时 t_ovr=0 就是这个原因）。
     */
    private val MOD_SH =
        "BB=; for b in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; " +
            "do [ -x ${'$'}b ] && { BB=${'$'}b; break; }; done; " +
            "if [ -n \"${'$'}BB\" ]; then RUN=\"env ASH_STANDALONE=1 ${'$'}BB sh\"; else RUN=sh; fi; "

    /** 按开机流程重新加载（service.sh：autooff → insmod → 校验），后台执行约 30~90 秒 */
    suspend fun reload(): Boolean =
        RootShell.run("rm -f $MOD_DIR/disable; echo 0 > $DATA/bootfail; " +
            MOD_SH + "nohup ${'$'}RUN $MOD_DIR/service.sh >/dev/null 2>&1 &")?.ok == true

    /** 卸载模块：rmmod 时模块自己写回原厂 vcutoff、fake_soc=-1 */
    suspend fun unload(): Boolean = RootShell.run("rmmod vcut_lower")?.ok == true

    /** boot.log + 内核日志里 vcut 相关的行 */
    suspend fun logs(): String {
        val r = RootShell.run(
            "echo '===== boot.log ====='; tail -120 $DATA/boot.log 2>/dev/null; " +
                "echo; echo '===== dmesg (vcut_lower) ====='; dmesg 2>/dev/null | grep vcut_lower | tail -60"
        )
        return r?.out ?: "（未获得 root 权限）"
    }

    /**
     * 生成诊断包；返回 /sdcard/Download 下的路径。collect.sh 最后一行形如
     * "完成：/sdcard/Download/vcut_diag_xxx.tar.gz"。
     */
    suspend fun collect(onLine: (String) -> Unit): String? {
        val r = RootShell.runDetached(MOD_SH + "${'$'}RUN $MOD_DIR/collect.sh", onLine) ?: return null
        return Regex("""(/sdcard/Download/vcut_diag_\S+\.tar\.gz)""").find(r.out)?.groupValues?.get(1)
    }

    /** 把诊断包复制进 App 的 cache/share/，交给 FileProvider 分享 */
    suspend fun stageForShare(src: String, cacheDir: String, uid: Int): String? {
        val dst = "$cacheDir/share/${src.substringAfterLast('/')}"
        val ok = RootShell.run(
            "mkdir -p '$cacheDir/share' && cp '$src' '$dst' && chown $uid:$uid '$cacheDir/share' '$dst' && chmod 644 '$dst'"
        )?.ok == true
        return if (ok) dst else null
    }
}
