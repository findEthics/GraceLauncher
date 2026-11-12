package com.galaxyrio.gracelauncher

import android.content.Intent
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.galaxyrio.gracelauncher.platform.LauncherAppTransitions
import com.galaxyrio.gracelauncher.platform.launchNotesCapture
import com.galaxyrio.gracelauncher.platform.notesCaptureAction
import com.galaxyrio.gracelauncher.ui.LauncherAppTheme
import com.galaxyrio.gracelauncher.ui.LauncherRoute
import com.galaxyrio.gracelauncher.ui.LauncherViewModel
import com.galaxyrio.gracelauncher.ui.components.LocalAppTransitions

class MainActivity : ComponentActivity() {
    internal val homeAppNavigation = com.galaxyrio.gracelauncher.ui.home.HomeAppNavigation()
    private val launcherViewModel: LauncherViewModel by viewModels()
    private var widgetEditRequest by mutableIntStateOf(0)
    private val appTransitions by lazy { LauncherAppTransitions(this, lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_EDIT_WIDGET, false)) { widgetEditRequest++; intent.removeExtra(EXTRA_EDIT_WIDGET) }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            CompositionLocalProvider(LocalAppTransitions provides appTransitions) {
                LauncherAppTheme(launcherViewModel) {
                    LauncherRoute(viewModel = launcherViewModel, widgetEditRequest = widgetEditRequest)
                }
            }
        }
        appTransitions.onHomeIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) launcherViewModel.assistant.openChat()
    }

    override fun onResume() {
        super.onResume()
        launcherViewModel.refreshApps()
        launcherViewModel.refreshSchedule()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) {
            launcherViewModel.magic.close()
            launcherViewModel.assistant.openChat()
        }
        if (intent.getBooleanExtra(EXTRA_EDIT_WIDGET, false)) { widgetEditRequest++; intent.removeExtra(EXTRA_EDIT_WIDGET) }
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            launcherViewModel.magic.close()
            launcherViewModel.requestReturnHome()
            appTransitions.onHomeIntent(intent)
        }
    }

    override fun onPause() {
        homeAppNavigation.release()
        appTransitions.close()
        super.onPause()
    }

    override fun onDestroy() {
        appTransitions.close()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) appTransitions.close()
        return super.dispatchTouchEvent(event)
    }

    // AndroidX marks its override restricted; this must still delegate to Android's public dispatch chain.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Match physical keys before Unicode command routing: Alt may produce a symbol on Titan keyboards.
        val capture = notesCaptureAction(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            metaState = event.metaState,
            textEditorFocused = currentFocus?.onCheckIsTextEditor() == true,
            idleHome = launcherViewModel.magic.eligible,
            inputActive = launcherViewModel.magic.session != null || launcherViewModel.magic.searchOpening ||
                launcherViewModel.assistant.chat.visible || launcherViewModel.assistant.composerFocused,
        )
        if (capture != null && !event.isCanceled) {
            launchNotesCapture(this, capture)
            return true
        }
        if (launcherViewModel.magic.hardwareKey(event, currentFocus?.onCheckIsTextEditor() == true)) return true
        if (launcherViewModel.assistant.hardwareKey(event, currentFocus?.onCheckIsTextEditor() == true)) return true
        if (homeAppNavigation.key(event, currentFocus?.onCheckIsTextEditor() == true)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (homeAppNavigation.motion(event, currentFocus?.onCheckIsTextEditor() == true)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) homeAppNavigation.release()
    }

    companion object { const val EXTRA_EDIT_WIDGET = "editHomeWidget" }
}
