package cn.hrtcurve.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LocalRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hrt_curve_local", Context.MODE_PRIVATE)

    fun load(): AppState {
        val default = AppState()
        return runCatching {
            val doses = mutableListOf<DoseEvent>()
            val doseArray = JSONArray(prefs.getString("doses", "[]"))
            for (index in 0 until doseArray.length()) {
                val item = doseArray.getJSONObject(index)
                doses += DoseEvent(
                    id = item.getString("id"),
                    drug = Drug.valueOf(item.getString("drug")),
                    route = Route.valueOf(item.getString("route")),
                    doseMg = item.getDouble("doseMg"),
                    takenAtMillis = item.getLong("takenAt")
                )
            }
            val labs = mutableListOf<LabSample>()
            val labArray = JSONArray(prefs.getString("labs", "[]"))
            for (index in 0 until labArray.length()) {
                val item = labArray.getJSONObject(index)
                labs += LabSample(
                    id = item.getString("id"),
                    analyte = Analyte.valueOf(item.getString("analyte")),
                    drug = Drug.valueOf(item.getString("drug")),
                    route = Route.valueOf(item.getString("route")),
                    relatedDoseId = item.getString("doseId"),
                    elapsedHours = item.getDouble("elapsed"),
                    sampledAtMillis = item.getLong("sampledAt"),
                    value = item.getDouble("value"),
                    unit = item.getString("unit")
                )
            }
            val calibration = mutableMapOf<String, Double>()
            val calibrationJson = JSONObject(prefs.getString("calibration", "{}") ?: "{}")
            calibrationJson.keys().forEach { key -> calibration[key] = calibrationJson.getDouble(key) }
            default.copy(
                profile = UserProfile(
                    weightKg = prefs.getString("weight", "60.0")?.toDoubleOrNull()?.coerceIn(35.0, 250.0) ?: 60.0,
                    hrtStartEpochDay = prefs.getLong("hrtStart", default.profile.hrtStartEpochDay),
                    baselineTestosteroneNgDl = prefs.getString("baselineT", null)?.toDoubleOrNull()?.takeIf { it > 0.0 }
                ),
                doses = doses.sortedBy { it.takenAtMillis },
                labs = labs.sortedBy { it.sampledAtMillis },
                calibration = calibration,
                disclaimerAccepted = prefs.getBoolean("disclaimer", false),
                apiBaseUrl = prefs.getString("apiBase", default.apiBaseUrl) ?: default.apiBaseUrl,
                apiModel = prefs.getString("apiModel", default.apiModel) ?: default.apiModel
            )
        }.getOrElse { default }
    }

    fun save(state: AppState) {
        val doseArray = JSONArray()
        state.doses.forEach { dose ->
            doseArray.put(JSONObject().apply {
                put("id", dose.id)
                put("drug", dose.drug.name)
                put("route", dose.route.name)
                put("doseMg", dose.doseMg)
                put("takenAt", dose.takenAtMillis)
            })
        }
        val labArray = JSONArray()
        state.labs.forEach { lab ->
            labArray.put(JSONObject().apply {
                put("id", lab.id)
                put("analyte", lab.analyte.name)
                put("drug", lab.drug.name)
                put("route", lab.route.name)
                put("doseId", lab.relatedDoseId)
                put("elapsed", lab.elapsedHours)
                put("sampledAt", lab.sampledAtMillis)
                put("value", lab.value)
                put("unit", lab.unit)
            })
        }
        val calibration = JSONObject()
        state.calibration.forEach { (key, value) -> calibration.put(key, value) }
        prefs.edit()
            .putString("weight", state.profile.weightKg.toString())
            .putLong("hrtStart", state.profile.hrtStartEpochDay)
            .putString("baselineT", state.profile.baselineTestosteroneNgDl?.toString())
            .putString("doses", doseArray.toString())
            .putString("labs", labArray.toString())
            .putString("calibration", calibration.toString())
            .putBoolean("disclaimer", state.disclaimerAccepted)
            .putString("apiBase", state.apiBaseUrl)
            .putString("apiModel", state.apiModel)
            .apply()
    }
}
