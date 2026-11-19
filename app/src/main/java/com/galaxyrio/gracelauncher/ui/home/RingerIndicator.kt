package com.galaxyrio.gracelauncher.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em
import androidx.compose.foundation.text.InlineTextContent
import androidx.core.content.ContextCompat
import com.galaxyrio.gracelauncher.R

/** Normal ringer shows nothing; only vibrate and silent get an icon. */
@DrawableRes
internal fun ringerIcon(mode: Int): Int? = when (mode) {
    AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ms_vibration
    AudioManager.RINGER_MODE_SILENT -> R.drawable.ms_notifications_off
    else -> null
}

/** Live ringer mode from the protected system broadcast; no permission needed. */
@Composable
internal fun rememberRingerMode(): Int {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    var mode by remember { mutableIntStateOf(audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL) }
    DisposableEffect(context, audio) {
        mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return mode
}

/** Icon-only status glyph sized and tinted like the weather icon; baseline-aligned via inline text content. */
@Composable
internal fun HomeRinger(@DrawableRes icon: Int, color: Color, textStyle: TextStyle, modifier: Modifier = Modifier) {
    val description = stringResource(
        if (icon == R.drawable.ms_vibration) R.string.home_ringer_vibrate else R.string.home_ringer_silent)
    Box(modifier.testTag("home_ringer").clearAndSetSemantics { contentDescription = description }) {
        Text(
            text = buildAnnotatedString { appendInlineContent("ringer") },
            inlineContent = mapOf("ringer" to InlineTextContent(
                Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter),
            ) { Icon(painterResource(icon), null, Modifier.fillMaxSize(), tint = color) }),
            style = textStyle, color = color, maxLines = 1,
        )
    }
}
