package com.galaxyrio.gracelauncher.magic

internal fun strictContactProvider(packageName: String?): String? =
    packageName?.takeIf { it == "com.whatsapp" || it == "com.whatsapp.w4b" }

internal data class ProviderAuthenticator(val packageName: String, val accountType: String)
internal data class ProviderPhoneRow(val rawContactId: Long, val accountType: String, val number: String, val name: String)

/** Account types come only from the selected package's platform authenticator metadata. */
internal fun providerAccountTypes(packageName: String, installed: Boolean, permission: Boolean,
    authenticators: List<ProviderAuthenticator>?): Set<String> {
    if (strictContactProvider(packageName) == null || !installed || !permission) return emptySet()
    return authenticators.orEmpty().filter { it.accountType.isNotBlank() }.groupBy { it.accountType }
        .filterValues { owners -> owners.all { it.packageName == packageName } }.keys
}

/** Never join numbers through an aggregate contact ID or use a name as membership evidence. */
internal fun verifiedProviderPhones(types: Set<String>, rows: List<ProviderPhoneRow>): List<ProviderPhoneRow> =
    rows.filter { it.accountType in types && it.rawContactId > 0 && it.number.any(Char::isDigit) }
        .distinctBy { it.rawContactId to it.number.filter(Char::isDigit) }

internal fun allowRawMagicRecipient(kind: MagicKind, provider: String?) =
    kind != MagicKind.Message || strictContactProvider(provider) == null

internal data class MagicContactScope(val provider: String?, val granted: Boolean, val installed: Boolean)
