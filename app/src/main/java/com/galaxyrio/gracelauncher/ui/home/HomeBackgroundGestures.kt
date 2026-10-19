package com.galaxyrio.gracelauncher.ui.home

import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

@Composable
internal fun Modifier.homeBackgroundGestures(
    enabled: Boolean,
    doubleTapSlop: Float,
    onSearch: () -> Unit,
    onLockScreen: () -> Boolean,
    onLockUnavailable: () -> Unit,
): Modifier {
    val latestEnabled = rememberUpdatedState(enabled)
    val latestSearch = rememberUpdatedState(onSearch)
    val latestLockScreen = rememberUpdatedState(onLockScreen)
    val latestLockUnavailable = rememberUpdatedState(onLockUnavailable)
    return pointerInput(doubleTapSlop) {
        var previousTap: BackgroundTap? = null
        val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (!latestEnabled.value || down.isConsumed) {
                previousTap = null
                return@awaitEachGesture
            }
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (event.changes.size != 1 || change == null || !latestEnabled.value || down.isConsumed || change.isConsumed) {
                    previousTap = null
                    break
                }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                    previousTap = null
                }
                if (!change.pressed) {
                    if (!moved) {
                        if (change.uptimeMillis - down.uptimeMillis >= longPressTimeout) {
                            previousTap = null
                        } else {
                            val prior = previousTap
                            val elapsed = change.uptimeMillis - (prior?.timeMillis ?: 0L)
                            if (prior != null && elapsed in 0..doubleTapTimeout &&
                                (change.position - prior.position).getDistance() <= doubleTapSlop
                            ) {
                                previousTap = null
                                if (!runCatching { latestLockScreen.value() }.getOrDefault(false)) latestLockUnavailable.value()
                            } else {
                                previousTap = BackgroundTap(change.position, change.uptimeMillis)
                            }
                        }
                    }
                    break
                }
            }
        }
    }.pointerInput(doubleTapSlop) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!latestEnabled.value || down.isConsumed) return@awaitEachGesture
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (event.changes.size != 1 || change == null || !latestEnabled.value || down.isConsumed || change.isConsumed) break
                val movement = change.position - down.position
                if (movement.y < -viewConfiguration.touchSlop && abs(movement.y) >= abs(movement.x)) {
                    change.consume()
                    latestSearch.value()
                    return@awaitEachGesture
                }
                if (!change.pressed) break
            }
        }
    }
}

private data class BackgroundTap(val position: Offset, val timeMillis: Long)
