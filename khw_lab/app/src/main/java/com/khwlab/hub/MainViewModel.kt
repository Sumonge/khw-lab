package com.khwlab.hub

import androidx.lifecycle.ViewModel
import com.khwlab.hub.core.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Screen {
    data object Home : Screen
    data object Provision : Screen
    data class Detail(val device: Device) : Screen
    data class Settings(val device: Device) : Screen
}

/** 极简导航:一个状态 + 一个返回栈,不引入 Navigation 依赖。 */
class MainViewModel : ViewModel() {
    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val stack = ArrayDeque<Screen>()

    fun navigate(s: Screen) {
        stack.addLast(_screen.value)
        _screen.value = s
    }

    fun back() {
        _screen.value = if (stack.isNotEmpty()) stack.removeLast() else Screen.Home
    }

    /** 清空栈直接回首页(删除设备后,Detail/Settings 里的设备已失效)。 */
    fun goHome() {
        stack.clear()
        _screen.value = Screen.Home
    }

    /** 重命名成功后同步导航栈里的设备快照,避免返回/重进时仍显示旧名。 */
    fun updateDevice(id: String, newName: String) {
        val update: (Screen) -> Screen = { s ->
            when (s) {
                is Screen.Settings -> if (s.device.id == id) s.copy(device = s.device.copy(name = newName)) else s
                is Screen.Detail -> if (s.device.id == id) s.copy(device = s.device.copy(name = newName)) else s
                else -> s
            }
        }
        _screen.value = update(_screen.value)
        for (i in stack.indices) stack[i] = update(stack[i])
    }
}
