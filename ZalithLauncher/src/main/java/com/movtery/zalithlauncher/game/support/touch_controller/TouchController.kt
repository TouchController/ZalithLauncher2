package com.movtery.zalithlauncher.game.support.touch_controller

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastForEach
import com.movtery.zalithlauncher.game.support.touch_controller.ControllerProxy.proxyClient
import org.lwjgl.glfw.CallbackBridge

/**
 * 单独捕获触摸事件，为TouchController模组的控制代理提供信息
 */
@Composable
fun Modifier.touchControllerModifier() = this.pointerInput(Unit) {
    awaitPointerEventScope {
        val activePointers = mutableMapOf<PointerId, Int>()
        var nextPointerId = 1

        fun PointerInputChange.toProxyOffset(): Pair<Float, Float> {
            val normalizedX = position.x / CallbackBridge.physicalWidth
            val normalizedY = position.y / CallbackBridge.physicalHeight
            return Pair(normalizedX, normalizedY)
        }

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.fastForEach { change ->
                if (change.changedToDownIgnoreConsumed()) {
                    if (!activePointers.containsKey(change.id)) {
                        val pointerId = nextPointerId++
                        activePointers[change.id] = pointerId
                        val (x, y) = change.toProxyOffset()
                        proxyClient?.addPointer(pointerId, x, y)
                    }
                } else if (change.changedToUpIgnoreConsumed()) {
                    activePointers.remove(change.id)?.let { pointerId ->
                        proxyClient?.removePointer(pointerId)
                    }
                } else if (change.pressed && event.type == PointerEventType.Move) {
                    activePointers[change.id]?.let { pointerId ->
                        val (x, y) = change.toProxyOffset()
                        proxyClient?.addPointer(pointerId, x, y)
                    }
                }
            }
        }
    }
}
