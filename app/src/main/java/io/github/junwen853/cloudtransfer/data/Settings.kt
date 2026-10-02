package io.github.junwen853.cloudtransfer.data

import android.content.Context

/** User preferences for the automatic backup. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var nightlyEnabled: Boolean
        get() = prefs.getBoolean("nightlyEnabled", false)
        set(value) = prefs.edit().putBoolean("nightlyEnabled", value).apply()

    /** Hour of the day (0-23) the nightly backup may start. */
    var nightlyHour: Int
        get() = prefs.getInt("nightlyHour", 2)
        set(value) = prefs.edit().putInt("nightlyHour", value.coerceIn(0, 23)).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(value) = prefs.edit().putBoolean("wifiOnly", value).apply()

    var chargingOnly: Boolean
        get() = prefs.getBoolean("chargingOnly", true)
        set(value) = prefs.edit().putBoolean("chargingOnly", value).apply()

    /** Compare pictures to also catch copies whose metadata was removed. */
    var samePictureCheck: Boolean
        get() = prefs.getBoolean("samePictureCheck", true)
        set(value) = prefs.edit().putBoolean("samePictureCheck", value).apply()

    var lastNightlyResult: String
        get() = prefs.getString("lastNightlyResult", "").orEmpty()
        set(value) = prefs.edit().putString("lastNightlyResult", value).apply()

    /** 0 = global iCloud, 1 = iCloud operated in mainland China. */
    var region: Int
        get() = prefs.getInt("region", 0)
        set(value) = prefs.edit().putInt("region", value).apply()
}
