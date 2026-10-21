package com.github.zyl6932.vcutlower.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.github.zyl6932.vcutlower.root.Vcut
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 页面在前台时的状态刷新间隔 */
private const val POLL_MS = 3000L

/** 模块状态 + 是否已经问到过一次 + 立即刷新（操作完成后调用） */
class StatusHolder(val status: Vcut.Status, val checked: Boolean, val refresh: () -> Unit)

/**
 * 主界面和各二级页面共用的状态轮询：只在界面可见时每 3 秒问一次，切到后台就停，不在后台耗电。
 */
@Composable
fun rememberVcutStatus(): StatusHolder {
    var status by remember { mutableStateOf(Vcut.Status()) }
    var checked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                status = Vcut.status()
                checked = true
                delay(POLL_MS)
            }
        }
    }
    return StatusHolder(status, checked) {
        scope.launch {
            status = Vcut.status()
            checked = true
        }
    }
}
