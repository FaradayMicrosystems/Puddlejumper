package app.puddlejumper

import android.content.Context
import app.puddlejumper.core.DistanceUnit

enum class ChargerSourceType { NREL, OPEN_CHARGE_MAP }

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("puddlejumper", Context.MODE_PRIVATE)

    var fullRangeMeters: Double
        get() = sp.getFloat("fullRangeMeters", 400_000f).toDouble()
        set(v) = sp.edit().putFloat("fullRangeMeters", v.toFloat()).apply()

    var reservePercent: Int
        get() = sp.getInt("reservePercent", 10)
        set(v) = sp.edit().putInt("reservePercent", v).apply()

    var chargeAtEachStop: Boolean
        get() = sp.getBoolean("chargeAtEachStop", true)
        set(v) = sp.edit().putBoolean("chargeAtEachStop", v).apply()

    var multiJumpView: Boolean
        get() = sp.getBoolean("multiJumpView", false)
        set(v) = sp.edit().putBoolean("multiJumpView", v).apply()

    var unit: DistanceUnit
        // Metric by default. New key so builds that defaulted to miles don't carry that choice over.
        get() = runCatching { DistanceUnit.valueOf(sp.getString("distanceUnit", null)!!) }.getOrDefault(DistanceUnit.KILOMETERS)
        set(v) = sp.edit().putString("distanceUnit", v.name).apply()

    var chargerSource: ChargerSourceType
        get() = runCatching { ChargerSourceType.valueOf(sp.getString("source", null)!!) }.getOrDefault(ChargerSourceType.NREL)
        set(v) = sp.edit().putString("source", v.name).apply()

    /** User-entered keys override the build-time keys from local.properties. */
    var nrelKey: String
        get() = sp.getString("nrelKey", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.NREL_API_KEY
        set(v) = sp.edit().putString("nrelKey", v.trim()).apply()

    var ocmKey: String
        get() = sp.getString("ocmKey", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.OCM_API_KEY
        set(v) = sp.edit().putString("ocmKey", v.trim()).apply()
}
