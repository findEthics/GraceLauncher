package com.galaxyrio.gracelauncher.ui.home

import android.media.AudioManager
import com.galaxyrio.gracelauncher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingerIndicatorTest {
    @Test
    fun mapsRingerModeToIcon() {
        assertNull(ringerIcon(AudioManager.RINGER_MODE_NORMAL))
        assertEquals(R.drawable.ms_vibration, ringerIcon(AudioManager.RINGER_MODE_VIBRATE))
        assertEquals(R.drawable.ms_notifications_off, ringerIcon(AudioManager.RINGER_MODE_SILENT))
        assertNull(ringerIcon(99))
    }
}
