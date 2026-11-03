@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.galaxyrio.gracelauncher.magic

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.data.SearchContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** Current-user standard Phone rows only; absence is an exact-provider conversations-only fallback. */
internal class ProviderSyncedContacts(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private fun granted() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun installed(pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    }.getOrDefault(false)

    fun observe(pkg: String) = callbackFlow {
        if (!granted() || !installed(pkg)) { trySend(Unit); close(); return@callbackFlow }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate().mapLatest { load(pkg) }.catch { emit(emptyList()) }

    private suspend fun load(pkg: String): List<SearchContact> = withContext(Dispatchers.IO) {
        if (!granted() || !installed(pkg)) return@withContext emptyList()
        // AccountManager.getAuthenticatorTypes requires no account-list permission and uses this context's user.
        val metadata = runCatching { AccountManager.get(context).authenticatorTypes.map {
            ProviderAuthenticator(it.packageName, it.type)
        } }.getOrNull()
        val types = providerAccountTypes(pkg, installed(pkg), granted(), metadata)
        if (types.isEmpty()) return@withContext emptyList()
        // Resolve account ownership on RawContacts, then query ONLY Phone rows for those raw IDs.
        // Phone.RAW_CONTACT_ID references RawContacts._ID; aggregate IDs never join numbers.
        val rawAccounts = linkedMapOf<Long, String>()
        resolver.query(ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} IN (${types.joinToString(",") { "?" }})",
            types.toTypedArray(), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val type = cursor.getString(1).orEmpty()
                if (type in types) rawAccounts[cursor.getLong(0)] = type
            }
        }
        val rows = mutableListOf<ProviderPhoneRow>()
        for (ids in rawAccounts.keys.chunked(500)) {
            currentCoroutineContext().ensureActive()
            if (!granted() || !installed(pkg)) return@withContext emptyList()
            resolver.query(Phone.CONTENT_URI, arrayOf(Phone.RAW_CONTACT_ID, Phone.NUMBER, Phone.DISPLAY_NAME_PRIMARY),
                "${Phone.RAW_CONTACT_ID} IN (${ids.joinToString(",") { "?" }})",
                ids.map(Long::toString).toTypedArray(), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getLong(0)
                    val type = rawAccounts[id] ?: continue
                    rows += ProviderPhoneRow(id, type, cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
                }
            }
        }
        if (!granted() || !installed(pkg)) return@withContext emptyList()
        verifiedProviderPhones(types, rows).map { row ->
            SearchContact(row.rawContactId, ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, row.rawContactId),
                row.name.ifBlank { context.getString(R.string.search_unnamed_contact) }, null,
                listOf(row.number.filter(Char::isDigit)), listOf(row.number))
        }
    }
}
