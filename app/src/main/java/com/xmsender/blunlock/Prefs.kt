package com.xmsender.blunlock

import android.content.Context
import java.util.UUID

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(v) = sp.edit().putString("token", v.trim()).apply()

    val deviceId: String
        get() = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().replace("-", "")
            .uppercase().also { sp.edit().putString("deviceId", it).apply() }

    var offsetMs: Long
        get() = sp.getLong("offsetMs", -100)
        set(v) = sp.edit().putLong("offsetMs", v).apply()

    var count: Int
        get() = sp.getInt("count", 10)
        set(v) = sp.edit().putInt("count", v).apply()

    var intervalMs: Long
        get() = sp.getLong("intervalMs", 100)
        set(v) = sp.edit().putLong("intervalMs", v).apply()
}
