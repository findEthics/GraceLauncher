package com.galaxyrio.gracelauncher

import android.content.ComponentName
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherScreen
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.theme.GraceLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherHomeGestureTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val apps = (0..25).map { index ->
        LauncherApp(ComponentName("gesture.test", "App$index"), "App $index", null)
    }

    private fun showLauncher(
        actions: LauncherActions = LauncherActions(),
        favoriteCount: Int = 6,
        initialDrawerOpen: Boolean = false,
        widgetEditRequest: Int = 0,
        onClockClick: () -> Unit = {},
    ) {
        compose.setContent {
            GraceLauncherTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFF152431))) {
                    LauncherScreen(
                        uiState = LauncherUiState(
                            apps = apps,
                            favoriteKeys = apps.take(favoriteCount).mapTo(linkedSetOf(), LauncherApp::key),
                            isLoadingApps = false,
                        ),
                        onDateClick = {}, onClockClick = onClockClick, onLaunchApp = {}, onToggleFavorite = {},
                        actions = actions, initialDrawerOpen = initialDrawerOpen, widgetEditRequest = widgetEditRequest,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aSingleTapOnBlankHomeDoesNotLockOrOpenSearch() {
        var lockCalls = 0
        showLauncher(actions = LauncherActions(lockScreen = { lockCalls++; true }))
        tapBackground()
        compose.onNodeWithTag("app_search").assertDoesNotExist()
        assertEquals(0, lockCalls)
    }

    @Test
    fun aLongHoldFollowedByOneTapDoesNotCountAsDoubleTap() {
        var lockCalls = 0
        showLauncher(actions = LauncherActions(lockScreen = { lockCalls++; true }))
        compose.onNodeWithTag("home_gesture_surface").performTouchInput {
            val point = Offset(width * 0.9f, height * 0.18f)
            down(point)
            advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
            up()
            advanceEventTime(100)
            click(point)
        }
        assertEquals(0, lockCalls)
        compose.onNodeWithTag("home_clock").assertIsDisplayed()
        compose.onNodeWithTag("app_search").assertDoesNotExist()
    }

    @Test
    fun aBlankBackgroundDoubleTapUsesTheOptInLockAction() {
        var lockCalls = 0
        showLauncher(actions = LauncherActions(lockScreen = { lockCalls++; true }))
        doubleTapBackground()
        assertEquals(1, lockCalls)
        compose.onNodeWithTag("home_clock").assertIsDisplayed()
        compose.onNodeWithTag("app_search").assertDoesNotExist()
    }

    @Test
    fun aRevokedOrDisconnectedLockActionFailsWithoutLeavingHome() {
        var lockCalls = 0
        showLauncher(actions = LauncherActions(lockScreen = { lockCalls++; false }))
        doubleTapBackground()
        assertEquals(1, lockCalls)
        compose.onNodeWithTag("home_clock").assertIsDisplayed()
        compose.onNodeWithTag("app_search").assertDoesNotExist()
    }

    @Test
    fun upwardBackgroundSwipeOpensSearch() {
        showLauncher()
        swipeUpBackground()
        compose.onNodeWithTag("app_search").assertIsDisplayed()
    }

    @Test
    fun homeContentOffersAnAccessibleSearchAction() {
        showLauncher()
        val searchAction = compose.onNodeWithTag("home_content").fetchSemanticsNode().config[
            SemanticsActions.CustomActions
        ].single { it.label == context.getString(R.string.search_apps) }
        assertTrue(searchAction.action())
        compose.onNodeWithTag("app_search").assertIsDisplayed()
    }

    @Test
    fun clockDateAndScrollableFavoritesKeepTheirOwnTouchGestures() {
        var lockCalls = 0
        var clockClicks = 0
        showLauncher(
            actions = LauncherActions(lockScreen = { lockCalls++; true }),
            favoriteCount = apps.size,
            onClockClick = { clockClicks++ },
        )
        compose.onNodeWithTag("home_clock").performTouchInput {
            click(center)
            advanceEventTime(100)
            click(center)
        }
        assertEquals(2, clockClicks)
        assertEquals(0, lockCalls)
        compose.onNodeWithTag("home_date").performTouchInput { swipeUp() }
        compose.onNodeWithTag("app_search").assertDoesNotExist()

        val firstFavorite = compose.onNodeWithTag("app:${apps.first().key}")
        val before = firstFavorite.fetchSemanticsNode().boundsInRoot.top
        firstFavorite.performTouchInput {
            val start = Offset(centerX, centerY)
            down(start)
            moveTo(Offset(start.x, start.y - height * 0.8f), delayMillis = 180)
            up()
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("app:${apps.first().key}").fetchSemanticsNode().boundsInRoot.top
        assertTrue("A swipe on a favorite should still scroll the home list", after < before)
        assertEquals(0, lockCalls)
        compose.onNodeWithTag("app_search").assertDoesNotExist()
    }

    @Test
    fun drawerDoesNotRunHomeGestures() {
        var lockCalls = 0
        showLauncher(actions = LauncherActions(lockScreen = { lockCalls++; true }), initialDrawerOpen = true)
        doubleTapBackground()
        assertEquals(0, lockCalls)
        compose.onNodeWithTag("app_search").assertDoesNotExist()
    }

    @Test
    fun overlayDoesNotRunHomeGestures() {
        var lockCalls = 0
        showLauncher(actions = LauncherActions(lockScreen = { lockCalls++; true }))
        compose.onNodeWithTag("home_clock").performTouchInput { longClick() }
        compose.onNodeWithTag("launcher_sheet").assertIsDisplayed().performTouchInput {
            click(center)
            advanceEventTime(100)
            click(center)
        }
        assertEquals(0, lockCalls)
    }

    @Test
    fun editingDisablesGesturesAndDoneStillCompletesLayoutEditing() {
        var lockCalls = 0
        showLauncher(actions = LauncherActions(lockScreen = { lockCalls++; true }), widgetEditRequest = 1)
        doubleTapBackground(y = 0.18f)
        assertEquals(0, lockCalls)
        compose.onNodeWithTag("home_edit_done").assertIsDisplayed().performClick()
        compose.onNodeWithTag("home_edit_done").assertDoesNotExist()
        compose.onNodeWithTag("home_position_handle").assertDoesNotExist()
    }

    private fun tapBackground(x: Float = 0.9f, y: Float = 0.18f) {
        compose.onNodeWithTag("home_gesture_surface").performTouchInput {
            click(Offset(width * x, height * y))
        }
    }

    private fun doubleTapBackground(x: Float = 0.9f, y: Float = 0.18f) {
        compose.onNodeWithTag("home_gesture_surface").performTouchInput {
            val point = Offset(width * x, height * y)
            click(point)
            advanceEventTime(100)
            click(point)
        }
    }

    private fun swipeUpBackground() {
        compose.onNodeWithTag("home_gesture_surface").performTouchInput {
            val start = Offset(width * 0.9f, height * 0.22f)
            down(start)
            moveTo(Offset(start.x, height * 0.1f), delayMillis = 180)
            up()
        }
    }
}
