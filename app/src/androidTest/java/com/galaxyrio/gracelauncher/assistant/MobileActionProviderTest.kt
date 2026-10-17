package com.galaxyrio.gracelauncher.assistant

import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

/** In-process, unregistered provider. No real contacts, calendars, calls, accounts or permissions are changed. */
class MobileActionProviderTest {
    private val now = LocalDateTime.of(2026, 7, 1, 9, 0)
    private val contact = MobileAction("createContact", mapOf("firstName" to "Ada", "lastName" to "Lovelace", "phoneNumber" to "+442012345678", "email" to "ada@example.com"))
    private val contactRequest = "create contact Ada Lovelace phone +442012345678 email ada@example.com"
    private val event = MobileAction("createCalendarEvent", mapOf("datetime" to "2026-07-01T15:00:00", "title" to "Dentist"))
    private val eventRequest = "add calendar event Dentist at 3 pm"

    private fun context(provider: FakeProvider, granted: Boolean = true): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        provider.attachInfo(base, ProviderInfo().apply { authority = "${ContactsContract.AUTHORITY};${CalendarContract.AUTHORITY}"; exported = false })
        val resolver = ContentResolver.wrap(provider)
        return object : ContextWrapper(base) {
            override fun getContentResolver() = resolver
            override fun checkSelfPermission(permission: String) = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            override fun getSystemService(name: String): Any? = null // Never reach a real TelecomManager.
        }
    }

    @Test fun contactBatchIsLocalAtomicAndVerifiedWithoutTouchingRealProvider() {
        val p = FakeProvider()
        assertTrue(MobileActionExecutor.execute(context(p), contact, contactRequest, now).startsWith("Contact saved"))
        assertEquals(1, p.batches)
        assertEquals(4, p.writes)
        assertNull(p.raw.getAsString(ContactsContract.RawContacts.ACCOUNT_NAME))
        assertNull(p.raw.getAsString(ContactsContract.RawContacts.ACCOUNT_TYPE))
        assertEquals(3, p.data.size)
    }

    @Test fun existingNameAfterNullRowRejectsContactWithoutWriting() {
        val p = FakeProvider().apply { names += null; names += "Lovelace Ada" }
        assertTrue(MobileActionExecutor.execute(context(p), contact, contactRequest, now).contains("already exists"))
        assertEquals(0, p.writes)
    }

    @Test fun contactReadbackFailureAndBatchExceptionAreUncertainAndNeverRetried() {
        for (throwWrite in listOf(false, true)) {
            val p = FakeProvider().apply { brokenReadback = true; throwOnWrite = throwWrite }
            assertTrue(MobileActionExecutor.execute(context(p), contact, contactRequest, now).contains("may have been saved"))
            assertEquals(1, p.batches)
            assertEquals(0, p.deletes)
        }
    }

    @Test fun eventUsesSinglePrimaryFifteenMinutesAndReadback() {
        val p = FakeProvider()
        assertTrue(MobileActionExecutor.execute(context(p), event, eventRequest, now).startsWith("Calendar event"))
        assertEquals(1, p.writes)
        assertEquals(900000L, p.event.getAsLong(CalendarContract.Events.DTEND) - p.event.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(1L, p.event.getAsLong(CalendarContract.Events.CALENDAR_ID))
    }

    @Test fun ambiguousPrimaryOrDuplicateEventNeverWrites() {
        for (primaries in listOf(0, 2)) {
            val p = FakeProvider().apply { primaryCount = primaries }
            assertTrue(MobileActionExecutor.execute(context(p), event, eventRequest, now).contains("exactly one"))
            assertEquals(0, p.writes)
        }
        val p = FakeProvider().apply { eventExists = true }
        assertTrue(MobileActionExecutor.execute(context(p), event, eventRequest, now).contains("already exists"))
        assertEquals(0, p.writes)
    }

    @Test fun eventReadbackOrInsertExceptionNeverClaimsFailureBeforePossibleWrite() {
        for (throwWrite in listOf(false, true)) {
            val p = FakeProvider().apply { brokenReadback = true; throwOnWrite = throwWrite }
            assertTrue(MobileActionExecutor.execute(context(p), event, eventRequest, now).contains("may have been saved"))
            assertEquals(1, p.writes)
            assertEquals(0, p.deletes)
        }
    }

    @Test fun permissionDenialAndUnrelatedRequestNeverAcquireProvider() {
        val p = FakeProvider()
        assertTrue(MobileActionExecutor.execute(context(p, false), contact, contactRequest, now).contains("permission"))
        assertEquals(0, p.queries)
        assertEquals(0, p.writes)
        assertTrue(MobileActionExecutor.execute(context(FakeProvider()), contact, "turn on airplane mode", now).startsWith("Action not performed"))
    }

    @Test fun ambiguousContactOrNumbersAndMissingAccountNeverPlaceCall() {
        val call = MobileAction("callContact", mapOf("contactName" to "John Smith", "phoneLabel" to ""))
        val duplicate = FakeProvider().apply { names += "John Smith"; names += "John Smith" }
        assertTrue(MobileActionExecutor.execute(context(duplicate), call, "call John Smith", now).contains("more than one saved contact"))
        val numbers = FakeProvider().apply { names += "John Smith"; phones += "02012345678"; phones += "02087654321" }
        assertTrue(MobileActionExecutor.execute(context(numbers), call, "call John Smith", now).contains("multiple phone numbers"))
        val noAccount = FakeProvider().apply { names += "John Smith"; phones += "02012345678" }
        assertTrue(MobileActionExecutor.execute(context(noAccount), call, "call John Smith", now).contains("calling is unavailable"))
        assertEquals(0, noAccount.writes)
    }

    @Test fun cellularAccountMustBeUniqueOrAnEnabledDefaultInTheList() {
        fun account(id: String, sim: Boolean) = PhoneAccount.builder(PhoneAccountHandle(ComponentName("test", "test.CallService"), id), id)
            .setCapabilities(if (sim) PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION else PhoneAccount.CAPABILITY_CALL_PROVIDER).build()
        val one = account("one", true); val two = account("two", true); val voip = account("voip", false)
        assertEquals(one.accountHandle, MobileActionExecutor.chooseCellularAccount(null, listOf(one, voip)))
        assertNull(MobileActionExecutor.chooseCellularAccount(null, listOf(one, two)))
        assertEquals(two.accountHandle, MobileActionExecutor.chooseCellularAccount(two, listOf(one, two)))
        assertNull(MobileActionExecutor.chooseCellularAccount(one, emptyList()))
        assertNull(MobileActionExecutor.chooseCellularAccount(voip, listOf(voip)))
    }

    private class FakeProvider : ContentProvider() {
        val names = mutableListOf<String?>()
        val phones = mutableListOf<String>()
        val data = mutableListOf<ContentValues>()
        var raw = ContentValues()
        var event = ContentValues()
        var primaryCount = 1
        var eventExists = false
        var brokenReadback = false
        var throwOnWrite = false
        var batches = 0; var writes = 0; var queries = 0; var deletes = 0
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Never update")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deletes++; error("Never delete") }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            batches++
            return super.applyBatch(operations)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            writes++
            if (throwOnWrite) throw SecurityException("synthetic ambiguous provider response")
            val v = ContentValues(requireNotNull(values))
            when (uri.pathSegments.first()) {
                "raw_contacts" -> raw = v
                "data" -> data += v
                "events" -> event = v
                else -> error("Unexpected insert")
            }
            return ContentUris.withAppendedId(uri, 42L)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            val columns = requireNotNull(projection)
            val cursor = MatrixCursor(columns)
            fun add(values: Map<String, Any?>) { cursor.addRow(columns.map { values[it] }.toTypedArray()) }
            when (uri.pathSegments.first()) {
                "contacts" -> names.forEachIndexed { i, n -> add(mapOf(ContactsContract.Contacts._ID to i + 1L, ContactsContract.Contacts.DISPLAY_NAME to n)) }
                "data" -> {
                    if (uri.pathSegments.getOrNull(1) == "phones") phones.forEach { n -> add(mapOf(ContactsContract.CommonDataKinds.Phone.NUMBER to n, ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)) }
                    else data.forEach { row -> add(columns.associateWith { row[it] }) }
                }
                "raw_contacts" -> add(mapOf(ContactsContract.RawContacts.ACCOUNT_TYPE to if (brokenReadback) "wrong" else null, ContactsContract.RawContacts.ACCOUNT_NAME to null, ContactsContract.RawContacts.DELETED to 0))
                "calendars" -> repeat(primaryCount) { add(mapOf(CalendarContract.Calendars._ID to it + 1L, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to "Test", CalendarContract.Calendars.IS_PRIMARY to 1)) }
                "events" -> if (uri.pathSegments.size > 1) add(columns.associateWith { key -> if (brokenReadback && key == CalendarContract.Events.DTSTART) 0L else event[key] })
                    else if (eventExists) add(mapOf(CalendarContract.Events._ID to 42L))
                else -> error("Unexpected query")
            }
            return cursor
        }
    }
}
