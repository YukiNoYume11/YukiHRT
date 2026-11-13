package cn.hrtcurve.app

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class Drug(val label: String, val shortLabel: String) {
    ESTRADIOL_VALERATE("戊酸雌二醇", "EV"),
    CPA("醋酸环丙孕酮", "CPA")
}

enum class Route(val label: String) {
    ORAL("口服"),
    SUBLINGUAL("舌下含服")
}

enum class Analyte(val label: String, val defaultUnit: String) {
    E2("雌二醇", "pg/mL"),
    CPA("CPA", "ng/mL"),
    TESTOSTERONE("睾酮", "ng/dL")
}

data class UserProfile(
    val weightKg: Double = 60.0,
    /** 旧版“HRT 开始日期”，仅为兼容已有本地数据保留；T 曲线起点已改为最早给药记录，不再读取该值。 */
    val hrtStartEpochDay: Long = 0L,
    val baselineTestosteroneNgDl: Double? = null
)

data class DoseEvent(
    val id: String,
    val drug: Drug,
    val route: Route,
    val doseMg: Double,
    val takenAtMillis: Long
)

fun buildDoseSeries(
    drug: Drug,
    route: Route,
    doseMg: Double,
    startDate: LocalDate,
    endDate: LocalDate,
    time: LocalTime,
    intervalDays: Int,
    zoneId: ZoneId = ZoneId.systemDefault(),
    idFactory: () -> String = { UUID.randomUUID().toString() }
): List<DoseEvent> {
    require(doseMg > 0.0 && doseMg <= 200.0) { "批量剂量需为 0–200 mg 之间的有效记录剂量" }
    require(!endDate.isBefore(startDate)) { "结束日期不能早于开始日期" }
    require(intervalDays in 1..30) { "间隔天数需为 1–30 天" }
    val totalDays = ChronoUnit.DAYS.between(startDate, endDate)
    val count = totalDays / intervalDays + 1
    require(count <= 1000) { "单次最多生成 1000 条记录；更长时间请分段添加" }
    val safeRoute = if (drug == Drug.CPA) Route.ORAL else route
    return generateSequence(startDate) { it.plusDays(intervalDays.toLong()) }
        .takeWhile { !it.isAfter(endDate) }
        .map { date ->
            DoseEvent(
                id = idFactory(),
                drug = drug,
                route = safeRoute,
                doseMg = doseMg,
                takenAtMillis = date.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
            )
        }
        .toList()
}

data class LabSample(
    val id: String,
    val analyte: Analyte,
    val drug: Drug,
    val route: Route,
    val relatedDoseId: String,
    val elapsedHours: Double,
    val sampledAtMillis: Long,
    val value: Double,
    val unit: String
)

data class BatchImportResult(
    val message: String,
    val added: Int = 0,
    val skipped: Int = 0,
    val success: Boolean = false
)

data class AppState(
    val profile: UserProfile = UserProfile(),
    val doses: List<DoseEvent> = emptyList(),
    val labs: List<LabSample> = emptyList(),
    val calibration: Map<String, Double> = emptyMap(),
    val disclaimerAccepted: Boolean = false,
    val apiBaseUrl: String = "https://api.openai.com/v1",
    val apiModel: String = "gpt-4o-mini"
)

/** 展示用脱敏：只保留首尾各 4 位，避免在界面上完整回显已保存的 Key。 */
fun maskSecret(secret: String): String {
    val trimmed = secret.trim()
    if (trimmed.isEmpty()) return "未保存"
    if (trimmed.length <= 8) return "•".repeat(trimmed.length)
    return trimmed.take(4) + "…" + trimmed.takeLast(4)
}

data class ModelParameters(
    val drug: Drug,
    val route: Route,
    val weightKg: Double,
    val referenceWeightKg: Double,
    val apparentClearance: Double,
    val apparentVolume: Double,
    val absorptionRate: Double,
    val eliminationRate: Double,
    val halfLifeHours: Double,
    val tmaxHours: Double,
    val referenceDoseMg: Double,
    val referenceCmax: Double,
    /** 慢相（终末相）消除速率；0 表示单相处置。 */
    val slowEliminationRate: Double = 0.0,
    /** 进入慢相的剂量份额。 */
    val slowFraction: Double = 0.0,
    val slowHalfLifeHours: Double = 0.0,
    /** 慢相实际承担的 AUC 份额（≠ [slowFraction]：AUCᵢ = wᵢ/kᵢ）。 */
    val slowAucShare: Double = 0.0,
    /** 深室（脂肪 depot）消除速率；0 表示没有第三相。 */
    val deepEliminationRate: Double = 0.0,
    /** 进入深室的剂量份额。 */
    val deepFraction: Double = 0.0,
    val deepHalfLifeHours: Double = 0.0,
    val deepAucShare: Double = 0.0,
    /**
     * 单次给药时的表观终末半衰期（由 96–168 h 段的数值斜率给出）。
     *
     * 三相模型里它落在浅室 36 h 与深室 90 h 之间（CPA 53.7 h，约 2.2 天，
     * 与 Kuhnz 1993 单次实测 54.0 ± 26 h 一致）。0 表示该 profile 没有第三相。
     */
    val apparentTerminalHalfLifeHours: Double = 0.0,
    /**
     * **长期给药后**的终末半衰期：q24 叠加 40 剂到稳态，取停药后 168–336 h 的斜率。
     *
     * 深室的蓄积倍数是 1/(1−e^−kd·τ)，比浅室大得多，稳态后尾部由深室接管，
     * 斜率由单次的 53.7 h 拉长到 78.7 h（约 3.3 天）——即「长时间服药后终末半衰期
     * 可达三天多」这一临床观察的来源。与 Kuhnz 1993 三周期后的 78.6 ± 16 h 一致。
     * 0 表示该 profile 没有第三相。
     */
    val longTermTerminalHalfLifeHours: Double = 0.0,
    /**
     * 峰值后降半时间（h）：从 Cmax 降到 Cmax/2 实际经过的时长。
     *
     * 与 [apparentTerminalHalfLifeHours] 是两个不同的量，用途也不同：
     * - 本值是**用户看曲线时能直接量出来**的那一段，决定「一次给药大概管多久」。
     *   CPA 50 mg 单次在 6 h 保留 85%、8 h 保留 71%、13 h 减半；把它当成 53.7 h 会与曲线矛盾。
     * - 终末相半衰期描述 96 h 之后的尾巴，决定蓄积倍数与停药后的清除尾。
     *
     * 双相/多相曲线两者必然不等（AUC/Cmax 35.7 h，而单次终末相是 53.7 h），
     * 单相时两者相同。
     */
    val apparentHalfLifeHours: Double = 0.0,
    val evidenceLabel: String
) {
    /** 双相处置（舌下 E2 与 CPA）：主相（含分布与快速廓清）与终末相并存。 */
    val dualPhase: Boolean get() = slowEliminationRate > 0.0 && slowFraction > 0.0

    /** 三相处置（仅 CPA）：浅室之外还有脂肪 depot，长期给药后终末相会继续延长。 */
    val triPhase: Boolean get() = dualPhase && deepEliminationRate > 0.0 && deepFraction > 0.0

    /**
     * 用户意义上的「半衰期」= 终末相。
     *
     * 双相模型里 [halfLifeHours] 只是主相（分布 + 快速廓清）的速率常数换算值（CPA 4 h、舌下 0.8 h），
     * 直接拿它当半衰期展示会与文献（CPA 43.9 ± 12.8 h）差一个数量级。
     */
    val terminalHalfLifeHours: Double
        get() = if (slowHalfLifeHours > 0.0) slowHalfLifeHours else halfLifeHours

    /** 终末相半衰期的直观读法（天），仅用于展示。 */
    val terminalHalfLifeDays: Double get() = terminalHalfLifeHours / 24.0
}

data class CurvePoint(
    val hours: Double,
    val value: Double,
    val low: Double,
    val high: Double
)

data class LabPoint(
    val hours: Double,
    val value: Double,
    val label: String
)

data class CurveSeries(
    val analyte: Analyte,
    val unit: String,
    val points: List<CurvePoint>,
    val labPoints: List<LabPoint>,
    val notice: String,
    val quantitative: Boolean,
    val anchoredByLab: Boolean = false
)

/** 曲线纵轴可选单位；[fromBase] 表示 1 个引擎内部单位换算成该单位后的数值倍数。 */
data class UnitOption(val label: String, val fromBase: Double)

/**
 * 引擎内部固定用 E2 = pg/mL、CPA = ng/mL、T = ng/dL 计算，
 * 这里只做展示层换算，避免单位切换污染拟合结果。
 */
fun Analyte.unitOptions(): List<UnitOption> = when (this) {
    Analyte.E2 -> listOf(UnitOption("pg/mL", 1.0), UnitOption("pmol/L", 3.671))
    Analyte.CPA -> listOf(UnitOption("ng/mL", 1.0), UnitOption("nmol/L", 2.398))
    Analyte.TESTOSTERONE -> listOf(UnitOption("ng/dL", 1.0), UnitOption("nmol/L", 0.03466))
}

/** 曲线没有绝对单位（如“% 基线”）时返回 null，界面上不提供单位切换。 */
fun CurveSeries.absoluteUnitOptions(): List<UnitOption> =
    if (unit == RELATIVE_UNIT) emptyList() else analyte.unitOptions()

fun CurveSeries.withDisplayUnit(label: String): CurveSeries {
    if (unit == RELATIVE_UNIT || unit == label) return this
    val option = analyte.unitOptions().firstOrNull { it.label == label } ?: return this
    return copy(
        unit = option.label,
        points = points.map {
            it.copy(
                value = it.value * option.fromBase,
                low = it.low * option.fromBase,
                high = it.high * option.fromBase
            )
        },
        labPoints = labPoints.map { it.copy(value = it.value * option.fromBase) }
    )
}

const val RELATIVE_UNIT = "% 基线"

data class CalibrationResult(
    val factor: Double,
    val sampleCount: Int,
    val ratios: List<Double>
)

enum class IndividualFitLevel(val label: String) {
    POPULATION("群体先验"),
    EXPOSURE("个体暴露"),
    ELIMINATION("个体暴露 + 消除"),
    FULL_PK("个体暴露 + 消除 + 吸收")
}

data class IndividualPkFit(
    val drug: Drug,
    val route: Route,
    val level: IndividualFitLevel = IndividualFitLevel.POPULATION,
    val exposureFactor: Double = 1.0,
    val eliminationFactor: Double = 1.0,
    val absorptionFactor: Double = 1.0,
    val sampleCount: Int = 0,
    val distinctDoseCount: Int = 0,
    val logRmse: Double = 0.0
)

enum class PdFitLevel(val label: String) {
    RELATIVE_ONLY("仅相对趋势"),
    ANCHOR("个体基线锚定"),
    EMAX("个体 Emax"),
    EMAX_EC50("个体 Emax + E2 敏感度")
}

data class IndividualPdFit(
    val level: PdFitLevel = PdFitLevel.RELATIVE_ONLY,
    val emax: Double = 0.95,
    val e2Sensitivity: Double = 1.0,
    val sampleCount: Int = 0,
    val logRmse: Double = 0.0,
    /** 拟合实际使用的未治疗基线；没有填基线时由实测点联估，未拟合时为 null。 */
    val baselineNgDl: Double? = null,
    /** 基线是否由实测点参与估计（而非用户填写）。 */
    val baselineFitted: Boolean = false
)
