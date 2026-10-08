package com.galaxyrio.gracelauncher

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSession
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyrio.gracelauncher.data.notifications.AppNotificationStore
import com.galaxyrio.gracelauncher.data.notifications.NotificationReplyResult
import com.galaxyrio.gracelauncher.data.notifications.replyBlockedByKeyguard
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNotificationStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = AppNotificationStore()
    private val cancelled = mutableListOf<String>()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun builder() = Notification.Builder(context, "notification-fixture")
        .setSmallIcon(R.drawable.ms_info).setContentTitle("Fixture title").setContentText("Short preview")
    @Suppress("DEPRECATION")
    private fun sbn(id: Int, notification: Notification = builder().build(), time: Long = id.toLong(), user: UserHandle = Process.myUserHandle()) =
        StatusBarNotification(context.packageName, context.packageName, id, null, Process.myUid(), 0, 0,
            notification, user, time)
    private fun notifications() = store.state.value.values.flatten()
    private fun replyPendingIntent(requestCode: Int, action: String = REPLY_ACTION): PendingIntent {
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, requestCode, Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_CANCEL_CURRENT or mutableFlag)
    }
    private fun replyAction(pendingIntent: PendingIntent, vararg inputs: RemoteInput) =
        Notification.Action.Builder(R.drawable.ms_info, "Reply", pendingIntent).apply {
            inputs.forEach { addRemoteInput(it) }
        }.build()
    private fun reply(key: String, revision: Long, text: String): NotificationReplyResult {
        var result = NotificationReplyResult.Unavailable
        main { result = store.reply(context, key, revision, text) }
        return result
    }
    @Before fun connect() = main { store.connected(emptyList()) { cancelled += it; true } }

    @Test fun longTextAndNewerNotificationAreUsedWithoutMutatingSystemNotifications() = main {
        val body = "This is a long message. ".repeat(40)
        store.posted(sbn(1, builder().setStyle(Notification.BigTextStyle().bigText(body)).build()))
        assertEquals(body.trim(), notifications().single().text)
        store.posted(sbn(2))
        assertEquals(listOf(sbn(2).key, sbn(1).key), notifications().map { it.key })
        assertTrue(cancelled.isEmpty())
    }

    @Test fun messagingAndInboxStylesKeepTheirExpandedText() = main {
        val message = NotificationCompat.Builder(context, "notification-fixture").setSmallIcon(R.drawable.ms_info)
            .setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
                .addMessage("First message", 1, Person.Builder().setName("Alice").build())
                .addMessage("Second message", 2, Person.Builder().setName("Bob").build())).build()
        store.posted(sbn(1, message))
        assertEquals("Alice: First message\nBob: Second message", notifications().single().text)
        store.posted(sbn(1, builder().setStyle(Notification.InboxStyle().addLine("One").addLine("Two")).build()))
        assertEquals("One\nTwo", notifications().single().text)
    }

    @Test fun groupSummaryIsNotDuplicatedAndDismissingChildOnlyCancelsThatKey() = main {
        val summary = sbn(1, builder().setGroup("chat").setGroupSummary(true).build())
        val first = sbn(2, builder().setGroup("chat").build())
        val second = sbn(3, builder().setGroup("chat").build())
        listOf(summary, first, second).forEach(store::posted)
        assertEquals(setOf(first.key, second.key), notifications().map { it.key }.toSet())
        val item = notifications().first { it.key == first.key }
        assertTrue(store.dismiss(item.key, item.revision))
        assertEquals(listOf(first.key), cancelled)
        assertEquals(2, notifications().size) // Only the system removal callback commits dismissal.
        store.removed(first.key)
        assertEquals(second.key, notifications().single().key)
    }

    @Test fun ongoingAndUpdatedNotificationsCannotBeDismissedByAStaleGesture() = main {
        store.posted(sbn(1))
        val old = notifications().single()
        store.posted(sbn(1, builder().setContentText("Updated message").build(), time = 5))
        assertFalse(store.dismiss(old.key, old.revision))
        store.posted(sbn(1, builder().setOngoing(true).build()))
        val ongoing = notifications().single()
        assertFalse(ongoing.canDismiss)
        assertFalse(store.dismiss(ongoing.key, ongoing.revision))
        assertTrue(cancelled.isEmpty())
    }

    @Test fun mediaSecretAndOtherProfilesAreNotShownAsAppMessages() = main {
        val player = MediaSession(context, "notification-filter-test")
        try {
            store.posted(sbn(1, builder().setStyle(Notification.MediaStyle().setMediaSession(player.sessionToken)).build()))
            store.posted(sbn(2, builder().setVisibility(Notification.VISIBILITY_SECRET).build()))
            store.posted(sbn(3, user = UserHandle.getUserHandleForUid(1_000_000)))
            assertTrue(notifications().isEmpty())
        } finally { player.release() }
    }

    @Test fun disconnectClearsPrivateContentAndReconnectRebuildsOnlyCurrentNotifications() = main {
        store.posted(sbn(1))
        val old = notifications().single()
        store.disconnected()
        store.posted(sbn(2))
        assertTrue(notifications().isEmpty())
        assertFalse(store.dismiss(old.key, old.revision))
        store.connected(listOf(sbn(3))) { cancelled += it; true }
        assertEquals(sbn(3).key, notifications().single().key)
        store.ranking { false }
        assertTrue(notifications().isEmpty())
        store.ranking { true }
        assertEquals(1, notifications().size)
    }

    @Test fun missingOrStaleContentIntentDoesNotLaunchAnything() = main {
        store.posted(sbn(1))
        val item = notifications().single()
        assertFalse(item.canOpen)
        assertFalse(store.open(context, item.key, item.revision))
        val pending = PendingIntent.getActivity(context, 407, Intent(context, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        store.posted(sbn(1, builder().setContentIntent(pending).build()))
        assertTrue(notifications().single().canOpen)
        assertFalse(store.open(context, item.key, item.revision))
        pending.cancel()
        val updated = notifications().single()
        assertFalse(store.open(context, updated.key, updated.revision))
    }

    @Test fun replyUsesTheSelectedActionAndOnlyFreeFormInputKeys() {
        val replyPending = replyPendingIntent(801)
        val unsupportedPending = replyPendingIntent(802, "fixture.notification.UNSUPPORTED")
        val contentPending = replyPendingIntent(803, "fixture.notification.CONTENT")
        val received = AtomicReference<Intent?>()
        val delivered = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) {
                    received.set(Intent(intent))
                    delivered.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(REPLY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val choices = RemoteInput.Builder("choice").setAllowFreeFormInput(false).setChoices(arrayOf("Yes", "No")).build()
            val dataOnly = RemoteInput.Builder("attachment").setAllowFreeFormInput(false)
                .setAllowDataType("image/png", true).build()
            val answer = RemoteInput.Builder("answer").setAllowFreeFormInput(true).build()
            val alternateAnswer = RemoteInput.Builder("alternate_answer").setAllowFreeFormInput(true).build()
            val notification = builder().setContentIntent(contentPending)
                .addAction(replyAction(unsupportedPending, choices, dataOnly))
                .addAction(replyAction(replyPending, choices, dataOnly, answer, alternateAnswer)).build()
            main { store.posted(sbn(1, notification)) }
            val item = notifications().single()
            assertTrue(item.canReply)
            assertEquals(NotificationReplyResult.Dispatched, reply(item.key, item.revision, "Hello"))
            assertTrue(delivered.await(3, TimeUnit.SECONDS))

            val intent = requireNotNull(received.get())
            val results = requireNotNull(RemoteInput.getResultsFromIntent(intent))
            assertEquals(REPLY_ACTION, intent.action)
            assertEquals("Hello", results.getCharSequence("answer").toString())
            assertEquals("Hello", results.getCharSequence("alternate_answer").toString())
            assertNull(results.getCharSequence("choice"))
            assertNull(results.getCharSequence("attachment"))
            assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT, RemoteInput.getResultsSource(intent))
            assertTrue(cancelled.isEmpty())
            assertEquals(item.key, notifications().single().key)
        } finally {
            context.unregisterReceiver(receiver)
            replyPending.cancel()
            unsupportedPending.cancel()
            contentPending.cancel()
        }
    }

    @Test fun choiceOnlyAndDataOnlyActionsDoNotExposeReply() {
        val pending = replyPendingIntent(804)
        try {
            val choices = RemoteInput.Builder("choice").setAllowFreeFormInput(false).setChoices(arrayOf("Yes")).build()
            val dataOnly = RemoteInput.Builder("attachment").setAllowFreeFormInput(false)
                .setAllowDataType("image/png", true).build()
            main { store.posted(sbn(1, builder().addAction(replyAction(pending, choices, dataOnly)).build())) }
            val item = notifications().single()
            assertFalse(item.canReply)
            assertEquals(NotificationReplyResult.Unavailable, reply(item.key, item.revision, "Hello"))
            assertTrue(cancelled.isEmpty())
        } finally { pending.cancel() }
    }

    @Test fun ordinaryActionsRemainVisibleWithoutReplyControls() {
        val pending = replyPendingIntent(808, "fixture.notification.ORDINARY")
        try {
            val action = Notification.Action.Builder(R.drawable.ms_info, "Open", pending).build()
            main { store.posted(sbn(1, builder().addAction(action).build())) }
            val item = notifications().single()
            assertEquals("Fixture title", item.title)
            assertFalse(item.canReply)
            assertTrue(cancelled.isEmpty())
        } finally { pending.cancel() }
    }

    @Test fun replyAuthenticationRequirementUsesTheActualLockState() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        assumeTrue("Unlock the disposable emulator before running focused reply tests",
            !context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val pending = replyPendingIntent(809)
        val received = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { received.countDown() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(REPLY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val input = RemoteInput.Builder("reply").build()
            val action = Notification.Action.Builder(R.drawable.ms_info, "Reply", pending)
                .addRemoteInput(input).setAuthenticationRequired(true).build()
            main { store.posted(sbn(1, builder().addAction(action).build())) }
            val item = notifications().single()
            assertTrue(item.canReply)
            assertEquals(NotificationReplyResult.Dispatched, reply(item.key, item.revision, "Unlocked reply"))
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertEquals(item.key, notifications().single().key)
        } finally {
            context.unregisterReceiver(receiver)
            pending.cancel()
        }
    }

    @Test fun authenticationRequiredRepliesAreBlockedOnlyWhenLocked() {
        assertTrue(replyBlockedByKeyguard(requiresAuthentication = true, isDeviceLocked = true))
        assertFalse(replyBlockedByKeyguard(requiresAuthentication = true, isDeviceLocked = false))
        assertFalse(replyBlockedByKeyguard(requiresAuthentication = false, isDeviceLocked = true))
    }

    @Test fun immutableReplyPendingIntentIsNotExposedAsReply() {
        val pending = PendingIntent.getBroadcast(context, 807, Intent(REPLY_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            val input = RemoteInput.Builder("reply").build()
            main { store.posted(sbn(1, builder().addAction(replyAction(pending, input)).build())) }
            val item = notifications().single()
            assertFalse(item.canReply)
            assertEquals(NotificationReplyResult.Unavailable, reply(item.key, item.revision, "Hello"))
        } finally { pending.cancel() }
    }

    @Test fun replyRequiresNonBlankCurrentVisibleConnectedNotification() {
        val pending = replyPendingIntent(805)
        try {
            val input = RemoteInput.Builder("reply").build()
            val currentNotification = builder().addAction(replyAction(pending, input)).build()
            main { store.posted(sbn(1, currentNotification)) }
            val old = notifications().single()
            assertEquals(NotificationReplyResult.InvalidText, reply(old.key, old.revision, " \n "))

            main { store.ranking { false } }
            assertEquals(NotificationReplyResult.Unavailable, reply(old.key, old.revision, "Hello"))
            main { store.ranking { true } }

            main { store.posted(sbn(1, builder().setContentText("Replacement").addAction(replyAction(pending, input)).build(), time = 5)) }
            val replacement = notifications().single()
            assertNotEquals(old.revision, replacement.revision)
            assertEquals(NotificationReplyResult.Unavailable, reply(old.key, old.revision, "Hello"))

            main { store.removed(replacement.key) }
            assertEquals(NotificationReplyResult.Unavailable, reply(replacement.key, replacement.revision, "Hello"))
            main { store.posted(sbn(2, currentNotification)) }
            val disconnected = notifications().single()
            main { store.disconnected() }
            assertEquals(NotificationReplyResult.Unavailable, reply(disconnected.key, disconnected.revision, "Hello"))
        } finally { pending.cancel() }
    }

    @Test fun canceledReplyPendingIntentFailsWithoutClearingNotification() {
        val pending = replyPendingIntent(806)
        val input = RemoteInput.Builder("reply").build()
        main { store.posted(sbn(1, builder().addAction(replyAction(pending, input)).build())) }
        val item = notifications().single()
        pending.cancel()
        assertEquals(NotificationReplyResult.Failed, reply(item.key, item.revision, "Hello"))
        assertEquals(item.key, notifications().single().key)
        assertTrue(cancelled.isEmpty())
    }

    private companion object {
        const val REPLY_ACTION = "fixture.notification.REPLY"
    }
}
