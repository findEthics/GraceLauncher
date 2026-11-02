package com.galaxyrio.gracelauncher.magic

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.CalendarContract
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.net.toUri
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherShortcut
import com.katoaapps.openminilaunch.features.calendar.model.CalendarDraft
import com.katoaapps.openminilaunch.features.messaging.MessagingDeviceActions
import com.katoaapps.openminilaunch.model.LauncherShortcutTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MagicConversation(
    val target: LauncherShortcutTarget,
    val shortcut: LauncherShortcut,
    val categories: Set<String>,
)

internal enum class SmsOutcome { Queued, Unavailable, NoSim, Unknown }

internal class MagicActions(private val context: Context) {
    val messaging = MessagingDeviceActions(context, ::appLabel) { intent, chooser ->
        start(if (chooser) Intent.createChooser(intent, null) else intent)
    }
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    fun appLabel(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
    fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
    fun hasPermission(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun assistantRoleHeld(): Boolean = Build.VERSION.SDK_INT >= 29 &&
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    fun assistantRoleIntent(): Intent = if (Build.VERSION.SDK_INT >= 29 &&
        context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true)
        context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    else Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

    /** No delivery claim and no automatic retry after an uncertain platform write. */
    fun sendSms(phone: String, body: String): SmsOutcome {
        if (Build.VERSION.SDK_INT < 29 || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) ||
            !assistantRoleHeld() || !hasPermission(Manifest.permission.SEND_SMS)) return SmsOutcome.Unavailable
        if (phone.isBlank() || body.isBlank()) return SmsOutcome.Unavailable
        val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (!SubscriptionManager.isValidSubscriptionId(subscription)) return SmsOutcome.NoSim
        return try {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
                else SmsManager.getSmsManagerForSubscriptionId(subscription)
            val parts = sms.divideMessage(body)
            if (parts.size == 1) sms.sendTextMessage(phone, null, body, null, null)
            else sms.sendMultipartTextMessage(phone, null, parts, null, null)
            SmsOutcome.Queued
        } catch (_: Exception) { SmsOutcome.Unknown }
    }
    fun call(phone: String, chooser: Boolean = false): Boolean {
        val dial = Intent(Intent.ACTION_DIAL, "tel:${android.net.Uri.encode(phone)}".toUri())
        if (chooser) return start(Intent.createChooser(dial, context.getString(R.string.magic_call_app)))
        @Suppress("DEPRECATION")
        val canCall = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            !PhoneNumberUtils.isEmergencyNumber(phone) && hasPermission(Manifest.permission.CALL_PHONE)
        return (canCall && start(Intent(Intent.ACTION_CALL, dial.data))) || start(dial)
    }
    fun calendar(draft: CalendarDraft): Boolean = start(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.Events.TITLE, draft.title)
        putExtra(CalendarContract.Events.DESCRIPTION, draft.description)
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, draft.allDay)
        draft.startMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        draft.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
    })

    suspend fun conversations(apps: List<LauncherApp>, packages: Set<String>): List<MagicConversation> = withContext(Dispatchers.IO) {
        if (!launcherApps.hasShortcutHostPermission()) return@withContext emptyList()
        val flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
        apps.filter { !it.isPrivateSpace && it.packageName in packages && it.shortcut == null }
            .distinctBy { it.packageName to it.userSerial }.flatMap { app ->
                val user = app.user ?: Process.myUserHandle()
                if (!eligible(app.packageName, user)) return@flatMap emptyList()
                runCatching { launcherApps.getShortcuts(LauncherApps.ShortcutQuery().setPackage(app.packageName).setQueryFlags(flags), user) }
                    .getOrNull().orEmpty().filter { info ->
                        info.isEnabled && info.isDynamic && !info.categories.isNullOrEmpty() &&
                            (Build.VERSION.SDK_INT < 33 || !info.isExcludedFromSurfaces(android.content.pm.ShortcutInfo.SURFACE_LAUNCHER))
                    }.sortedWith(compareBy({ it.rank }, { -it.lastChangedTimestamp })).map { info ->
                        val name = info.shortLabel?.toString().orEmpty()
                        MagicConversation(LauncherShortcutTarget(name, app.packageName, info.id,
                            users.getSerialNumberForUser(user), app.isWorkProfile),
                            LauncherShortcut(info.id, app.packageName, name, null, info.activity, app.user, app.userSerial), info.categories.orEmpty())
                    }
            }.distinctBy { it.target.selectionKey }.take(40)
    }

    fun conversationEligible(value: MagicConversation): Boolean {
        val user = value.shortcut.user ?: Process.myUserHandle()
        if (!eligible(value.target.packageName, user)) return false
        return runCatching { launcherApps.getShortcuts(LauncherApps.ShortcutQuery().setPackage(value.target.packageName)
            .setShortcutIds(listOf(value.target.shortcutId)).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC), user)
            ?.any { it.isEnabled && it.isDynamic && !it.categories.isNullOrEmpty() } == true }.getOrDefault(false)
    }
    private fun eligible(pkg: String, user: UserHandle): Boolean = runCatching {
        launcherApps.hasShortcutHostPermission() && users.isUserUnlocked(user) && !users.isQuietModeEnabled(user) &&
            launcherApps.isPackageEnabled(pkg, user) && (user == Process.myUserHandle() ||
                (Build.VERSION.SDK_INT < 35 || launcherApps.getLauncherUserInfo(user)?.userType == "android.os.usertype.profile.MANAGED"))
    }.getOrDefault(false)
}
