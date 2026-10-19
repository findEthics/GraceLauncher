package com.galaxyrio.gracelauncher

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.UserHandle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyrio.gracelauncher.data.*
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.data.notifications.appNotificationBucketKey
import com.galaxyrio.gracelauncher.data.notifications.NotificationReplyResult
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.overlays.NotificationPopupItem
import com.galaxyrio.gracelauncher.ui.LauncherScreen
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherAppRow
import com.galaxyrio.gracelauncher.ui.theme.GraceLauncherTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNotificationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = LauncherApp(ComponentName("fixture.chat", "ChatActivity"), "Messages", null)
    private val browser = LauncherApp(ComponentName("fixture.browser", "BrowserActivity"), "Chrome", null)
    private val workApp = app.copy(label = "Work Messages", user = UserHandle.getUserHandleForUid(1_000_000), userSerial = 77L)
    private val message = AppNotification("message-1", app.packageName, "Two new messages",
        "My computer: This is a longer message preview. ".repeat(4).trim(), System.currentTimeMillis(), 1, true, true)
    private var notifications by mutableStateOf(listOf(message))
    private val dismissed = mutableListOf<String>()
    private var acceptNotificationDismissals = true
    private var removeNotificationsImmediately = true
    private val opened = mutableListOf<String>()
    private val replies = mutableListOf<Triple<String, Long, String>>()
    private var replyResult = NotificationReplyResult.Dispatched
    private var launchedApps = 0

    private fun show(
        drawer: Boolean = false,
        shortcutAccess: Boolean = true,
        targetApp: LauncherApp = app,
        additionalApps: List<LauncherApp> = emptyList(),
    ) {
        val shortcuts = ShortcutResult(if (shortcutAccess) ShortcutStatus.Ready else ShortcutStatus.DefaultLauncherRequired,
            if (shortcutAccess) listOf(LauncherShortcut("compose", app.packageName, "Compose", null),
                LauncherShortcut("inbox", app.packageName, "Inbox", null)) else emptyList())
        val shownApps = listOf(browser, targetApp) + additionalApps
        compose.setContent {
            GraceLauncherTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFF25302F))) {
                    LauncherScreen(
                        LauncherUiState(apps = shownApps,
                            favoriteKeys = shownApps.mapTo(linkedSetOf(), LauncherApp::key),
                            isLoadingApps = false, hasShortcutAccess = shortcutAccess, textMode = WallpaperTextMode.Light,
                            workProfileSerials = shownApps.mapNotNull { it.userSerial }.toSet(),
                            notifications = notifications.groupBy { appNotificationBucketKey(it.packageName, it.userSerial) }),
                        onDateClick = {}, onClockClick = {}, onToggleFavorite = {}, onLaunchApp = { launchedApps++ },
                        initialDrawerOpen = drawer,
                        actions = LauncherActions(
                            shortcuts = { shortcuts }, cachedShortcuts = { shortcuts },
                            dismissNotification = { key, revision ->
                                val current = notifications.firstOrNull { it.key == key && it.revision == revision }
                                if (current == null || !acceptNotificationDismissals) false else {
                                    dismissed += key
                                    if (removeNotificationsImmediately) notifications = notifications.filterNot { it.key == key }
                                    true
                                }
                            },
                            openNotification = { key, _ -> opened += key; true },
                            replyNotification = { key, revision, text ->
                                replies += Triple(key, revision, text)
                                replyResult
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.assertEditableTextEquals(expected: String) = assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected))
    )

    private fun previewTextBounds(text: String, ancestorTag: String) = compose.onAllNodes(
        hasText(text, substring = true) and hasAnyAncestor(hasTestTag(ancestorTag)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().also { assertTrue("Missing '$text' under $ancestorTag", it.isNotEmpty()) }
        .minBy { it.boundsInRoot.height }.boundsInRoot

    private fun assertPreviewTextVisible(text: String, ancestorTag: String) {
        val bounds = previewTextBounds(text, ancestorTag)
        assertTrue("'$text' under $ancestorTag has empty bounds: $bounds", bounds.width > 0 && bounds.height > 0)
    }

    @Test fun homePreviewArrowOpensNotificationAboveShortcutsAndLastDismissClosesPopup() {
        show()
        compose.onNode(hasText("Two new messages") and hasAnyAncestor(hasTestTag("home_content")), useUnmergedTree = true).assertIsDisplayed()
        screenshot("notifications-home.png")
        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        val notification = compose.onNodeWithTag("notification:${message.key}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val shortcut = compose.onNodeWithTag("shortcut:compose").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(notification.bottom <= shortcut.top)
        screenshot("notifications-popup.png")
        compose.onNodeWithTag("notification:${message.key}").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("notification:${message.key}").assertDoesNotExist()
        compose.onNodeWithTag("shortcut_popup").assertDoesNotExist()
        compose.onNodeWithTag("app:${app.key}").performTouchInput { swipeRight() }
        compose.onNodeWithTag("shortcut:compose").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(message.key), dismissed); assertEquals(0, launchedApps); assertTrue(opened.isEmpty()) }
    }

    @Test fun homeAppLeftSwipeDismissesOnlyOnReleaseAndOnlyWithNotifications() {
        notifications = listOf(message)
        show()
        val row = compose.onNodeWithTag("app:${app.key}")
        row.performTouchInput {
            down(center)
            moveTo(Offset(centerX - width * 0.45f, centerY), delayMillis = 180)
            cancel()
        }
        compose.runOnIdle { assertTrue(dismissed.isEmpty()); assertEquals(listOf(message), notifications) }

        compose.runOnIdle { notifications = emptyList() }
        row.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue(dismissed.isEmpty()); assertTrue(notifications.isEmpty()) }
    }

    @Test fun drawerAppLeftSwipeDoesNotDismissNotifications() {
        notifications = listOf(message)
        show(drawer = true)
        compose.onNodeWithTag("app:${app.key}").performTouchInput { swipeLeft() }
        compose.onNode(hasText(message.title) and hasAnyAncestor(hasTestTag("app_drawer")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.runOnIdle { assertTrue(dismissed.isEmpty()); assertEquals(listOf(message), notifications) }
    }

    @Test fun homeAppLeftSwipeDismissesEveryClearableNotificationOnlyForItsProfile() {
        val postedAt = System.currentTimeMillis()
        val personal = message.copy(key = "personal", title = "Personal", text = "Personal message", postedAt = postedAt)
        val work = (0..3).map { index ->
            message.copy(key = "work-$index", title = "Work sender $index", text = "Work message $index",
                postedAt = postedAt - index * 60_000L, revision = index + 2L, userSerial = workApp.userSerial)
        }
        val ongoing = message.copy(key = "work-ongoing", title = "Ongoing", text = "Ongoing work message",
            postedAt = postedAt + 1, canDismiss = false, userSerial = workApp.userSerial)
        notifications = listOf(personal, ongoing) + work.reversed()
        show(targetApp = workApp, additionalApps = listOf(app))

        compose.onNodeWithTag("app:${workApp.key}").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(work.map { it.key }.toSet(), dismissed.toSet())
            assertEquals(setOf(personal.key, ongoing.key), notifications.map { it.key }.toSet())
        }
    }

    @Test fun homeAppRowOffersAnAccessibleBulkDismissAction() {
        val first = message.copy(key = "accessible-1")
        val second = message.copy(key = "accessible-2", revision = 2)
        notifications = listOf(first, second)
        show()

        val action = compose.onNode(
            hasTestTag("app:${app.key}") and hasAnyAncestor(hasTestTag("home_content")), useUnmergedTree = true,
        ).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == context.getString(R.string.notification_dismiss_all) }
        assertTrue(action.action())
        compose.runOnIdle { assertEquals(setOf(first.key, second.key), dismissed.toSet()); assertTrue(notifications.isEmpty()) }
    }

    @Test fun homePreviewShowsOnlyNewestThreeEligibleNotificationsWhilePopupKeepsAll() {
        val postedAt = System.currentTimeMillis()
        val eligible = (0..4).map { index ->
            message.copy(key = "eligible-$index", title = "Sender $index", text = "Sender $index: Body $index",
                postedAt = postedAt - index * 60_000L, revision = index + 2L, userSerial = workApp.userSerial)
        }
        val wrongProfile = message.copy(key = "wrong-profile-newest", title = "Wrong profile newest", text = "Do not preview",
            postedAt = postedAt + 2, userSerial = 88L)
        val wrongPackage = message.copy(key = "wrong-package-newest", packageName = "fixture.foreign",
            title = "Wrong package newest", text = "Do not preview", postedAt = postedAt + 1, userSerial = workApp.userSerial)
        notifications = listOf(eligible[4], wrongProfile, eligible[1], wrongPackage, eligible[0], eligible[3], eligible[2])
        show(targetApp = workApp)

        eligible.take(3).forEach { assertPreviewTextVisible(it.text, "home_content") }
        eligible.drop(3).forEach { compose.onNodeWithText(it.text, useUnmergedTree = true).assertDoesNotExist() }
        compose.onNodeWithText(wrongProfile.title, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(wrongPackage.title, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("notification_arrow:app:${workApp.key}").performClick()
        eligible.forEach { notification ->
            val tag = "notification:${notification.key}"
            compose.onNodeWithTag("shortcut_list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertPreviewTextVisible(notification.text, tag)
        }
    }

    @Test fun drawerPreviewShowsOnlyNewestThreeNotificationsButPopupKeepsAll() {
        val postedAt = System.currentTimeMillis()
        val all = (0..4).map { index ->
            message.copy(key = "drawer-$index", title = "Drawer sender $index", text = "Drawer message $index",
                postedAt = postedAt - index * 60_000L, revision = index + 2L, userSerial = workApp.userSerial)
        }
        notifications = all.reversed()
        show(drawer = true, targetApp = workApp)

        all.take(3).forEach { assertPreviewTextVisible(it.text, "app_drawer") }
        all.drop(3).forEach { compose.onNodeWithText(it.text, useUnmergedTree = true).assertDoesNotExist() }
        compose.onNode(hasTestTag("notification_arrow:app:${workApp.key}") and hasAnyAncestor(hasTestTag("app_drawer")))
            .performClick()
        all.forEach { notification ->
            val tag = "notification:${notification.key}"
            compose.onNodeWithTag("shortcut_list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test fun inlineNotificationLineLimitCountsDistinctTitleAndKeepsSenderSuppression() {
        val titled = message.copy(key = "line-cap-title", title = "Group summary title",
            text = "First body line\n\nSecond body line\nThird body line\nFourth body line")
        notifications = listOf(titled)
        show()

        listOf("Group summary title", "First body line", "Second body line").forEach {
            assertPreviewTextVisible(it, "home_content")
        }
        listOf("Third body line", "Fourth body line").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist()
        }
        val sender = message.copy(key = "line-cap-sender", title = "Me",
            text = "Me: First reply\n\nMe: Second reply\nThird reply\nFourth reply")
        compose.runOnIdle { notifications = listOf(sender) }
        compose.onNodeWithText("Me", useUnmergedTree = true).assertDoesNotExist()
        listOf("Me: First reply", "Me: Second reply", "Third reply").forEach {
            assertPreviewTextVisible(it, "home_content")
        }
        compose.onNodeWithText("Fourth reply", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun leftReversalOfHomePopupOnlyCollapsesAndNeverDismisses() {
        notifications = listOf(message)
        show()
        val density = context.resources.displayMetrics.density
        val row = compose.onNodeWithTag("app:${app.key}")
        row.performTouchInput {
            down(Offset(8f, centerY))
            moveTo(Offset(8f + 24f * density, centerY), delayMillis = 150)
        }
        compose.onNodeWithTag("shortcut_popup").assertIsDisplayed()
        row.performTouchInput {
            moveTo(Offset(8f + 12f * density, centerY), delayMillis = 150)
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut_popup").assertDoesNotExist()
        compose.runOnIdle { assertTrue(dismissed.isEmpty()); assertEquals(listOf(message), notifications) }
    }

    @Test fun acceptedPopupDismissalsWaitForTheLiveListAndCloseAfterRapidConsecutiveRemovals() {
        val second = message.copy(key = "message-2", title = "Another message", revision = 2)
        notifications = listOf(message, second)
        removeNotificationsImmediately = false
        show()
        compose.onNodeWithTag("app:${app.key}").performTouchInput { swipeRight() }

        compose.onNodeWithTag("notification:${message.key}").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("notification:${second.key}").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(setOf(message.key, second.key), dismissed.toSet())
            assertEquals(2, notifications.size)
        }
        compose.onNodeWithTag("shortcut_popup").assertIsDisplayed()

        compose.runOnIdle { notifications = listOf(second) }
        compose.onNodeWithTag("shortcut_popup").assertIsDisplayed()
        compose.runOnIdle { notifications = emptyList() }
        compose.onNodeWithTag("shortcut_popup").assertDoesNotExist()
        compose.onNodeWithTag("app:${app.key}").performTouchInput { swipeRight() }
        compose.onNodeWithTag("shortcut:compose").assertIsDisplayed()
    }

    @Test fun rejectedOrOngoingPopupDismissalLeavesThePopupOpen() {
        notifications = listOf(message)
        acceptNotificationDismissals = false
        show()
        compose.onNodeWithTag("app:${app.key}").performTouchInput { swipeRight() }
        compose.onNodeWithTag("notification:${message.key}").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("shortcut_popup").assertIsDisplayed()
        compose.onNodeWithTag("notification:${message.key}").assertIsDisplayed()
        compose.runOnIdle { assertTrue(dismissed.isEmpty()); assertEquals(listOf(message), notifications) }

        compose.runOnIdle { notifications = listOf(message.copy(canDismiss = false)) }
        compose.onNodeWithTag("notification:${message.key}").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("shortcut_popup").assertIsDisplayed()
        compose.runOnIdle { assertTrue(dismissed.isEmpty()); assertEquals(1, notifications.size) }
    }

    @Test fun popupOpenedWithoutNotificationsStaysOpen() {
        notifications = emptyList()
        show()
        compose.onNodeWithTag("app:${app.key}").performTouchInput { swipeRight() }
        compose.onNodeWithTag("shortcut_popup").assertIsDisplayed()
        compose.onNodeWithTag("shortcut:compose").assertIsDisplayed()
        compose.runOnIdle { assertTrue(dismissed.isEmpty()) }
    }

    @Test fun workHomePreviewOpensItsNotificationAndOffersReply() {
        notifications = listOf(message.copy(userSerial = workApp.userSerial, canReply = true))
        show(targetApp = workApp)
        compose.onNode(hasText(message.title) and hasAnyAncestor(hasTestTag("home_content")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("notification_arrow:app:${workApp.key}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("notification:${message.key}").assertIsDisplayed()
        compose.onNodeWithTag("notification_reply_button:${message.key}").performClick()
        compose.onNodeWithTag("notification_reply_input:${message.key}").performTextInput("Work reply")
        compose.onNodeWithTag("notification_reply_send:${message.key}").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple(message.key, message.revision, "Work reply")), replies)
            assertTrue(opened.isEmpty()); assertTrue(dismissed.isEmpty())
        }
    }

    @Test fun workDrawerPreviewOpensItsNotification() {
        notifications = listOf(message.copy(userSerial = workApp.userSerial))
        show(drawer = true, targetApp = workApp)
        compose.onNode(hasText(message.title) and hasAnyAncestor(hasTestTag("app_drawer")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasTestTag("notification_arrow:app:${workApp.key}") and hasAnyAncestor(hasTestTag("app_drawer")))
            .assertIsDisplayed().performClick()
        compose.onNodeWithTag("notification:${message.key}").assertIsDisplayed()
        compose.runOnIdle { assertTrue(opened.isEmpty()); assertTrue(dismissed.isEmpty()) }
    }

    @Test fun homeMessagingPreviewOmitsRedundantSenderTitle() {
        notifications = listOf(message.copy(key = "me-message", title = "Me", text = "Me: t31"))
        show()

        compose.onNode(
            hasText("Me") and hasAnyAncestor(hasTestTag("home_content")), useUnmergedTree = true,
        ).assertDoesNotExist()
        assertPreviewTextVisible("Me: t31", "home_content")
    }

    @Test fun homeMessagingPreviewRendersBodyLinesSeparately() {
        notifications = listOf(message.copy(key = "split-messages", title = "Today", text = "Me: t31\nMe: t32"))
        show()

        val firstLine = previewTextBounds("Me: t31", "home_content")
        val secondLine = previewTextBounds("Me: t32", "home_content")
        assertTrue(firstLine.bottom <= secondLine.top)
        compose.onNodeWithText("Me: t31 Me: t32", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun homePreviewShowsEveryContactNotificationAndPopupKeepsOriginalItems() {
        val newestAt = System.currentTimeMillis() - 2 * 60 * 60_000L - 10 * 60_000L
        val newest = message.copy(key = "poulami", title = "Poulami", text = "Poulami: t32", postedAt = newestAt, revision = 2)
        val older = message.copy(key = "me", title = "Me", text = "Me: t31", postedAt = newestAt - 2 * 60 * 60_000L, revision = 1)
        notifications = listOf(newest, older)
        show()

        val newestLine = previewTextBounds(newest.text, "home_content")
        val olderLine = previewTextBounds(older.text, "home_content")
        assertTrue(newestLine.top < olderLine.top)
        compose.onNode(
            hasTestTag("app:${app.key}:label") and hasAnyAncestor(hasTestTag("home_content")),
            useUnmergedTree = true,
        ).assertTextEquals("${app.label} · ${context.getString(R.string.notification_hours, 2)}")

        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        for (notification in listOf(newest, older)) {
            val itemTag = "notification:${notification.key}"
            compose.onNodeWithTag(itemTag).assertIsDisplayed()
            assertPreviewTextVisible(notification.title, itemTag)
            assertPreviewTextVisible(notification.text, itemTag)
        }
        compose.runOnIdle { assertEquals(listOf(newest, older), notifications) }
    }

    @Test fun workDrawerPreviewShowsEveryProfileNotification() {
        val newestAt = System.currentTimeMillis() - 2 * 60 * 60_000L - 10 * 60_000L
        val newest = message.copy(
            key = "work-poulami", title = "Poulami", text = "Poulami: t32", postedAt = newestAt,
            revision = 2, userSerial = workApp.userSerial,
        )
        val older = message.copy(
            key = "work-me", title = "Me", text = "Me: t31", postedAt = newestAt - 2 * 60 * 60_000L,
            revision = 1, userSerial = workApp.userSerial,
        )
        notifications = listOf(newest, older)
        show(drawer = true, targetApp = workApp)

        val newestLine = previewTextBounds(newest.text, "app_drawer")
        val olderLine = previewTextBounds(older.text, "app_drawer")
        assertTrue(newestLine.top < olderLine.top)
        compose.onNode(
            hasTestTag("app:${workApp.key}:label") and hasAnyAncestor(hasTestTag("app_drawer")),
            useUnmergedTree = true,
        ).assertTextEquals("${workApp.label} · ${context.getString(R.string.notification_hours, 2)}")

        compose.onNode(
            hasTestTag("notification_arrow:app:${workApp.key}") and hasAnyAncestor(hasTestTag("app_drawer")),
        ).assertIsDisplayed().performClick()
        for (notification in listOf(newest, older)) {
            val itemTag = "notification:${notification.key}"
            compose.onNodeWithTag(itemTag).assertIsDisplayed()
            assertPreviewTextVisible(notification.title, itemTag)
            assertPreviewTextVisible(notification.text, itemTag)
        }
        compose.runOnIdle { assertEquals(listOf(newest, older), notifications) }
    }

    @Test fun previewKeepsDistinctTitlesTitleOnlyAndBlankTitleBodyFallback() {
        val distinctTitle = message.copy(title = "Group summary", text = "Group message")
        val titleOnly = message.copy(key = "title-only", title = "Title only", text = " ")
        val bodyOnly = message.copy(key = "body-only", title = "", text = "Body fallback")
        notifications = listOf(distinctTitle)
        show()

        assertPreviewTextVisible(distinctTitle.title, "home_content")
        assertPreviewTextVisible(distinctTitle.text, "home_content")
        compose.runOnIdle { notifications = listOf(titleOnly) }
        assertPreviewTextVisible(titleOnly.title, "home_content")
        compose.runOnIdle { notifications = listOf(bodyOnly) }
        assertPreviewTextVisible(bodyOnly.text, "home_content")
    }

    @Test fun rowsStillHidePrivateShortcutUnknownAndMismatchedProfileNotifications() {
        var rowApp by mutableStateOf(workApp.copy(isPrivateSpace = true))
        var rowNotifications by mutableStateOf(listOf(message.copy(userSerial = rowApp.userSerial)))
        compose.setContent {
            GraceLauncherTheme(dynamicColor = false) {
                LauncherAppRow(rowApp, onClick = {}, onLongClick = {}, notifications = rowNotifications)
            }
        }
        val blocked = listOf(
            workApp.copy(isPrivateSpace = true) to workApp.userSerial,
            workApp.copy(shortcut = LauncherShortcut("compose", app.packageName, "Compose", null)) to workApp.userSerial,
            app.copy(user = workApp.user) to null,
            workApp to 88L,
            app to workApp.userSerial,
        )
        for ((blockedApp, serial) in blocked) {
            compose.runOnIdle {
                rowApp = blockedApp
                rowNotifications = listOf(message.copy(userSerial = serial))
            }
            compose.onNodeWithText(message.title, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("notification_arrow:app:${blockedApp.key}").assertDoesNotExist()
        }

        val newestAt = System.currentTimeMillis() - 2 * 60 * 60_000L - 10 * 60_000L
        val wrongPackage = message.copy(
            key = "wrong-package", packageName = "fixture.foreign", title = "Wrong package",
            text = "Wrong package body", postedAt = System.currentTimeMillis(), userSerial = workApp.userSerial,
        )
        val wrongProfile = message.copy(
            key = "wrong-profile", title = "Wrong profile", text = "Wrong profile body",
            postedAt = System.currentTimeMillis(), userSerial = 88L,
        )
        val eligible = message.copy(
            key = "eligible-work", title = "Eligible sender", text = "Eligible body",
            postedAt = newestAt, userSerial = workApp.userSerial,
        )
        compose.runOnIdle {
            rowApp = workApp
            rowNotifications = listOf(wrongPackage, wrongProfile, eligible)
        }
        assertPreviewTextVisible(eligible.text, "app:${workApp.key}")
        compose.onNodeWithText(wrongPackage.title, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(wrongProfile.title, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("notification_arrow:app:${workApp.key}").assertIsDisplayed()
        compose.onNodeWithTag("app:${workApp.key}:label", useUnmergedTree = true).assertTextEquals(
            "${workApp.label} · ${context.getString(R.string.notification_hours, 2)}",
        )
    }

    @Test fun drawerRightSwipeOpensSamePanelAndRightDismissKeepsOtherMessages() {
        notifications = listOf(message, message.copy(key = "message-2", title = "Another message", revision = 2))
        show(drawer = true)
        compose.onNodeWithTag("app:${app.key}").performTouchInput { swipeRight() }
        compose.onNodeWithTag("notification:${message.key}").assertIsDisplayed().performTouchInput { swipeRight() }
        compose.onNodeWithTag("notification:${message.key}").assertDoesNotExist()
        compose.onNodeWithTag("notification:message-2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(message.key), dismissed); assertEquals(0, launchedApps) }
    }

    @Test fun ongoingNotificationsAndCancelledDragsAreNotDismissedOrOpened() {
        notifications = listOf(message.copy(canDismiss = false))
        show()
        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        screenshot("notifications-ongoing-before.png")
        compose.onNodeWithTag("notification:${message.key}").assertIsDisplayed()
        compose.onNodeWithTag("notification:${message.key}").performTouchInput { swipeLeft() }
        screenshot("notifications-ongoing-after.png")
        compose.onNodeWithTag("notification:${message.key}").assertIsDisplayed()
        compose.runOnIdle { notifications = listOf(message.copy(revision = 2)) }
        compose.onNodeWithTag("notification:${message.key}").performTouchInput {
            down(center); moveBy(Offset(-50f, 0f), 250); moveBy(Offset.Zero, 250); up()
        }
        compose.onNodeWithTag("notification:${message.key}").assertIsDisplayed()
        compose.runOnIdle { assertTrue(dismissed.isEmpty()); assertTrue(opened.isEmpty()) }
    }

    @Test fun notificationWorksWithoutShortcutHostPermissionAndClickUsesItsIntent() {
        show(shortcutAccess = false)
        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        compose.onNodeWithTag("notification:${message.key}").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithTag("shortcut_popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(message.key), opened); assertEquals(0, launchedApps); assertTrue(dismissed.isEmpty()) }
    }

    @Test fun swipeBackgroundFollowsTheFingerAndANewRevisionResetsTheGesture() {
        show()
        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        val row = compose.onNodeWithTag("notification:${message.key}")
        row.performTouchInput { down(center); moveBy(Offset(-width * 0.4f, 0f), 250) }
        screenshot("notifications-swipe.png")
        compose.runOnIdle { notifications = listOf(message.copy(title = "Updated notification", revision = 2)) }
        row.performTouchInput { up() }
        row.assertIsDisplayed()
        compose.onNodeWithText("Updated notification", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertTrue(dismissed.isEmpty()); assertTrue(opened.isEmpty()) }
    }

    @Test fun longNotificationCanScrollToShortcutsWithoutHorizontalDismissal() {
        notifications = listOf(message.copy(text = "A long message that must remain readable. ".repeat(160)))
        show()
        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        compose.onNodeWithTag("shortcut_list").performScrollToNode(hasTestTag("shortcut:compose"))
        compose.onNodeWithTag("shortcut:compose").assertIsDisplayed()
        compose.runOnIdle { assertTrue(dismissed.isEmpty()); assertTrue(opened.isEmpty()) }
    }

    @Test fun searchDoesNotInheritNotificationArrowsWithoutAShortcutHost() {
        show()
        compose.onNodeWithTag("notification_arrow:app:${app.key}").assertIsDisplayed()
        openSearchFromBackground()
        compose.onAllNodesWithTag("notification_arrow:app:${app.key}").assertCountEquals(0)
        compose.onNodeWithTag("app_search_query").performTextReplacement(app.label)
        compose.onNodeWithTag("app:${app.key}").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, launchedApps); assertTrue(opened.isEmpty()) }
    }

    @Test fun replyEditorCanCancelWithoutOpeningOrDismissingTheNotification() {
        notifications = listOf(message.copy(canReply = true))
        show()
        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        compose.onNodeWithTag("notification_reply_button:${message.key}").performClick()
        val input = compose.onNodeWithTag("notification_reply_input:${message.key}").assertIsDisplayed()
        input.performTextInput("A draft")
        input.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("notification:${message.key}").assertIsDisplayed()
        compose.onNodeWithTag("notification_reply_cancel:${message.key}").performClick()
        compose.onNodeWithTag("notification_reply_input:${message.key}").assertDoesNotExist()
        compose.onNodeWithTag("notification_reply_button:${message.key}").assertIsDisplayed()
        compose.runOnIdle { assertTrue(replies.isEmpty()); assertTrue(opened.isEmpty()); assertTrue(dismissed.isEmpty()) }
    }

    @Test fun removingAnOpenReplyNotificationDiscardsItsDraft() {
        notifications = listOf(message.copy(canReply = true))
        show()
        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        compose.onNodeWithTag("notification_reply_button:${message.key}").performClick()
        compose.onNodeWithTag("notification_reply_input:${message.key}").performTextInput("Private draft")

        compose.runOnIdle { notifications = emptyList() }
        compose.onNodeWithTag("notification:${message.key}").assertDoesNotExist()
        compose.runOnIdle { notifications = listOf(message.copy(canReply = true)) }
        compose.onNodeWithTag("notification_reply_button:${message.key}").performClick()
        compose.onNodeWithTag("notification_reply_input:${message.key}").assertEditableTextEquals("")
        compose.runOnIdle { assertTrue(replies.isEmpty()) }
    }

    @Test fun replyControlsAndDispatchAreDisabledWhileThePopupIsCollapsing() {
        var gesturesEnabled by mutableStateOf(true)
        val replyable = message.copy(canReply = true)
        compose.setContent {
            GraceLauncherTheme(dynamicColor = false) {
                NotificationPopupItem(
                    app = app,
                    notification = replyable,
                    onOpen = {},
                    onDismiss = { false },
                    onReply = { key, revision, text ->
                        replies += Triple(key, revision, text)
                        NotificationReplyResult.Dispatched
                    },
                    gesturesEnabled = gesturesEnabled,
                )
            }
        }
        compose.runOnIdle { gesturesEnabled = false }
        val replyButton = compose.onNodeWithTag("notification_reply_button:${message.key}").assertIsNotEnabled()
        replyButton.performTouchInput { click() }
        compose.onNodeWithTag("notification_reply_input:${message.key}").assertDoesNotExist()
        compose.runOnIdle { gesturesEnabled = true }

        compose.onNodeWithTag("notification_reply_button:${message.key}").performClick()
        val input = compose.onNodeWithTag("notification_reply_input:${message.key}")
        input.performTextInput("Do not submit while collapsing")
        compose.runOnIdle { gesturesEnabled = false }

        input.assertIsNotEnabled()
        compose.onNodeWithTag("notification_reply_cancel:${message.key}").assertIsNotEnabled()
        val send = compose.onNodeWithTag("notification_reply_send:${message.key}").assertIsNotEnabled()
        send.performTouchInput { click() }
        compose.runOnIdle { assertTrue(replies.isEmpty()) }
    }

    @Test fun replyDispatchKeepsTheNotificationAndDoesNotDuplicateSubmits() {
        notifications = listOf(message.copy(canReply = true))
        show()
        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        compose.onNodeWithTag("notification_reply_button:${message.key}").performClick()
        val input = compose.onNodeWithTag("notification_reply_input:${message.key}").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.notification_reply_input)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.notification_reply_send)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.notification_reply_cancel)).assertIsDisplayed()
        input.performTextInput("Hello there")
        compose.onNodeWithTag("notification_reply_send:${message.key}").assertIsEnabled()
        screenshot("notification-reply-editor.png")
        val panel = compose.onNodeWithTag("shortcut_popup").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("notification_reply_send:${message.key}").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithTag("notification_reply_cancel:${message.key}").fetchSemanticsNode().boundsInRoot
        assertTrue(send.bottom <= panel.bottom && cancel.bottom <= panel.bottom)
        compose.onNodeWithTag("notification_reply_send:${message.key}").performClick()
        compose.onNodeWithTag("notification_reply_input:${message.key}").assertDoesNotExist()
        compose.onNodeWithTag("notification_reply_button:${message.key}").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(Triple(message.key, message.revision, "Hello there")), replies)
            assertTrue(opened.isEmpty()); assertTrue(dismissed.isEmpty())
            assertEquals(message.key, notifications.single().key)
        }
    }

    @Test fun blankRepliesAreDisabledAndFailedRepliesKeepTheDraftForRetry() {
        notifications = listOf(message.copy(canReply = true))
        replyResult = NotificationReplyResult.Failed
        show()
        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        compose.onNodeWithTag("notification_reply_button:${message.key}").performClick()
        val input = compose.onNodeWithTag("notification_reply_input:${message.key}").assertIsDisplayed()
        val send = compose.onNodeWithTag("notification_reply_send:${message.key}")
        send.assertIsNotEnabled()
        input.performTextInput("   ")
        send.assertIsNotEnabled()
        input.performTextClearance()
        input.performTextInput("Keep this draft")
        send.assertIsEnabled().performClick()
        input.assertEditableTextEquals("Keep this draft")
        compose.onNodeWithTag("notification_reply_error:${message.key}")
            .assertTextEquals(context.getString(R.string.notification_reply_failed))
        compose.runOnIdle {
            assertEquals(listOf(Triple(message.key, message.revision, "Keep this draft")), replies)
            replyResult = NotificationReplyResult.Dispatched
        }
        send.performClick()
        compose.onNodeWithTag("notification_reply_input:${message.key}").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, replies.size); assertTrue(opened.isEmpty()); assertTrue(dismissed.isEmpty()) }
    }

    @Test fun replacingAReplyableNotificationKeepsTheDraftButBlocksItsStaleAction() {
        notifications = listOf(message.copy(canReply = true))
        show()
        compose.onNodeWithTag("notification_arrow:app:${app.key}").performClick()
        compose.onNodeWithTag("notification_reply_button:${message.key}").performClick()
        val input = compose.onNodeWithTag("notification_reply_input:${message.key}").assertIsDisplayed()
        input.performTextInput("Do not send to the replacement")
        compose.runOnIdle { notifications = listOf(message.copy(revision = 2, title = "Updated", canReply = false)) }
        input.assertIsDisplayed().assertEditableTextEquals("Do not send to the replacement")
        compose.onNodeWithTag("notification_reply_error:${message.key}")
            .assertTextEquals(context.getString(R.string.notification_unavailable))
        compose.onNodeWithTag("notification_reply_send:${message.key}").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(replies.isEmpty()); assertTrue(opened.isEmpty()); assertTrue(dismissed.isEmpty()) }
    }

    private fun openSearchFromBackground() {
        compose.onNodeWithTag("home_gesture_surface").performTouchInput {
            val start = Offset(width * 0.9f, height * 0.9f)
            down(start)
            moveTo(Offset(start.x, height * 0.62f), delayMillis = 180)
            up()
        }
        compose.onNodeWithTag("app_search").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir("ui-verification")).also { it.mkdirs() }
        File(directory, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
