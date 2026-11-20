package com.galaxyrio.gracelauncher.assistant.memory

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryParseTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 11, 20, 10, 0, 0, 0, zone)

    // ---- slash parser + chips ----
    @Test fun everySlashTriggerParses() {
        assertEquals(MemoryCommand.Remember("buy milk"), parseMemoryCommand("/remember buy milk"))
        assertEquals(MemoryCommand.Remember("buy milk"), parseMemoryCommand("/save buy milk"))
        assertEquals(MemoryCommand.Delete(MemoryTarget.Words("milk")), parseMemoryCommand("/delete milk"))
        assertEquals(MemoryCommand.Delete(MemoryTarget.Index(2)), parseMemoryCommand("/forget 2"))
        assertEquals(MemoryCommand.Erase(MemoryTarget.Words("milk")), parseMemoryCommand("/erase milk"))
        assertEquals(MemoryCommand.Erase(MemoryTarget.Index(1)), parseMemoryCommand("/purge 1"))
        assertEquals(MemoryCommand.Recall("milk"), parseMemoryCommand("/recall milk"))
        assertEquals(MemoryCommand.Recall(""), parseMemoryCommand("/recall"))
        assertEquals(MemoryCommand.History(3), parseMemoryCommand("/history 3"))
        assertEquals(MemoryCommand.History(null), parseMemoryCommand("/history abc"))
    }

    @Test fun triggersAreCaseInsensitiveAndTrimmed() {
        assertEquals(MemoryCommand.Remember("x y"), parseMemoryCommand("  /REMEMBER   x y  "))
        assertEquals(MemoryCommand.Delete(null), parseMemoryCommand("/delete"))
    }

    @Test fun unknownSlashIsOrdinaryText() {
        assertNull(parseMemoryCommand("/xyz hello"))
        assertNull(parseMemoryCommand("/rememberme x"))
        assertNull(parseMemoryCommand("hello /remember x"))
        assertNull(parseMemoryCommand("/"))
        assertNull(parseMemoryCommand("plain text"))
    }

    @Test fun chipsApplyToPlainTextAsOneShotModes() {
        assertEquals(MemoryCommand.Remember("my car is blue"), parseMemoryCommand("my car is blue", MemoryChip.SAVE))
        assertEquals(MemoryCommand.Delete(MemoryTarget.Words("milk")), parseMemoryCommand("milk", MemoryChip.DELETE))
        assertEquals(MemoryCommand.Delete(MemoryTarget.Index(2)), parseMemoryCommand("2", MemoryChip.DELETE))
        assertEquals(MemoryCommand.Erase(MemoryTarget.Words("milk")), parseMemoryCommand("milk", MemoryChip.PURGE))
    }

    @Test fun recallChipMapsLikeSlashRecall() {
        assertEquals(parseMemoryCommand("/recall milk"), parseMemoryCommand("milk", MemoryChip.RECALL))
        assertEquals(MemoryCommand.Recall(""), parseMemoryCommand("", MemoryChip.RECALL))
        assertEquals(MemoryCommand.Recall(""), parseMemoryCommand("   ", MemoryChip.RECALL))
        assertNull(parseMemoryCommand("", MemoryChip.SAVE))
        assertNull(parseMemoryCommand("", null))
    }

    @Test fun slashPrefixOverridesChip() {
        assertEquals(MemoryCommand.Recall("milk"), parseMemoryCommand("/recall milk", MemoryChip.DELETE))
        assertEquals(MemoryCommand.Erase(MemoryTarget.Words("a")), parseMemoryCommand("/erase a", MemoryChip.SAVE))
    }

    @Test fun unknownSlashWithChipStaysOrdinaryTextForTheChip() {
        assertEquals(MemoryCommand.Remember("/xyz hello"), parseMemoryCommand("/xyz hello", MemoryChip.SAVE))
    }

    // ---- /remember classification ----
    @Test fun dateBecomesEventAndKeepsFullResolvedDateTime() {
        val r = classifyMemory("Dentist appointment tomorrow at 3pm", now)
        assertEquals(MemoryType.EVENT, r.type)
        assertEquals(ZonedDateTime.of(2026, 11, 21, 15, 0, 0, 0, zone).toInstant().toEpochMilli(), r.occurredAt)
    }

    @Test fun cuesGivePreferenceAndPlaceInEnglishAndGerman() {
        assertEquals(MemoryType.PREFERENCE, classifyMemory("I prefer oat milk", now).type)
        assertEquals(MemoryType.PREFERENCE, classifyMemory("I hate cilantro", now).type)
        assertEquals(MemoryType.PREFERENCE, classifyMemory("Ich mag Kaffee", now).type)
        assertEquals(MemoryType.PREFERENCE, classifyMemory("Ich bevorzuge Tee", now).type)
        assertEquals(MemoryType.PLACE, classifyMemory("My car is parked on level 3", now).type)
        assertEquals(MemoryType.PLACE, classifyMemory("The spare key is at Anna's", now).type)
        assertEquals(MemoryType.PLACE, classifyMemory("Das Auto parkt in der Garage", now).type)
        assertEquals(MemoryType.PLACE, classifyMemory("Der Schlüssel liegt im Flur", now).type)
    }

    @Test fun otherwiseFactWithNoDate() {
        val r = classifyMemory("My wifi password hint is the dog's name", now)
        assertEquals(MemoryType.FACT, r.type); assertNull(r.occurredAt)
    }

    @Test fun germanDatePhraseIsNotAnEventByDesign() {
        val r = classifyMemory("Zahnarzt morgen um 15 Uhr", now)
        assertNotEquals(MemoryType.EVENT, r.type); assertNull(r.occurredAt)
    }

    // ---- subject JSON validator + fallback ----
    @Test fun validSubjectJson() {
        assertEquals(SubjectJson("Oat milk", null), validateSubjectJson("""{"subject":"Oat milk"}""", MemoryType.PREFERENCE))
        assertEquals(SubjectJson("Car location", MemoryType.PLACE), validateSubjectJson("""{"subject":"Car location","type":"place"}""", MemoryType.FACT))
        assertEquals(SubjectJson("a\"b", null), validateSubjectJson("""{ "subject" : "a\"b" }""", MemoryType.FACT))
    }

    @Test fun invalidSubjectJsonRejected() {
        val bad = listOf(
            "", "no json", """{"subject":""}""", """{"subject":"   "}""", """{"subject":"one two three four five six seven"}""",
            """{"subject":1}""", """{"subj":"x"}""", """{"subject":"x","extra":"y"}""", """{"subject":"x"} trailing""",
            """{"subject":"x","type":"event"}""", """{"subject":"x","type":"bogus"}""", """{"subject":"line1\nline2"}""",
            "```json\n{\"subject\":\"x\"}\n```", """{"subject":"${"w".repeat(201)}"}""",
        )
        bad.forEach { assertNull("should reject: $it", validateSubjectJson(it, MemoryType.FACT)) }
    }

    @Test fun typeFromModelOnlyUsedWhenRulesGaveFact() {
        assertNull(validateSubjectJson("""{"subject":"x","type":"bogus"}""", MemoryType.FACT))
        assertEquals(SubjectJson("x", null), validateSubjectJson("""{"subject":"x","type":"place"}""", MemoryType.PREFERENCE))
    }

    @Test fun fallbackSubjectIsFirstFourContentWords() {
        assertEquals("car parked level 3", fallbackSubject("My car is parked on level 3 in the garage"))
        assertEquals("mag Kaffee Hafermilch Morgen", fallbackSubject("Ich mag Kaffee mit Hafermilch am Morgen"))
        assertEquals("note", fallbackSubject("   "))
    }

    @Test fun parseRememberUsesValidModelSubjectAndKeepsBodyVerbatim() = runBlocking {
        val text = "I prefer  oat milk."
        val d = parseRemember(text, now, { """{"subject":"Milk preference"}""" })
        assertEquals(MemoryDraft(MemoryType.PREFERENCE, "Milk preference", text, null), d)
    }

    @Test fun parseRememberFactTypeCanBeRefinedByModel() = runBlocking {
        val d = parseRemember("Spare key with Anna", now, { """{"subject":"Spare key","type":"place"}""" })
        assertEquals(MemoryType.PLACE, d.type); assertEquals("Spare key", d.subject)
    }

    @Test fun parseRememberFallsBackOnInvalidNullThrowingAndTimeout() = runBlocking {
        val text = "My car is parked on level 3"
        val expectedSubject = fallbackSubject(text)
        assertEquals(expectedSubject, parseRemember(text, now, { "garbage" }).subject)
        assertEquals(expectedSubject, parseRemember(text, now, { null }).subject)
        assertEquals(expectedSubject, parseRemember(text, now, { throw IllegalStateException("boom") }).subject)
        val slow = parseRemember(text, now, { delay(5_000); """{"subject":"late"}""" }, timeoutMs = 50)
        assertEquals(expectedSubject, slow.subject); assertEquals(MemoryType.PLACE, slow.type)
    }

    @Test fun parseRememberEventKeepsDateAndNothingPersists() = runBlocking {
        val d = parseRemember("Dentist tomorrow at 3pm", now, { """{"subject":"Dentist"}""" })
        assertEquals(MemoryType.EVENT, d.type)
        assertEquals(ZonedDateTime.of(2026, 11, 21, 15, 0, 0, 0, zone).toInstant().toEpochMilli(), d.occurredAt)
    }

    // ---- subject_key ----
    @Test fun subjectKeyLowersTrimsCollapsesAndFoldsDiacritics() {
        assertEquals("cafe creme", subjectKey("  Café   Crème "))
        assertEquals("strasse", subjectKey("Straße"))
        assertEquals("zahnarzt", subjectKey("ZAHNARZT"))
        assertEquals(subjectKey("Über  Uns"), subjectKey("uber uns"))
    }
}

/** Shared with server/supabase/grace_memory/tests/test_05_subject_key.py — one file, both sides must agree byte for byte. */
class SubjectKeyVectorsTest {
    @Test fun matchesSharedVectors() {
        val text = checkNotNull(javaClass.getResourceAsStream("/memory/subject_key_vectors.json")).readBytes().toString(Charsets.UTF_8)
        val vectors = org.json.JSONArray(text)
        assertTrue(vectors.length() >= 40)
        val bad = (0 until vectors.length()).map { vectors.getJSONArray(it) }
            .filter { subjectKey(it.getString(0)) != it.getString(1) }
            .map { "${it.getString(0).take(30).toList().map { c -> "\\u%04x".format(c.code) }.joinToString("")}" }
        assertEquals("vectors failing", emptyList<String>(), bad)
    }
}
