package com.galaxyrio.gracelauncher.assistant

import android.annotation.SuppressLint
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
    )
    private val localDateTimeFormat = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)
    private val phoneFormat = Regex("\\+?[0-9() .-]+")

    fun validate(action: MobileAction) {
        val keys = parameters[action.name] ?: throw IllegalArgumentException("Unsupported mobile action.")
        require(action.arguments.keys == keys) { "Action arguments do not match the required fields." }
        action.arguments.forEach { (key, value) ->
            require(value.length <= if (key == "body") 8000 else 2000) { "Action argument is too long." }
            require(value.none { Character.isISOControl(it) && !(key == "body" && (it == '\n' || it == '\t')) }) {
                "Action argument contains unsupported control characters."
            }
            if (action.name != "createContact") {
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

    fun intentFor(action: MobileAction): Intent? {
        val snapshot = action.copy(arguments = action.arguments.toMap())
        validate(snapshot)
        val fields = snapshot.arguments
        return when (snapshot.name) {
            "turnOnFlashlight", "turnOffFlashlight" -> null
            "createContact" -> Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.NAME,
                    listOf(fields.getValue("firstName"), fields.getValue("lastName")).filter { it.isNotBlank() }.joinToString(" "))
                fields.getValue("phoneNumber").takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                fields.getValue("email").takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            }
            // Android MailTo decodes before splitting query parameters; extras avoid injection.
            "sendEmail" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(fields.getValue("to"))}")).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(fields.getValue("to")))
                putExtra(Intent.EXTRA_SUBJECT, fields.getValue("subject"))
                putExtra(Intent.EXTRA_TEXT, fields.getValue("body"))
            }
            "showLocationOnMap" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(fields.getValue("location"))}"))
            "openWifiSettings" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "createCalendarEvent" -> {
                val zone = ZoneId.systemDefault()
                Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                    putExtra(CalendarContract.Events.TITLE, fields.getValue("title"))
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, eventTime(fields.getValue("datetime"), zone))
                    putExtra(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                }
            }
            else -> throw IllegalArgumentException("Unsupported mobile action.")
        }
    }

    fun execute(context: Context, action: MobileAction): String {
        val snapshot = action.copy(arguments = action.arguments.toMap())
        try {
            validate(snapshot)
        } catch (_: IllegalArgumentException) {
            return "Action not performed: check the requested fields and date/time."
        }
        return try {
            if (snapshot.needsCamera) {
                setFlashlight(context, snapshot.name == "turnOnFlashlight")
            } else {
                val intent = requireNotNull(intentFor(snapshot))
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                when (snapshot.name) {
                    "sendEmail" -> "Email draft opened. Review it and send it yourself."
                    "createContact" -> "Contact draft opened. Review it and save it yourself."
                    "createCalendarEvent" -> "Calendar draft opened. Review it and save it yourself."
                    else -> "Requested screen opened."
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
