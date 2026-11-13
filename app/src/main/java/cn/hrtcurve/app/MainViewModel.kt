package cn.hrtcurve.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToLong

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocalRepository(application)
    private val secrets = SecretStore(application)
    private val aiClient = AiClient()
    private val _state = MutableStateFlow(repository.load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val initialApiKey = secrets.loadApiKey()

    var apiKey by mutableStateOf(initialApiKey.orEmpty())
        private set
    var apiKeyStored by mutableStateOf(!initialApiKey.isNullOrBlank())
        private set
    /** 已写入本机存储的 Key 的脱敏显示；与当前输入框内容分开，避免把未保存的修改误认为已保存。 */
    var storedApiKeyMask by mutableStateOf(initialApiKey?.let(::maskSecret).orEmpty())
        private set
    var aiLoading by mutableStateOf(false)
        private set
    var aiReport by mutableStateOf("")
        private set
    var aiError by mutableStateOf("")
        private set
    /** 「测试连接」独立于正式复核，只发固定短语、不发送健康记录。 */
    var aiTesting by mutableStateOf(false)
        private set
    var aiTestResult by mutableStateOf("")
        private set

    private fun update(transform: (AppState) -> AppState) {
        val next = transform(_state.value)
        _state.value = next
        repository.save(next)
    }

    fun acceptDisclaimer() = update { it.copy(disclaimerAccepted = true) }

    fun saveProfile(weight: String, baselineT: String, baselineUnit: String): String? {
        val kg = weight.toDoubleOrNull()
        if (kg == null || kg !in 35.0..250.0) return "体重需为 35–250 kg 之间的数字"
        val baselineInput = if (baselineT.isBlank()) null else baselineT.trim().toDoubleOrNull()
        val baselineLimit = if (baselineUnit.equals("nmol/L", ignoreCase = true)) 200.0 else 5000.0
        if (!baselineT.isBlank() && (baselineInput == null || baselineInput <= 0.0 || baselineInput > baselineLimit)) {
            return "基线 T 可留空；填写时需为合理范围内的有效数值"
        }
        val baselineNgDl = baselineInput?.let {
            if (baselineUnit.equals("nmol/L", ignoreCase = true)) it * 28.85 else it
        }
        update {
            it.copy(
                profile = it.profile.copy(
                    weightKg = kg,
                    baselineTestosteroneNgDl = baselineNgDl
                )
            )
        }
        return null
    }

    fun addDose(drug: Drug, route: Route, dose: String, dateTime: String): String? {
        val mg = dose.toDoubleOrNull()
        if (mg == null || mg <= 0.0 || mg > 200.0) return "请输入 0–200 mg 之间的有效记录剂量"
        val parsed = runCatching { LocalDateTime.parse(dateTime, DATE_TIME_FORMAT) }.getOrNull()
            ?: return "给药时间格式应为 yyyy-MM-dd HH:mm"
        val millis = parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val safeRoute = if (drug == Drug.CPA) Route.ORAL else route
        val event = DoseEvent(UUID.randomUUID().toString(), drug, safeRoute, mg, millis)
        update { it.copy(doses = (it.doses + event).sortedBy(DoseEvent::takenAtMillis)) }
        return null
    }

    fun addDoseSeries(
        drug: Drug,
        route: Route,
        dose: String,
        startDateText: String,
        endDateText: String,
        timeText: String,
        intervalDaysText: String
    ): BatchImportResult {
        val mg = dose.toDoubleOrNull()
        if (mg == null || mg <= 0.0 || mg > 200.0) {
            return BatchImportResult("批量剂量需为 0–200 mg 之间的有效记录剂量")
        }
        val startDate = runCatching { LocalDate.parse(startDateText.trim(), DATE_FORMAT) }.getOrNull()
            ?: return BatchImportResult("开始日期格式应为 yyyy-MM-dd")
        val endDate = runCatching { LocalDate.parse(endDateText.trim(), DATE_FORMAT) }.getOrNull()
            ?: return BatchImportResult("结束日期格式应为 yyyy-MM-dd")
        if (endDate.isAfter(LocalDate.now())) return BatchImportResult("批量历史记录不能晚于今天")
        val time = runCatching { LocalTime.parse(timeText.trim(), BATCH_TIME_FORMAT) }.getOrNull()
            ?: return BatchImportResult("每日时间格式应为 HH:mm")
        val interval = intervalDaysText.trim().toIntOrNull()
            ?: return BatchImportResult("间隔天数需为整数")
        val events = runCatching {
            buildDoseSeries(drug, route, mg, startDate, endDate, time, interval)
        }.getOrElse { return BatchImportResult(it.message.orEmpty().ifBlank { "批量记录参数无效" }) }
        val now = System.currentTimeMillis()
        if (events.any { it.takenAtMillis > now }) {
            return BatchImportResult("批量历史记录不能包含未来的日期与时间")
        }
        val existing = _state.value.doses.map { Triple(it.drug, it.route, it.takenAtMillis) }.toHashSet()
        val unique = events.filterNot { Triple(it.drug, it.route, it.takenAtMillis) in existing }
        if (unique.isEmpty()) {
            return BatchImportResult(
                "这些时间点已经存在相同药物和途径的记录，未重复添加",
                skipped = events.size
            )
        }
        update { current ->
            current.copy(
                doses = (current.doses + unique).sortedBy(DoseEvent::takenAtMillis),
                labs = current.labs
            )
        }
        val skipped = events.size - unique.size
        val effectiveRoute = if (drug == Drug.CPA) Route.ORAL else route
        return BatchImportResult(
            message = "已成功导入 ${unique.size} 条${drug.shortLabel}·${effectiveRoute.label}记录" +
                if (skipped > 0) "，跳过 $skipped 条重复时间点" else "",
            added = unique.size,
            skipped = skipped,
            success = true
        )
    }

    fun removeDose(id: String) = update {
        it.copy(
            doses = it.doses.filterNot { dose -> dose.id == id },
            labs = it.labs.filterNot { lab -> lab.relatedDoseId == id }
        )
    }

    fun addLab(
        analyte: Analyte,
        dose: DoseEvent,
        elapsedHours: String,
        value: String,
        unit: String
    ): String? {
        val elapsed = elapsedHours.toDoubleOrNull()
        if (elapsed == null || elapsed < 0.0 || elapsed > 24_000.0) return "距给药时间需为 0–24000 小时"
        val measured = value.toDoubleOrNull()
        if (measured == null || measured <= 0.0 || measured > 1_000_000.0) return "请输入有效实测值"
        val sampledAt = dose.takenAtMillis + (elapsed * 3_600_000.0).roundToLong()
        val storedValue = if (analyte == Analyte.E2 && unit.equals("pmol/L", ignoreCase = true)) {
            measured / 3.671
        } else {
            measured
        }
        val sample = LabSample(
            id = UUID.randomUUID().toString(),
            analyte = analyte,
            drug = dose.drug,
            route = dose.route,
            relatedDoseId = dose.id,
            elapsedHours = elapsed,
            sampledAtMillis = sampledAt,
            value = storedValue,
            unit = if (analyte == Analyte.TESTOSTERONE) unit else analyte.defaultUnit
        )
        update { it.copy(labs = (it.labs + sample).sortedBy(LabSample::sampledAtMillis)) }
        return null
    }

    fun removeLab(id: String) = update { it.copy(labs = it.labs.filterNot { lab -> lab.id == id }) }

    fun recalibrate(drug: Drug, route: Route): String {
        val state = _state.value
        val result = PkEngine.individualFit(drug, route, state.labs, state.doses, state.profile.weightKg)
        if (result.sampleCount == 0) return "该药物与途径还没有可用实测点"
        val hint = PkEngine.fitHint(result, drug, route, state.profile.weightKg)
        return "${result.level.label}：${result.sampleCount} 点，暴露 ×${"%.2f".format(result.exposureFactor)}，" +
            "消除 ×${"%.2f".format(result.eliminationFactor)}，吸收 ×${"%.2f".format(result.absorptionFactor)}\n$hint"
    }

    fun updateApiConfig(baseUrl: String, model: String) = update {
        it.copy(apiBaseUrl = baseUrl.trim(), apiModel = model.trim())
    }

    /** 保存接口设置，并按用户选择决定是否把 Key 写入本机加密存储。 */
    fun saveApiConfig(baseUrl: String, model: String, persistKey: Boolean): String {
        val url = baseUrl.trim()
        if (!url.startsWith("https://")) return "接口地址需以 https:// 开头"
        if (model.trim().isEmpty()) return "请填写模型名称"
        update { it.copy(apiBaseUrl = url, apiModel = model.trim()) }
        if (!persistKey) {
            secrets.clearApiKey()
            apiKeyStored = false
            storedApiKeyMask = ""
            return "接口设置已保存；API Key 未写入本机，仅本次会话可用"
        }
        if (apiKey.isBlank()) {
            secrets.clearApiKey()
            apiKeyStored = false
            storedApiKeyMask = ""
            return "接口设置已保存；API Key 为空，未写入本机"
        }
        val saved = secrets.saveApiKey(apiKey)
        apiKeyStored = saved
        storedApiKeyMask = if (saved) maskSecret(apiKey) else ""
        return if (saved) {
            "接口设置与 API Key 已加密保存在本机（$storedApiKeyMask）"
        } else {
            "接口设置已保存，但本机加密存储不可用；API Key 仅本次会话有效"
        }
    }

    /** 删除本机保存的 Key，并清空内存中的副本。 */
    fun clearStoredApiKey(): String {
        secrets.clearApiKey()
        apiKeyStored = false
        storedApiKeyMask = ""
        apiKey = ""
        return "已清除本机保存的 API Key"
    }

    fun updateApiKey(value: String) {
        apiKey = value
    }

    fun clearAiReport() {
        aiReport = ""
        aiError = ""
    }

    fun testAiConnection(baseUrl: String, model: String) {
        if (aiTesting) return
        aiTesting = true
        aiTestResult = ""
        viewModelScope.launch {
            runCatching {
                aiClient.testConnection(baseUrl, apiKey, model)
            }.onSuccess { aiTestResult = it }
                .onFailure { aiTestResult = "连接失败：" + (it.message ?: "未知错误") }
            aiTesting = false
        }
    }

    fun generateAiReview() {
        if (aiLoading) return
        aiLoading = true
        aiError = ""
        aiReport = ""
        val snapshot = _state.value
        viewModelScope.launch {
            runCatching {
                // 摘要要跑三条约 160 点的曲线，放到后台线程，避免点下按钮时界面卡一下。
                val payload = withContext(Dispatchers.Default) { AiPayload.build(snapshot) }
                aiClient.generateReview(snapshot.apiBaseUrl, apiKey, snapshot.apiModel, payload)
            }.onSuccess { aiReport = it }
                .onFailure { aiError = it.message ?: "请求失败，请检查接口与网络" }
            aiLoading = false
        }
    }

    companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val BATCH_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

        fun formatDateTime(millis: Long): String =
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(DATE_TIME_FORMAT)
    }
}
