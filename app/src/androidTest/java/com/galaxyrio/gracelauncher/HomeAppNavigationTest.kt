package com.galaxyrio.gracelauncher

import android.content.ComponentName
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.data.notifications.appNotificationBucketKey
import com.galaxyrio.gracelauncher.ui.LauncherScreen
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.home.HomeAppNavigation
import com.galaxyrio.gracelauncher.ui.theme.GraceLauncherTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic apps/notifications only; no provider reads, external actions or preference writes. */
class HomeAppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val navigation = HomeAppNavigation()
    private val apps = (0..17).map { LauncherApp(ComponentName("example.app$it", "example.app$it.Main"), "App $it", null) }
    private var shownApps by mutableStateOf(apps)
    private val launched = mutableListOf<String>()
    private val notification = AppNotification("synthetic", apps[0].packageName, "Preview", "Synthetic body", 1L, 1, true, true)

    private fun show(withNotification: Boolean = false) {
        compose.setContent { GraceLauncherTheme(dynamicColor = false) {
            LauncherScreen(
                LauncherUiState(apps = shownApps, favoriteKeys = shownApps.mapTo(linkedSetOf()) { it.key },
                    favoriteOrder = shownApps.map { it.key }, isLoadingApps = false,
                    notifications = if (withNotification) mapOf(appNotificationBucketKey(notification.packageName, null) to listOf(notification)) else emptyMap()),
                onDateClick = {}, onClockClick = {}, onToggleFavorite = {},
                onLaunchApp = { launched += it.key }, homeAppNavigation = navigation,
            )
        } }
        compose.waitUntil { navigation.move != null }
    }

    private fun key(code: Int, meta: Int = 0, editor: Boolean = false): Boolean {
        var handled = false
        compose.runOnUiThread {
            val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)
            handled = navigation.key(event, editor)
            if (handled) assertTrue(navigation.key(KeyEvent.changeAction(event, KeyEvent.ACTION_UP), editor))
        }
        compose.waitForIdle()
        return handled
    }

    private fun enter(code: Int = KeyEvent.KEYCODE_ENTER) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun scroll(source: Int, amount: Float, axis: Int = MotionEvent.AXIS_VSCROLL): Boolean {
        var handled = false
        compose.runOnUiThread {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val coordinates = MotionEvent.PointerCoords().apply { setAxisValue(axis, amount) }
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, source, 0)
            try { handled = navigation.motion(event, false) } finally { event.recycle() }
        }
        compose.waitForIdle()
        return handled
    }

    @Test fun dpadTabAndNumpadEnterUseSelectedRowAndReachOffscreenApps() {
        show()
        assertTrue(key(KeyEvent.KEYCODE_DPAD_DOWN))
        compose.onNodeWithTag("app:${apps[0].key}").assertIsSelected().assertIsFocused()
        assertTrue(key(KeyEvent.KEYCODE_TAB))
        compose.onNodeWithTag("app:${apps[1].key}").assertIsSelected().assertIsFocused()
        assertTrue(key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON))
        enter(KeyEvent.KEYCODE_NUMPAD_ENTER)
        compose.runOnIdle { assertEquals(listOf(apps[0].key), launched) }
        repeat(17) { assertTrue(key(KeyEvent.KEYCODE_DPAD_DOWN)) }
        compose.onNodeWithTag("app:${apps.last().key}").assertIsDisplayed().assertIsSelected().assertIsFocused()
        assertTrue(key(KeyEvent.KEYCODE_DPAD_DOWN))
        enter()
        compose.runOnIdle { assertEquals(listOf(apps[0].key, apps.last().key), launched) }
    }

    @Test fun genericTouchpadMouseAndRotaryScrollMoveSelectionAndEnterOpensPreview() {
        show(withNotification = true)
        assertTrue(scroll(InputDevice.SOURCE_TOUCHPAD, -1f))
        compose.onNodeWithTag("app:${apps[0].key}").assertIsSelected()
        assertTrue(scroll(InputDevice.SOURCE_MOUSE, -1f))
        compose.onNodeWithTag("app:${apps[1].key}").assertIsSelected()
        assertTrue(scroll(InputDevice.SOURCE_ROTARY_ENCODER, 1f, MotionEvent.AXIS_SCROLL))
        compose.onNodeWithTag("app:${apps[0].key}").assertIsSelected().assertIsFocused()
        enter()
        compose.onNodeWithTag("notification:${notification.key}").assertIsDisplayed()
        compose.runOnIdle { assertTrue(launched.isEmpty()); assertNull(navigation.move) }
        assertFalse(scroll(InputDevice.SOURCE_MOUSE, -1f))
        assertFalse(key(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test fun emptyHomeEditorsAndModifiedKeysDoNotConsumeNavigation() {
        show()
        assertFalse(key(KeyEvent.KEYCODE_DPAD_DOWN, editor = true))
        assertFalse(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_CTRL_ON))
        assertFalse(scroll(InputDevice.SOURCE_MOUSE, 0f))
        assertFalse(scroll(InputDevice.SOURCE_MOUSE, Float.NaN))
        compose.runOnIdle { shownApps = emptyList() }
        compose.waitForIdle()
        assertFalse(key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(scroll(InputDevice.SOURCE_MOUSE, -1f))
        enter()
        compose.runOnIdle { assertTrue(launched.isEmpty()) }
    }

    @Test fun touchScrollingStillReachesOffscreenAppsAndSearchKeepsKeyboard() {
        show()
        compose.onNodeWithTag("home_content").performTouchInput {
            swipeUp(startY = height * .8f, endY = height * .2f, durationMillis = 500)
        }
        compose.onNodeWithTag("app:${apps[0].key}").assertIsNotDisplayed()
        compose.onNodeWithTag("home_content").performScrollToNode(hasTestTag("app:${apps.last().key}"))
        compose.onNodeWithTag("app:${apps.last().key}").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(apps.last().key), launched) }
        val search = compose.onNodeWithTag("home_content").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsActions.CustomActions].single()
        compose.runOnUiThread { assertTrue(search.action()) }
        compose.onNodeWithTag("app_search_query").performTextInput("typed")
        compose.onNodeWithTag("app_search_query").assertTextContains("typed")
        compose.runOnIdle { assertNull(navigation.move) }
        assertFalse(key(KeyEvent.KEYCODE_DPAD_DOWN))
    }
}
