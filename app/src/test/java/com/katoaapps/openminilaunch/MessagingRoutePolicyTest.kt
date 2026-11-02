package com.katoaapps.openminilaunch

// Focused adaptation of upstream openminilaunch MessagingBehaviorTest (pinned b9b26f7).
import com.katoaapps.openminilaunch.features.messaging.MessagingSendRoute
import com.katoaapps.openminilaunch.features.messaging.messagingSendRoute
import com.katoaapps.openminilaunch.features.messaging.resolvedMessageSendRoute
import com.katoaapps.openminilaunch.model.userEnteredRecipient
import org.junit.Assert.assertEquals
import org.junit.Test

class MessagingRoutePolicyTest {
    @Test fun carrierSmsIsOptInAndManualModeKeepsProviderPicker() {
        assertEquals(MessagingSendRoute.PROVIDER_PICKER, messagingSendRoute(false, null))
        assertEquals(MessagingSendRoute.PROVIDER_PICKER, messagingSendRoute(false, "com.example.provider"))
        assertEquals(MessagingSendRoute.DIRECT_SMS, messagingSendRoute(true, null))
        assertEquals(MessagingSendRoute.PREFERRED_DRAFT, messagingSendRoute(true, "com.example.provider"))
    }

    @Test fun ordinaryUsernameRemainsProviderOwnedEvenWhenAutomaticSmsIsEnabled() {
        assertEquals(
            MessagingSendRoute.PREFERRED_DRAFT,
            resolvedMessageSendRoute(
                recipient = userEnteredRecipient("alice@example.test"),
                selectedConversationPackage = null,
                defaultSmsPackage = null,
                sendAutomatically = true,
                preferredPackage = null,
                fallbackRoute = MessagingSendRoute.DIRECT_SMS,
            ),
        )
    }

    @Test fun phoneRecipientUsesPickerWhenAutomaticSmsIsOff() {
        assertEquals(
            MessagingSendRoute.PROVIDER_PICKER,
            resolvedMessageSendRoute(
                recipient = userEnteredRecipient("+1 (555) 123-4567"),
                selectedConversationPackage = null,
                defaultSmsPackage = null,
                sendAutomatically = false,
                preferredPackage = null,
                fallbackRoute = MessagingSendRoute.PROVIDER_PICKER,
            ),
        )
    }
}
