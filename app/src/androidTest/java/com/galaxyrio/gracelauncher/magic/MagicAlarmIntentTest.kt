package com.galaxyrio.gracelauncher.magic

import android.provider.AlarmClock
import org.junit.Assert.*
import org.junit.Test

/** Builds intents only; never starts Clock or changes real alarms/timers. */
class MagicAlarmIntentTest {
    @Test fun alarmExtrasPreserveTimeAndOptionalLabel() {
        val intent = magicAlarmIntent(MagicAlarmCommand.Alarm(7, 30, "Breakfast"))
        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(7, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("Breakfast", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertFalse(magicAlarmIntent(MagicAlarmCommand.Alarm(19, 0)).hasExtra(AlarmClock.EXTRA_MESSAGE))
    }

    @Test fun timerUsesSecondsAndSkipsUi() {
        val intent = magicAlarmIntent(MagicAlarmCommand.Timer(600))
        assertEquals(AlarmClock.ACTION_SET_TIMER, intent.action)
        assertEquals(600, intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
    }
}
