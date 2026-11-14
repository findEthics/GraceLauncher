package com.galaxyrio.gracelauncher.magic

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.katoaapps.openminilaunch.features.messaging.*
import com.katoaapps.openminilaunch.model.CommunicationRecipient
import org.junit.Assert.*
import org.junit.Test

/** Captures intents only: never launches a messaging provider or sends a message. */
class MagicEmptyMessageIntentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val recipient = CommunicationRecipient("+15551234567", "Fixture", "+15551234567")

    @Test fun emptyBodyPreservesProviderNumberRoutes() {
        val factory = MessagingIntentFactory(context)
        for (pkg in listOf("com.whatsapp", "org.telegram.messenger", "com.enflick.android.TextNow")) {
            val route = factory.routes(MessagingProviderCatalog.providerForPackage(pkg)!!, recipient.address, "").first()
            assertEquals(pkg, route.intent.`package`)
            assertTrue(route.carriesRecipient)
            assertTrue(route.intent.data.toString().contains("15551234567"))
        }
        val signalRoutes = factory.routes(MessagingProviderCatalog.providerForPackage("org.thoughtcrime.securesms")!!, recipient.address, "")
        assertTrue(signalRoutes.all { it.intent.`package` == "org.thoughtcrime.securesms" })
        assertFalse(signalRoutes.last().carriesRecipient)
    }

    @Test fun defaultEmptyDraftAddressesContactButNeverDirectSends() {
        val intents = mutableListOf<Intent>()
        val actions = MessagingDeviceActions(context, { it }) { intent, _ -> intents.add(intent); true }
        assertEquals(PreferredMessageDraftResult.OPENED, actions.openPreferredMessageDraft(recipient, "", null))
        assertEquals(1, intents.size)
        assertEquals(Intent.ACTION_SENDTO, intents.single().action)
        assertEquals("smsto", intents.single().data!!.scheme)
        assertEquals(recipient.address, intents.single().data!!.schemeSpecificPart)
        assertEquals("", intents.single().getStringExtra("sms_body"))
    }

    @Test fun emptyUnsupportedRouteUsesOnlySelectedAppsLaunchScreenAndMissingStrictProviderFailsClosed() {
        val intents = mutableListOf<Intent>()
        val actions = MessagingDeviceActions(context, { it }) { intent, _ -> intents.add(intent); true }
        assertEquals(PreferredMessageDraftResult.OPENED_WITH_RECIPIENT_PICKER,
            actions.openPreferredMessageDraft(recipient, "", context.packageName, allowSmsFallback = false))
        assertEquals(context.packageName, intents.single().component!!.packageName)
        intents.clear()
        assertEquals(PreferredMessageDraftResult.FAILED,
            actions.openPreferredMessageDraft(recipient, "", "invalid.missing.provider", allowSmsFallback = false))
        assertTrue(intents.isEmpty())
    }
}
