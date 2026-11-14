package app.puddlejumper

import android.content.Context
import app.puddlejumper.core.LatLon
import org.json.JSONArray
import org.json.JSONObject

enum class ChargerSourceType { NREL, OPEN_CHARGE_MAP }

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("puddlejumper", Context.MODE_PRIVATE)

    /** Usable battery capacity, kWh. */
    var usableKwh: Double
        get() = sp.getFloat("usableKwh", 75f).toDouble()
        set(v) = sp.edit().putFloat("usableKwh", v.toFloat()).apply()

    /** Average consumption, kWh/100 km. */
    var kwhPer100Km: Double
        get() = sp.getFloat("kwhPer100Km", 18f).toDouble()
        set(v) = sp.edit().putFloat("kwhPer100Km", v.toFloat()).apply()

    /** Car plus people, kg; used for the energy of hills. */
    var massKg: Double
        get() = sp.getFloat("massKg", 2_100f).toDouble()
        set(v) = sp.edit().putFloat("massKg", v.toFloat()).apply()

    /** Battery charge now, 0..100. */
    var batteryPercent: Double
        get() = sp.getFloat("batteryPercent", 80f).toDouble()
        set(v) = sp.edit().putFloat("batteryPercent", v.toFloat()).apply()

    /** Lower the battery estimate as you drive (from distance x consumption). */
    var countDownBattery: Boolean
        get() = sp.getBoolean("countDownBattery", true)
        set(v) = sp.edit().putBoolean("countDownBattery", v).apply()

    var rangeTreeOn: Boolean
        get() = sp.getBoolean("rangeTreeOn", true)
        set(v) = sp.edit().putBoolean("rangeTreeOn", v).apply()

    var reservePercent: Int
        get() = sp.getInt("reservePercent", 10)
        set(v) = sp.edit().putInt("reservePercent", v).apply()

    var chargeAtEachStop: Boolean
        get() = sp.getBoolean("chargeAtEachStop", true)
        set(v) = sp.edit().putBoolean("chargeAtEachStop", v).apply()

    var weatherOn: Boolean
        get() = sp.getBoolean("weatherOn", false)
        set(v) = sp.edit().putBoolean("weatherOn", v).apply()

    var voiceMuted: Boolean
        get() = sp.getBoolean("voiceMuted", false)
        set(v) = sp.edit().putBoolean("voiceMuted", v).apply()

    var chargerSource: ChargerSourceType
        // Open Charge Map by default when the build has its key, otherwise NREL.
        get() = runCatching { ChargerSourceType.valueOf(sp.getString("chargerSource", null)!!) }
            .getOrDefault(
                if (BuildConfig.OCM_API_KEY.isNotBlank()) ChargerSourceType.OPEN_CHARGE_MAP else ChargerSourceType.NREL
            )
        set(v) = sp.edit().putString("chargerSource", v.name).apply()

    /**
     * Stops the user added, saved so the route survives closing the app.
     * "My location" is not saved: it is re-added from GPS on the next start.
     */
    var savedStops: List<Waypoint>
        get() = runCatching {
            val arr = JSONArray(sp.getString("savedStops", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Waypoint(o.getString("name"), LatLon(o.getDouble("lat"), o.getDouble("lon")))
            }
        }.getOrDefault(emptyList())
        set(v) {
            val arr = JSONArray()
            v.filterNot { it.isCurrentLocation }.forEach {
                arr.put(JSONObject().put("name", it.name).put("lat", it.location.lat).put("lon", it.location.lon))
            }
            sp.edit().putString("savedStops", arr.toString()).apply()
        }

    /** API keys are built into the app (see app/build.gradle.kts); there is no Settings field for them. */
    val nrelKey: String get() = BuildConfig.NREL_API_KEY.ifBlank { "DEMO_KEY" }
    val ocmKey: String get() = BuildConfig.OCM_API_KEY
}
