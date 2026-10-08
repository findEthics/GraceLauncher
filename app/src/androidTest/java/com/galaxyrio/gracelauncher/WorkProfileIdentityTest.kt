package com.galaxyrio.gracelauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyrio.gracelauncher.data.AppRepository
import com.galaxyrio.gracelauncher.data.FavoritesStore
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherShortcut
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.data.notifications.appNotificationBucketKey
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.drawer.AppListModel
import com.galaxyrio.gracelauncher.ui.drawer.DrawerItem
import com.galaxyrio.gracelauncher.ui.overlays.resolveWidgetProviderApp
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkProfileIdentityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "work-profile-test-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val component = ComponentName("com.example.duplicate", "com.example.duplicate.Main")
    private val personal = LauncherApp(component, "Example", null, firstInstallTime = 100L)
    private val work = LauncherApp(component, "Example", null, user = UserHandle.getUserHandleForUid(1_000_000), userSerial = 77L, firstInstallTime = 100L)
    private val privateApp = LauncherApp(component, "Example", null, user = UserHandle.getUserHandleForUid(1_100_000), userSerial = 88L,
        isPrivateSpace = true, firstInstallTime = 100L)

    @After fun cleanUp() { context.deleteSharedPreferences(preferenceName) }

    @Test fun duplicatePackagesKeepProfileQualifiedAppAndShortcutOwners() {
        assertEquals(component.flattenToString(), personal.key)
        assertEquals("profile:77:${component.flattenToString()}", work.key)
        assertNotEquals(personal.key, work.key)
        assertTrue(work.isWorkProfile)
        assertFalse(work.isPrivateSpace)
        assertFalse(privateApp.isWorkProfile)
        val shortcutApp = LauncherShortcut("id", component.packageName, "Shortcut", null, component,
            user = work.user, userSerial = work.userSerial).asApp(work)
        assertTrue(shortcutApp.isWorkProfile)
        assertEquals(work.user, shortcutApp.user)
        assertEquals("profile:77:shortcut:${component.packageName}/id", shortcutApp.key)

        assertEquals(personal, com.galaxyrio.gracelauncher.data.savedShortcutOwner(
            "shortcut:com.example.duplicate/id", component.flattenToString(), component.packageName, listOf(personal, work)))
        assertEquals(work, com.galaxyrio.gracelauncher.data.savedShortcutOwner(
            "profile:77:shortcut:com.example.duplicate/id", component.flattenToString(), component.packageName, listOf(personal, work)))
        assertNull(com.galaxyrio.gracelauncher.data.savedShortcutOwner(
            "profile:88:shortcut:com.example.duplicate/id", component.flattenToString(), component.packageName, listOf(personal, work)))
    }

    @Test fun recentlyInstalledAndNotificationsKeepThePersonalAndWorkCopiesDistinct() {
        val personalNotification = AppNotification("personal-notification", component.packageName,
            "Personal title", "Personal body", 1L, 1L, true, true)
        val workNotification = AppNotification("work-notification", component.packageName,
            "Work title", "Work body", 2L, 2L, true, true, userSerial = work.userSerial)
        val state = LauncherUiState(
            apps = listOf(personal, work, privateApp),
            workProfileSerials = setOf(requireNotNull(work.userSerial)),
            notifications = mapOf(
                appNotificationBucketKey(component.packageName, null) to listOf(personalNotification),
                appNotificationBucketKey(component.packageName, work.userSerial) to listOf(workNotification),
            ),
        )
        assertEquals(setOf(personal.key, work.key), state.recentlyInstalledApps.map(LauncherApp::key).toSet())
        assertEquals(listOf(personalNotification), state.notificationsFor(personal))
        assertEquals(listOf(workNotification), state.notificationsFor(work))
        assertTrue(state.notificationsFor(privateApp).isEmpty())
        assertTrue(state.notificationsFor(work.copy(userSerial = 99L)).isEmpty())
        assertTrue(state.copy(apps = listOf(personal, privateApp)).notificationsFor(work).isEmpty())
    }

    @Test fun recentlyInstalledDeduplicatesLauncherActivitiesWithinEachProfile() {
        val secondPersonalActivity = LauncherApp(
            ComponentName(component.packageName, "com.example.duplicate.Settings"), "Example Settings", null,
            firstInstallTime = 101L,
        )
        val state = LauncherUiState(apps = listOf(personal, secondPersonalActivity, work, privateApp))
        val recent = state.recentlyInstalledApps

        assertEquals(2, recent.size)
        assertEquals(setOf(personal.packageName), recent.map(LauncherApp::packageName).toSet())
        assertEquals(setOf(null, work.userSerial), recent.map(LauncherApp::userSerial).toSet())
    }

    @Test fun homeWidgetInfoResolvesTheProviderCopyForItsProfile() {
        val provider = ComponentName(component.packageName, "com.example.duplicate.Widget")
        val shortcut = LauncherShortcut("widget", component.packageName, "Example shortcut", null,
            user = work.user, userSerial = work.userSerial).asApp(work)

        assertSame(work, resolveWidgetProviderApp(provider, requireNotNull(work.user), listOf(personal, shortcut, work)))
        assertSame(personal, resolveWidgetProviderApp(provider, Process.myUserHandle(), listOf(work, personal, shortcut)))
        assertNull(resolveWidgetProviderApp(provider, UserHandle.getUserHandleForUid(1_200_000), listOf(personal, work, shortcut)))
    }

    @Test fun managedProfileInventoryKeepsLauncherActivityFirstInstallTime() = runBlocking {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val managedProfile = launcherApps.profiles.firstOrNull { user ->
            user != Process.myUserHandle() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM ||
                runCatching { launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_MANAGED }.getOrDefault(false))
        }
        assumeTrue("A running managed profile is required for this platform inventory check", managedProfile != null)
        val user = requireNotNull(managedProfile)
        val platformActivity = launcherApps.getActivityList(null, user).firstOrNull()
        assumeTrue("The managed profile must expose at least one launcher activity", platformActivity != null)
        val activity = requireNotNull(platformActivity)
        val app = AppRepository(context).loadAppInventory().apps.firstOrNull {
            it.user == user && it.componentName == activity.componentName
        }

        assertNotNull("Managed launcher activity was not discovered", app)
        assertEquals(activity.firstInstallTime, requireNotNull(app).firstInstallTime)
    }

    @Test fun pausedWorkInventoryRetainsFavoritesCustomizationsAndProfileIdentity() {
        val expectedOrder = listOf(personal.key, work.key)
        preferences.edit().putString("favorite_component_order", JSONArray(expectedOrder).toString())
            .putStringSet("favorite_components", expectedOrder.toSet()).commit()
        val favorites = FavoritesStore(preferences)
        assertEquals(expectedOrder, favorites.favoritesFor(listOf(personal)))
        assertEquals(expectedOrder, FavoritesStore(preferences).favoritesFor(listOf(personal, work)))

        val state = LauncherUiState(
            apps = listOf(personal),
            workProfileSerials = setOf(77L),
            workProfilesUnavailable = true,
            favoriteKeys = setOf(work.key),
            favoriteOrder = listOf(work.key),
            itemIcons = mapOf(work.key to ItemIcon.System),
            popups = mapOf(personal.key to listOf(PopupItem(work.key))),
        )
        assertTrue(state.favoriteApps.isEmpty())
        assertEquals(listOf(work.key), state.favoriteOrder)
        assertEquals(ItemIcon.System, state.itemIcons[work.key])
        assertEquals(listOf(work.key), state.popupItems(personal, emptyList()).map(PopupItem::key))
        assertFalse(state.isPrivateProfileItem(work.key))
        assertTrue(state.isPrivateProfileItem(privateApp.key))
        assertTrue(AppListModel(listOf(personal), workProfilesUnavailable = true).items.contains(DrawerItem.WorkProfileStatus))
    }
}
