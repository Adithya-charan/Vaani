package com.vaani.app.data

import android.content.Context
import androidx.core.content.edit

interface VoicePrefs {
    var muted: Boolean
}

class SharedVoicePrefs(context: Context) : VoicePrefs {
    private val prefs = context.getSharedPreferences("vaani_voice", Context.MODE_PRIVATE)
    override var muted: Boolean
        get() = prefs.getBoolean("muted", false)
        set(value) = prefs.edit { putBoolean("muted", value) }
}

class MemoryVoicePrefs(override var muted: Boolean = false) : VoicePrefs
