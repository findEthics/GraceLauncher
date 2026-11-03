package com.katoaapps.openminilaunch

import com.katoaapps.openminilaunch.features.messaging.resolveRecentConversationPackages
import org.junit.Assert.*
import org.junit.Test

class RecentConversationScopeTest {
    private val wa = "com.whatsapp"
    private val business = "com.whatsapp.w4b"
    @Test fun strictConsumerAndBusinessScopesIgnoreAutomaticSwitch() {
        for (automatic in listOf(false, true)) for (pkg in listOf(wa, business)) {
            assertEquals(setOf(pkg), resolveRecentConversationPackages(automatic, pkg, "sms.app") { true })
        }
    }
    @Test fun unavailableSelectedPackageNeverFallsBackToSmsOrSibling() {
        for (automatic in listOf(false, true)) for (pkg in listOf(wa, business)) {
            assertTrue(resolveRecentConversationPackages(automatic, pkg, "sms.app") { it != pkg }.isEmpty())
        }
    }
    @Test fun ordinaryManualDiscoveryAndAutomaticSmsStayUnchanged() {
        assertEquals(setOf("sms.app"), resolveRecentConversationPackages(true, null, "sms.app") { true })
        assertEquals(setOf("sms.app", wa), resolveRecentConversationPackages(false, null, "sms.app") { it in setOf("sms.app", wa) })
    }
}
