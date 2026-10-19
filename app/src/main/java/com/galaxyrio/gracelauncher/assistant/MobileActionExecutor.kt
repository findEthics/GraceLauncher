package com.galaxyrio.gracelauncher.assistant

import android.annotation.SuppressLint
import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.AlarmClock
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Patterns
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

object MobileActionExecutor {
    private val parameters = mapOf(
        "turnOnFlashlight" to emptySet(),
        "turnOffFlashlight" to emptySet(),
        "createContact" to setOf("firstName", "lastName", "phoneNumber", "email"),
        "sendEmail" to setOf("to", "subject", "body"),
        "showLocationOnMap" to setOf("location"),
        "openWifiSettings" to emptySet(),
        "createCalendarEvent" to setOf("datetime", "title"),
        "createTimer" to setOf("duration", "label"),
        "createAlarm" to setOf("time", "label"),
        "callContact" to setOf("contactName", "phoneLabel"),
    )
    private val optionalBlank = mapOf(
        "createContact" to setOf("firstName", "lastName", "phoneNumber", "email"),
        "createTimer" to setOf("label"), "createAlarm" to setOf("label"), "callContact" to setOf("phoneLabel"),
    )
    private val localDateTimeFormat = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)
    private val phoneFormat = Regex("\\+?[0-9() .-]+")

    internal fun applySchemaDefaults(name: String, arguments: Map<String, Any?>): Map<String, Any?> =
        arguments + optionalBlank[name].orEmpty().filter {
            it !in arguments && !(name == "createContact" && it == "firstName")
        }.associateWith { "" }

    fun validate(action: MobileAction) {
        val keys = parameters[action.name] ?: throw IllegalArgumentException("Unsupported mobile action.")
        require(action.arguments.keys == keys) { "Action arguments do not match the required fields." }
        action.arguments.forEach { (key, value) ->
            require(value.length <= if (key == "body") 8000 else 2000) { "Action argument is too long." }
            require(value.none { Character.isISOControl(it) && !(key == "body" && (it == '\n' || it == '\t')) }) {
                "Action argument contains unsupported control characters."
            }
            if (key !in optionalBlank[action.name].orEmpty()) {
                require(value.isNotBlank()) { "Required action arguments cannot be blank." }
            }
        }
        when (action.name) {
            "sendEmail" -> require(validEmail(action.arguments.getValue("to"))) { "Enter a valid recipient email address." }
            "createContact" -> {
                val fields = action.arguments
                require(fields.getValue("firstName").isNotBlank() || fields.getValue("lastName").isNotBlank()) {
                    "A contact needs a name."
                }
                val phone = fields.getValue("phoneNumber")
                val email = fields.getValue("email")
                require(phone.isNotBlank() || email.isNotBlank()) { "A contact needs a phone number or email address." }
                require(phone.isBlank() || (phoneFormat.matches(phone) && phone.count { it in '0'..'9' } in 3..15)) {
                    "Enter a valid contact phone number."
                }
                require(email.isBlank() || validEmail(email)) { "Enter a valid contact email address." }
            }
            "createCalendarEvent" -> eventTime(action.arguments.getValue("datetime"), ZoneId.systemDefault())
            "createTimer" -> {
                require(ActionInputValidation.parseDurationMillis(action.arguments.getValue("duration"))
                    ?.let(ActionInputValidation::timerLengthSeconds) != null) { "Use a timer duration from 1 second to 24 hours." }
                require(action.arguments.getValue("label").length <= 80) { "The label must be 80 characters or fewer." }
            }
            "createAlarm" -> {
                require(ActionInputValidation.parseLocalTime(action.arguments.getValue("time")) != null) { "Use a valid local alarm time in HH:mm format." }
                require(action.arguments.getValue("label").length <= 80) { "The label must be 80 characters or fewer." }
            }
            "callContact" -> require(action.arguments.getValue("contactName").length <= 100 &&
                action.arguments.getValue("phoneLabel").length <= 100) { "Provide the contact's saved display name." }
        }
    }

    private fun validEmail(value: String): Boolean = Patterns.EMAIL_ADDRESS.matcher(value).matches()

    private fun eventTime(datetime: String, zone: ZoneId): Long {
        val local = try {
            LocalDateTime.parse(datetime, localDateTimeFormat)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("Use a valid ISO local date and time.")
        }
        require(zone.rules.getValidOffsets(local).isNotEmpty()) { "This local time does not exist because of a clock change." }
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    /** Side-effect-free: schema/type validation plus corroboration against the RAW user request. */
    fun validateRequest(action: MobileAction, request: String, now: LocalDateTime = LocalDateTime.now()) =
        validateRequest(action, request, now, ZoneId.systemDefault())

    internal fun validateRequest(action: MobileAction, request: String, now: LocalDateTime, zone: ZoneId) {
        val snapshot = action.copy(arguments = action.arguments.toMap())
        validate(snapshot)
        ActionRequestPolicy.validate(snapshot, request, now, zone)
        if (snapshot.name == "showLocationOnMap") {
            require(ActionRequestPolicy.containsTokens(request, snapshot.arguments.getValue("location"))) { "The location must come from what you said." }
        }
    }

    fun requiredPermissions(action: MobileAction): Array<String> = when (action.name) {
        "turnOnFlashlight", "turnOffFlashlight" -> arrayOf(Manifest.permission.CAMERA)
        "createContact" -> arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        "createCalendarEvent" -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        "callContact" -> arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
        else -> emptyArray()
    }

    /** Intent for actions that are launched through another app; direct actions (flashlight, call, contact, calendar) return null. */
    fun intentFor(action: MobileAction): Intent? {
        val snapshot = action.copy(arguments = action.arguments.toMap())
        validate(snapshot)
        val fields = snapshot.arguments
        return when (snapshot.name) {
            "turnOnFlashlight", "turnOffFlashlight", "createContact", "createCalendarEvent", "callContact" -> null
            "createTimer" -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, ActionInputValidation.timerLengthSeconds(
                    requireNotNull(ActionInputValidation.parseDurationMillis(fields.getValue("duration")))))
                fields.getValue("label").trim().takeIf { it.isNotEmpty() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            "createAlarm" -> Intent(AlarmClock.ACTION_SET_ALARM).apply {
                val time = requireNotNull(ActionInputValidation.parseLocalTime(fields.getValue("time")))
                putExtra(AlarmClock.EXTRA_HOUR, time.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
                fields.getValue("label").trim().takeIf { it.isNotEmpty() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            // Android MailTo decodes before splitting query parameters; extras avoid injection.
            "sendEmail" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(fields.getValue("to"))}")).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(fields.getValue("to")))
                putExtra(Intent.EXTRA_SUBJECT, fields.getValue("subject"))
                putExtra(Intent.EXTRA_TEXT, fields.getValue("body"))
            }
            "showLocationOnMap" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(fields.getValue("location"))}"))
            "openWifiSettings" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            else -> throw IllegalArgumentException("Unsupported mobile action.")
        }
    }

    /** [request] is the RAW transcript; it is revalidated here so execution can never bypass policy. */
    fun execute(context: Context, action: MobileAction, request: String = "", now: LocalDateTime = LocalDateTime.now()): String {
        val snapshot = action.copy(arguments = action.arguments.toMap())
        val zone = ZoneId.systemDefault()
        try {
            validateRequest(snapshot, request, now, zone)
        } catch (e: IllegalArgumentException) {
            return "Action not performed: ${e.message ?: "check the request."}"
        }
        if (requiredPermissions(snapshot).any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            return "Action not performed: permission is missing or access is restricted."
        }
        return try {
            when (snapshot.name) {
                "turnOnFlashlight", "turnOffFlashlight" -> setFlashlight(context, snapshot.name == "turnOnFlashlight")
                "createContact" -> createContact(context, snapshot.arguments)
                "createCalendarEvent" -> createCalendarEvent(context, snapshot.arguments, zone)
                "callContact" -> callContact(context, snapshot.arguments)
                else -> {
                    val intent = requireNotNull(intentFor(snapshot))
                    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    when (snapshot.name) {
                        "sendEmail" -> "Email draft opened. Review it and send it yourself."
                        "createTimer" -> "Timer requested from Clock; not confirmed set."
                        "createAlarm" -> "Alarm requested from Clock; not confirmed set."
                        else -> "Requested screen opened."
                    }
                }
            }
        } catch (_: ActivityNotFoundException) {
            "No installed app can open this action."
        } catch (_: SecurityException) {
            "Action not performed: permission is missing or access is restricted."
        } catch (_: Exception) {
            "Action not performed: the device or app is unavailable. Try again."
        }
    }

    private const val UNCERTAIN = "may have been saved but could not be verified. Check the app; nothing was retried."

    @SuppressLint("MissingPermission")
    private fun callContact(context: Context, a: Map<String, String>): String {
        val wanted = ActionRequestPolicy.tokens(a.getValue("contactName")).joinToString(" ")
        val contacts = mutableListOf<ContactNameCandidate>()
        val cursor = context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
            ?: return "Call not placed: contacts could not be read."
        cursor.use { while (it.moveToNext()) it.getString(1)?.let { n -> contacts += ContactNameCandidate(it.getLong(0), n) } }
        // Whole-name token match only: "John" never matches "John Smith" or the reverse.
        val matches = contacts.filter { ActionRequestPolicy.tokens(it.displayName).joinToString(" ") == wanted }
        val contact = matches.singleOrNull()
            ?: return if (matches.isEmpty()) "Call not placed: no saved contact matches that name." else "Call not placed: more than one saved contact matches that name."
        val numbers = mutableListOf<ContactPhoneCandidate>()
        val phones = context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contact.id.toString()), null)
            ?: return "Call not placed: phone numbers could not be read."
        phones.use {
            while (it.moveToNext()) {
                val number = it.getString(0) ?: continue
                val label = when (it.getInt(1)) {
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                    ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                    ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
                    ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "other"
                    else -> it.getString(2).orEmpty()
                }
                numbers += ContactPhoneCandidate(number, ActionRequestPolicy.canonicalLabel(label))
            }
        }
        // Distinct numbers only: the same number saved twice is not ambiguous.
        val unique = numbers.distinctBy { ContactCallResolver.normalizePhoneNumber(it.number) to it.label }
        val label = ActionRequestPolicy.canonicalLabel(a.getValue("phoneLabel"))
        val chosen = when (val m = ContactCallResolver.choosePhone("the contact", label, unique)) {
            is PhoneMatch.Found -> m.number
            is PhoneMatch.Failure -> return "Call not placed: ${m.message}"
        }
        if (label.isNotEmpty() && unique.count { it.label == label && ContactCallResolver.normalizePhoneNumber(it.number) != ContactCallResolver.normalizePhoneNumber(chosen.number) } > 0) {
            return "Call not placed: more than one number has that label."
        }
        val dial = ContactCallResolver.normalizePhoneNumber(chosen.number) ?: return "Call not placed: the saved number is not a safe phone number."
        if (PhoneNumberUtils.isEmergencyNumber(dial.removePrefix("+"))) return "Call not placed: emergency numbers are not allowed."
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return "Call not placed: calling is unavailable."
        val account = cellularAccount(telecom) ?: return "Call not placed: no single cellular calling account is available."
        telecom.placeCall(Uri.fromParts("tel", dial, null), Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account) })
        return "Call requested to ${contact.displayName}; not confirmed connected."
    }

    @SuppressLint("MissingPermission")
    private fun cellularAccount(telecom: TelecomManager): PhoneAccountHandle? {
        val default = telecom.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)?.let(telecom::getPhoneAccount)
        return chooseCellularAccount(default, telecom.callCapablePhoneAccounts.mapNotNull(telecom::getPhoneAccount))
    }

    internal fun chooseCellularAccount(default: PhoneAccount?, accounts: List<PhoneAccount>): PhoneAccountHandle? {
        // Telecom supplies only enabled call-capable accounts; don't trust a stale default outside that list.
        val cellular = accounts.filter { it.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) }
        if (default != null && cellular.any { it.accountHandle == default.accountHandle }) return default.accountHandle
        return cellular.singleOrNull()?.accountHandle
    }

    private fun createContact(context: Context, a: Map<String, String>): String {
        val first = a.getValue("firstName").trim(); val last = a.getValue("lastName").trim()
        val phone = a.getValue("phoneNumber").trim(); val email = a.getValue("email").trim()
        val key = ActionRequestPolicy.nameKey("$first $last")
        val resolver = context.contentResolver
        val existing = resolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
            ?: return "No contact saved: existing contacts could not be read."
        val duplicate = existing.use { c ->
            var found = false
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                if (ActionRequestPolicy.nameKey(name) == key) { found = true; break }
            }
            found
        }
        if (duplicate) return "No contact saved: a contact with that name already exists."
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, first)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, last).build(),
        )
        if (phone.isNotEmpty()) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_OTHER).build()
        if (email.isNotEmpty()) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
            .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER).build()
        val rawId = try {
            // Any exception from here on means the batch may have committed: never retry or delete.
            val uri = resolver.applyBatch(ContactsContract.AUTHORITY, ops).firstOrNull()?.uri
                ?: return "Contact $UNCERTAIN"
            ContentUris.parseId(uri)
        } catch (_: Exception) {
            return "Contact $UNCERTAIN"
        }
        val verified = try {
            verifyContact(resolver, rawId, first, last, phone, email)
        } catch (_: Exception) {
            return "Contact $UNCERTAIN"
        }
        return if (verified) "Contact saved on this device (no account)." else "Contact $UNCERTAIN"
    }

    private fun verifyContact(resolver: android.content.ContentResolver, rawId: Long, first: String, last: String, phone: String, email: String): Boolean {
        val rawOk = resolver.query(ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.DELETED),
            "${ContactsContract.RawContacts._ID} = ?", arrayOf(rawId.toString()), null)?.use {
            it.moveToFirst() && it.getString(0) == null && it.getString(1) == null && it.getInt(2) == 0
        } == true
        if (!rawOk) return false
        val rows = mutableListOf<List<String?>>()
        resolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(rawId.toString()), null)?.use {
            while (it.moveToNext()) rows += listOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3))
        } ?: return false
        val expected = mutableListOf<(List<String?>) -> Boolean>()
        expected += { r -> r[0] == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE && (r[2] ?: "") == first && (r[3] ?: "") == last }
        if (phone.isNotEmpty()) expected += { r -> r[0] == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE && r[1] == phone }
        if (email.isNotEmpty()) expected += { r -> r[0] == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE && r[1] == email }
        return rows.size == expected.size && expected.all { check -> rows.count(check) == 1 }
    }

    private const val EVENT_MS = 15 * 60 * 1000L

    private fun createCalendarEvent(context: Context, a: Map<String, String>, zone: ZoneId): String {
        val start = requireNotNull(ActionInputValidation.toEpochMillis(LocalDateTime.parse(a.getValue("datetime")), zone))
        val end = start + EVENT_MS
        val title = a.getValue("title").trim()
        val resolver = context.contentResolver
        val calendars = mutableListOf<Triple<Long, String, Boolean>>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), "${CalendarContract.Calendars._ID} ASC")?.use {
            while (it.moveToNext()) calendars += Triple(it.getLong(0), it.getString(1).orEmpty(), it.getInt(2) != 0)
        } ?: return "No event saved: calendars could not be read."
        val calendar = calendars.filter { it.third }.singleOrNull()
            ?: return "No event saved: exactly one writable visible primary calendar is required."
        val duplicate = resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} = ? AND ${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(calendar.first.toString(), start.toString(), title), null)?.use { it.count > 0 }
            ?: return "No event saved: existing events could not be checked."
        if (duplicate) return "No event saved: the same event already exists."
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar.first)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
        }
        val uncertain = "Event $UNCERTAIN"
        // Single insert, never retried; any exception after this point may mean it was written.
        val id = try {
            ContentUris.parseId(resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return "No event saved: Calendar did not create it.")
        } catch (_: Exception) {
            return uncertain
        }
        val verified = try {
            resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.TITLE),
                null, null, null)?.use {
                it.moveToFirst() && it.getLong(0) == calendar.first && it.getLong(1) == start && it.getLong(2) == end && it.getString(3) == title
            } == true
        } catch (_: Exception) {
            return uncertain
        }
        return if (verified) "Calendar event '$title' saved." else uncertain
    }

    @SuppressLint("MissingPermission")
    private fun setFlashlight(context: Context, enabled: Boolean): String {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return "Flashlight is unavailable on this device."
        val rearFlash = manager.cameraIdList.firstOrNull { id ->
            val camera = manager.getCameraCharacteristics(id)
            camera.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                camera.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "No rear flashlight is available on this device."
        manager.setTorchMode(rearFlash, enabled)
        return if (enabled) "Flashlight turned on." else "Flashlight turned off."
    }
}
