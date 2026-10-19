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
// Adapted from Google AI Edge Gallery's ContactCallResolver.kt (package renamed).
package com.galaxyrio.gracelauncher.assistant

import java.text.Normalizer
import java.util.Locale

internal data class ContactNameCandidate(val id: Long, val displayName: String)

internal data class ContactPhoneCandidate(val number: String, val label: String)

internal sealed interface PhoneMatch {
  data class Found(val number: ContactPhoneCandidate) : PhoneMatch

  data class Failure(val message: String) : PhoneMatch
}

internal object ContactCallResolver {
  private val whitespace = Regex("\\s+")
  private val allowedPhoneCharacters = Regex("\\+?[0-9 .()\\-]+")

  fun choosePhone(
    contactName: String,
    requestedLabel: String,
    candidates: List<ContactPhoneCandidate>,
  ): PhoneMatch {
    if (candidates.isEmpty()) {
      return PhoneMatch.Failure("No phone number is saved for $contactName.")
    }
    val label = normalizeDisplayName(requestedLabel)
    val matches =
      if (label.isEmpty()) candidates
      else candidates.filter { normalizeDisplayName(it.label) == label }
    return when {
      matches.isEmpty() -> PhoneMatch.Failure("No phone number for $contactName has the label '$requestedLabel'.")
      matches.size == 1 -> PhoneMatch.Found(matches.single())
      label.isEmpty() ->
        PhoneMatch.Failure("$contactName has multiple phone numbers. Specify a unique saved phone label.")
      else ->
        PhoneMatch.Failure("More than one phone number for $contactName has the label '$requestedLabel'.")
    }
  }

  fun normalizeDisplayName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
      .trim()
      .replace(whitespace, " ")
      .lowercase(Locale.ROOT)

  fun normalizePhoneNumber(value: String): String? {
    val input = value.trim()
    if (!allowedPhoneCharacters.matches(input)) return null
    val first = input.firstOrNull { !it.isWhitespace() } ?: return null
    if (first != '+' && first != '(' && first !in '0'..'9') return null
    val openParenthesis = input.indexOf('(')
    val closeParenthesis = input.indexOf(')')
    if (
      input.count { it == '(' } != input.count { it == ')' } ||
        input.count { it == '(' } > 1 ||
        (openParenthesis >= 0 && closeParenthesis < openParenthesis)
    ) {
      return null
    }
    if (input.last() !in '0'..'9') return null
    val digits = input.filter { it in '0'..'9' }
    if (digits.length !in 7..15) return null
    return if (input.startsWith('+')) "+$digits" else digits
  }
}
