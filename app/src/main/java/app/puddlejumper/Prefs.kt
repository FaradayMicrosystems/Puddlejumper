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
        // Open Charge Map by default now that its key is built in. New pref name so older saved choices reset.
        get() = runCatching { ChargerSourceType.valueOf(sp.getString("chargerSource", null)!!) }
            .getOrDefault(ChargerSourceType.OPEN_CHARGE_MAP)
        set(v) = sp.edit().putString("chargerSource", v.name).apply()

    /** API keys are built into the app (see app/build.gradle.kts); there is no Settings field for them. */
    val nrelKey: String get() = BuildConfig.NREL_API_KEY
    val ocmKey: String get() = BuildConfig.OCM_API_KEY
}
