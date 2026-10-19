/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.galaxyrio.gracelauncher.assistant

import android.content.Context
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

// Adapted from Google AI Edge Gallery's MobileActionsTools.kt.
data class MobileAction(val name: String, val arguments: Map<String, String>) {
    val needsCamera: Boolean
        get() = name == "turnOnFlashlight" || name == "turnOffFlashlight"

    /** [request] is the RAW user transcript; empty fails safely. */
    fun execute(context: Context, request: String = ""): String = MobileActionExecutor.execute(context, this, request)

    companion object {
        fun fromToolCall(name: String, arguments: Map<String, Any?>): MobileAction {
            val strings = MobileActionExecutor.applySchemaDefaults(name, arguments).mapValues { (_, value) ->
                require(value is String) { "Action arguments must be strings." }
                value
            }
            return MobileAction(name, strings).also { MobileActionExecutor.validate(it) }
        }
    }
}

class MobileActionTools(private val onAction: (MobileAction) -> Unit) : ToolSet {
    private fun queue(name: String, arguments: Map<String, String> = emptyMap()): Map<String, String> {
        // ponytail: queue only; the caller validates against the raw request and executes.
        onAction(MobileAction(name, arguments))
        return mapOf("result" to "queued")
    }

    @Tool(description = "Turns the flashlight on")
    fun turnOnFlashlight(): Map<String, String> = queue("turnOnFlashlight")

    @Tool(description = "Turns the flashlight off")
    fun turnOffFlashlight(): Map<String, String> = queue("turnOffFlashlight")

    @Tool(description = "Starts a timer of the given duration, e.g. '5 minutes' or '1 hour 30 minutes'.")
    fun createTimer(
        @ToolParam(description = "The spoken duration, e.g. '10 minutes'.") duration: String,
        @ToolParam(description = "An optional label, or empty.") label: String = "",
    ): Map<String, String> = queue("createTimer", mapOf("duration" to duration, "label" to label))

    @Tool(description = "Calls a saved contact by their exact saved display name.")
    fun callContact(
        @ToolParam(description = "The saved display name exactly as spoken.") contactName: String,
        @ToolParam(description = "The spoken phone label such as mobile, home or work, or empty.") phoneLabel: String = "",
    ): Map<String, String> = queue("callContact", mapOf("contactName" to contactName, "phoneLabel" to phoneLabel))

    @Tool(description = "Saves a new contact on this device using only details the user said.")
    fun createContact(
        @ToolParam(description = "The provided first name of the contact. Required; never omit the provided name.") firstName: String,
        @ToolParam(description = "The last name of the contact.") lastName: String = "",
        @ToolParam(description = "The phone number of the contact.") phoneNumber: String = "",
        @ToolParam(description = "The email address of the contact.") email: String = "",
    ): Map<String, String> = queue("createContact", mapOf(
        "firstName" to firstName, "lastName" to lastName, "phoneNumber" to phoneNumber, "email" to email,
    ))

    @Tool(description = "Opens an email draft for the user to review and send. Does not send automatically.")
    fun sendEmail(
        @ToolParam(description = "The email address of the recipient.") to: String,
        @ToolParam(description = "The subject of the email.") subject: String,
        @ToolParam(description = "The body of the email.") body: String,
    ): Map<String, String> = queue("sendEmail", mapOf("to" to to, "subject" to subject, "body" to body))

    @Tool(description = "Shows a location on the map.")
    fun showLocationOnMap(
        @ToolParam(description = "The location to search for. May be the name of a place, a business, or an address.") location: String,
    ): Map<String, String> = queue("showLocationOnMap", mapOf("location" to location))

    @Tool(description = "Opens the WiFi settings.")
    fun openWifiSettings(): Map<String, String> = queue("openWifiSettings")

    @Tool(description = "Saves a calendar event (15 minutes) today or tomorrow using only details the user said.")
    fun createCalendarEvent(
        @ToolParam(description = "The date and time of the event in the format YYYY-MM-DDTHH:MM:SS, in the device's local timezone.") datetime: String,
        @ToolParam(description = "The title of the event.") title: String,
    ): Map<String, String> = queue("createCalendarEvent", mapOf("datetime" to datetime, "title" to title))
}
