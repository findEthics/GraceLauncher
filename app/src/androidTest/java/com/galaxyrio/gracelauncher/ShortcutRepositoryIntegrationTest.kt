package com.galaxyrio.gracelauncher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.ShortcutRepository
import com.galaxyrio.gracelauncher.data.ShortcutStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShortcutRepositoryIntegrationTest {
    @Test
    fun coldCacheLoadsShortcutsAndPrefetchWarmsOtherApps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ShortcutManager::class.java)
        val component = ComponentName(context, SettingsActivity::class.java)
        val app = LauncherApp(component, "Grace shortcut fixture", null)
        val otherApp = LauncherApp(ComponentName("com.android.settings", "com.android.settings.Settings"), "Settings", null)
        val id = "grace-shortcut-fixture-${System.nanoTime()}"
        val shortcut = ShortcutInfo.Builder(context, id)
            .setShortLabel("Repository regression fixture")
            .setActivity(component)
            .setIntent(Intent(context, SettingsActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        try {
            assertTrue(manager.addDynamicShortcuts(listOf(shortcut)))
            ShortcutRepository(context).use { repository ->
                assertTrue("Make Grace the default HOME on the test device first", repository.hasAccess())
                assertNull("A valid cold cache miss is not an error", repository.peek(app))
                val result = withTimeout(12_000) { repository.shortcutsFor(app) }
                assertEquals(ShortcutStatus.Ready, result.status)
                assertTrue(result.shortcuts.any { it.id == id })
                assertEquals(result, repository.peek(app))
                assertEquals(result, repository.shortcutsFor(app))
            }
            ShortcutRepository(context).use { repository ->
                withTimeout(12_000) { repository.prefetch(listOf(app, otherApp)) }
                assertEquals(ShortcutStatus.Ready, repository.peek(app)?.status)
                assertTrue(repository.peek(app)!!.shortcuts.any { it.id == id })
                assertEquals(ShortcutStatus.Ready, repository.peek(otherApp)?.status)
            }
        } finally {
            manager.removeDynamicShortcuts(listOf(id))
        }
    }
}
