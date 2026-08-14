package com.medonza.paketleme

import android.content.Context
import java.util.UUID

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("medonza", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = p.getString("deviceId", null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            p.edit().putString("deviceId", created).apply()
            return created
        }

    var deviceName: String
        get() = p.getString("deviceName", "") ?: ""
        set(v) { p.edit().putString("deviceName", v.trim()).apply() }

    var backendUrl: String
        get() = p.getString("backendUrl", "http://10.0.2.2:8787/") ?: "http://10.0.2.2:8787/"
        set(v) { p.edit().putString("backendUrl", v.trim()).apply() }

    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) { p.edit().putBoolean("sound", v).apply() }
    var vibration: Boolean
        get() = p.getBoolean("vibration", true)
        set(v) { p.edit().putBoolean("vibration", v).apply() }
    var verification: Boolean
        get() = p.getBoolean("verification", false)
        set(v) { p.edit().putBoolean("verification", v).apply() }
}
