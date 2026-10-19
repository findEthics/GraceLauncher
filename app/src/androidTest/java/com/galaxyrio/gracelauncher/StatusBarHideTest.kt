package com.galaxyrio.gracelauncher

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.galaxyrio.gracelauncher.ui.components.rememberLauncherSystemBars
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class StatusBarHideTest {
    @get:Rule val compose = createComposeRule()

    private data class Sample(
        val phase: String,
        val callback: String,
        val elapsedMs: Long,
        val alpha: Float,
        val fraction: Float,
        val interpolatedFraction: Float,
    )

    @Test fun autoHideTimeoutDoesNotAnimateStatusBarButPullRevealStillDoes() {
        val activity = AtomicReference<Activity?>()
        val pullConnection = AtomicReference<NestedScrollConnection?>()
        val phase = AtomicReference<String?>(null)
        val phaseStartNanos = AtomicReference(0L)
        val samples = CopyOnWriteArrayList<Sample>()
        val statusBars = WindowInsets.Type.statusBars()
        val callback = object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            private fun record(name: String, animation: WindowInsetsAnimation) {
                val currentPhase = phase.get() ?: return
                if (animation.typeMask and statusBars == 0) return
                val elapsed = SystemClock.elapsedRealtimeNanos() - phaseStartNanos.get()
                samples += Sample(
                    phase = currentPhase,
                    callback = name,
                    elapsedMs = elapsed / 1_000_000L,
                    alpha = animation.alpha,
                    fraction = animation.fraction,
                    interpolatedFraction = animation.interpolatedFraction,
                )
            }

            override fun onPrepare(animation: WindowInsetsAnimation) = record("prepare", animation)

            override fun onStart(
                animation: WindowInsetsAnimation,
                bounds: WindowInsetsAnimation.Bounds,
            ): WindowInsetsAnimation.Bounds {
                record("start", animation)
                return bounds
            }

            override fun onProgress(
                insets: WindowInsets,
                runningAnimations: MutableList<WindowInsetsAnimation>,
            ): WindowInsets {
                runningAnimations.forEach { record("progress", it) }
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimation) = record("end", animation)
        }

        compose.setContent {
            val host = LocalActivity.current
            val connection = rememberLauncherSystemBars(
                autoHide = true,
                darkIcons = false,
                allowPullDown = true,
            )
            SideEffect {
                activity.set(host)
                pullConnection.set(connection)
            }
        }

        compose.waitUntil(5_000) {
            val root = activity.get()?.window?.decorView ?: return@waitUntil false
            ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.statusBars()) == false
        }
        val root = requireNotNull(activity.get()).window.decorView
        compose.runOnUiThread { root.setWindowInsetsAnimationCallback(callback) }
        // Let any visibility callback from the initial show-to-hidden transition settle first.
        SystemClock.sleep(3_200L)

        phaseStartNanos.set(SystemClock.elapsedRealtimeNanos())
        phase.set("pull-reveal")
        compose.runOnUiThread {
            requireNotNull(pullConnection.get()).onPostScroll(
                consumed = Offset.Zero,
                available = Offset(0f, 1f),
                source = NestedScrollSource.UserInput,
            )
        }
        compose.waitUntil(3_000) {
            ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true
        }
        SystemClock.sleep(400L)

        val revealSamples = samples.filter { it.phase == "pull-reveal" }
        phaseStartNanos.set(SystemClock.elapsedRealtimeNanos())
        phase.set("timeout-hide")
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.statusBars()) == false
        }
        SystemClock.sleep(350L)
        phase.set(null)

        val hideSamples = samples.filter { it.phase == "timeout-hide" }
        val details = samples.joinToString { sample ->
            "${sample.phase}/${sample.callback}@${sample.elapsedMs}ms " +
                "alpha=${"%.2f".format(sample.alpha)} " +
                "fraction=${"%.2f".format(sample.fraction)} " +
                "interpolated=${"%.2f".format(sample.interpolatedFraction)}"
        }
        Log.i("StatusBarHideTest", "API ${android.os.Build.VERSION.SDK_INT} insets callbacks: $details")
        compose.runOnUiThread { root.setWindowInsetsAnimationCallback(null) }

        assertTrue("The real controlled pull reveal did not report its animation: $details", revealSamples.isNotEmpty())
        assertTrue(
            "The pull reveal should still animate its status-bar alpha: $details",
            revealSamples.any { it.alpha < 0.99f || it.fraction in 0.01f..0.99f },
        )
        assertTrue(
            "The status-bar auto-hide must not run a native hide animation: $details",
            hideSamples.none { it.alpha < 0.99f || it.fraction in 0.01f..0.99f },
        )
    }
}
