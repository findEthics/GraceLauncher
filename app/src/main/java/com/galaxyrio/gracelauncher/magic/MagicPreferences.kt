package com.galaxyrio.gracelauncher.magic

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class MagicPreferences(context: Context) : AutoCloseable {
    private val prefs = context.getSharedPreferences("magic_commands", Context.MODE_PRIVATE)
    var automatic by mutableStateOf(prefs.getBoolean("automatic", false)); private set
    var provider by mutableStateOf(prefs.getString("provider", null)); private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        automatic = prefs.getBoolean("automatic", false)
        provider = prefs.getString("provider", null)
    }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    fun automatic(value: Boolean) { prefs.edit().putBoolean("automatic", value).apply() }
    fun provider(value: String?) { prefs.edit().putString("provider", value).apply() }
    override fun close() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}
