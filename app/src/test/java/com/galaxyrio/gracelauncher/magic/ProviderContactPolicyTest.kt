package com.galaxyrio.gracelauncher.magic

import androidx.compose.ui.text.input.TextFieldValue
import com.katoaapps.openminilaunch.model.CommunicationRecipient
import org.junit.Assert.*
import org.junit.Test

class ProviderContactPolicyTest {
    private val wa = "com.whatsapp"
    private val business = "com.whatsapp.w4b"
    private val metadata = listOf(ProviderAuthenticator(wa, "wa-type"),
        ProviderAuthenticator(business, "business-type"), ProviderAuthenticator("other", "other-type"))
    @Test fun exactAuthenticatorPackageSeparatesConsumerAndBusiness() {
        assertEquals(setOf("wa-type"), providerAccountTypes(wa, true, true, metadata))
        assertEquals(setOf("business-type"), providerAccountTypes(business, true, true, metadata))
        assertTrue(providerAccountTypes("com.whatsapp.fake", true, true, metadata).isEmpty())
    }
    @Test fun conflictingAuthenticatorOwnershipFailsClosed() {
        assertTrue(providerAccountTypes(wa, true, true,
            listOf(ProviderAuthenticator(wa, "shared"), ProviderAuthenticator(business, "shared"))).isEmpty())
    }
    @Test fun unavailableMetadataDeniedPermissionAndAbsentPackageFailClosed() {
        assertTrue(providerAccountTypes(wa, true, true, null).isEmpty())
        assertTrue(providerAccountTypes(wa, true, true, emptyList()).isEmpty())
        assertTrue(providerAccountTypes(wa, false, true, metadata).isEmpty())
        assertTrue(providerAccountTypes(wa, true, false, metadata).isEmpty())
        assertTrue(providerAccountTypes(wa, true, true, listOf(ProviderAuthenticator(wa, ""))).isEmpty())
    }
    @Test fun onlyOwnedRawPhoneRowsSurviveEvenWithSameNameOrNumber() {
        val owned = ProviderPhoneRow(1, "wa-type", "+15550001", "Same fixture")
        val otherNumber = owned.copy(rawContactId = 2, accountType = "other-type", number = "+15550002")
        val businessRow = owned.copy(rawContactId = 3, accountType = "business-type")
        val ownedSecond = owned.copy(number = "+15550003")
        assertEquals(listOf(owned, ownedSecond), verifiedProviderPhones(setOf("wa-type"),
            listOf(owned, otherNumber, businessRow, ownedSecond, owned)))
        assertEquals(listOf(businessRow), verifiedProviderPhones(setOf("business-type"), listOf(owned, businessRow)))
        assertTrue(verifiedProviderPhones(emptySet(), listOf(owned)).isEmpty())
    }
    @Test fun malformedPhoneRowsDoNotBecomeRecipients() {
        assertTrue(verifiedProviderPhones(setOf("wa-type"), listOf(
            ProviderPhoneRow(0, "wa-type", "1555", "Fixture"),
            ProviderPhoneRow(1, "wa-type", "", "Fixture"))).isEmpty())
    }
    @Test fun strictMessagingNeverAddsTypedRecipientButCallAndOtherProvidersKeepIt() {
        for (pkg in listOf(wa, business)) assertFalse(allowRawMagicRecipient(MagicKind.Message, pkg))
        assertTrue(allowRawMagicRecipient(MagicKind.Call, wa))
        assertTrue(allowRawMagicRecipient(MagicKind.Message, null))
        assertTrue(allowRawMagicRecipient(MagicKind.Message, "other"))
    }
    @Test fun providerOrPermissionOrAvailabilityChangeDiscardsSelectionsAndPendingClaim() {
        val initial = MagicContactScope(wa, true, true)
        for (changed in listOf(initial.copy(provider = business), initial.copy(granted = false), initial.copy(installed = false))) {
            val session = MagicSession(1, MagicKind.Message)
            session.updateContactScope(initial)
            session.choose(CommunicationRecipient("15550001", "Fixture", "15550001"), wa, true)
            session.input = TextFieldValue("Draft fixture")
            val pending = checkNotNull(session.requestSms())
            session.updateContactScope(changed)
            assertNull(session.recipient); assertNull(session.conversation); assertNull(session.providerPackage)
            assertEquals("", session.input.text); assertFalse(session.claimSms(pending))
        }
    }
    @Test fun contactPermissionChangesDoNotClearCalendarDraft() {
        val session = MagicSession(1, MagicKind.Event)
        session.updateContactScope(MagicContactScope(null, true, true))
        session.input = TextFieldValue("Calendar fixture")
        assertFalse(session.updateContactScope(MagicContactScope(null, false, true)))
        assertEquals("Calendar fixture", session.input.text)
    }
    @Test fun unchangedScopeRetainsDraft() {
        val session = MagicSession(1, MagicKind.Message)
        val scope = MagicContactScope(wa, true, true)
        session.updateContactScope(scope)
        session.input = TextFieldValue("Retained fixture")
        session.updateContactScope(scope)
        assertEquals("Retained fixture", session.input.text)
    }
}
