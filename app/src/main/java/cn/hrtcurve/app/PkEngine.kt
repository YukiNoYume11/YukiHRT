package cn.hrtcurve.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Transparent curve-equivalent scenarios with optional, regularized individual fits.
 * This is not a clinically validated PK/PD model and must not be used for dosing.
 */
object PkEngine {
    private const val E2_MOLAR_EQUIVALENT = 272.38 / 356.50

    /**
     * T 抑制情景常数：E2 与 CPA 都用 7 日平均**血药浓度**驱动。
     *
     * ENIGI（882 例）显示单用雌激素 T≈5.5 nmol/L，而 CPA 10/25/50/100 mg 的 T 均≈0.9–1.1 nmol/L，
     * 因此 CPA 项在约 10 mg/日以上必须进入平台，不能随血药浓度继续线性压低 T。
     *
     * 旧版直接用 7 日平均**日剂量**：稳态值与 ENIGI 吻合，但漏服/停药时剂量窗口会立刻归零，
     * T 在一天内就跳回基线——与 43.9 h 半衰期、每日给药蓄积约 3 倍的实测曲线自相矛盾。
     * 现在改成浓度驱动：先把 7 日平均浓度折算成「等效日剂量」（以 50 mg q24 稳态均值为 50 mg 基准），
     * 再走同一条饱和函数。稳态下两种口径完全等价（浓度比 = 剂量比），只有停药/漏服/爬坡的
     * 时间过程变了，且这个时间过程直接来自上面校正过的 PK 曲线。
     */
    private const val E2_REFERENCE_PG_ML = 45.0
    private const val E2_HILL = 1.5
    private const val CPA_DAILY_SCALE_MG = 1.5
    private const val PD_WINDOW_DAYS = 7.0

    /**
     * 未输入未治疗基线 T、也没有治疗中实测点时，用群体参考基线显示绝对情景曲线。
     * 取成年男性参考范围（约 300–1000 ng/dL）的中位量级；只用于让曲线有 ng/dL / nmol/L 标尺，
     * 不代表本人基线，必须在提示里说明。
     */
    private const val DEFAULT_T_BASELINE_NG_DL = 550.0

    /**
     * 性能：单次给药在经历 [KERNEL_DECAY_CUTOFF] 个半衰期后贡献 < 1e-6，直接截断。
     * 一年 730 条记录里，每个时间点真正有效的剂量只有最近几十条，这是不卡顿的关键。
     */
    private const val KERNEL_DECAY_CUTOFF = 20.0

    /**
     * 只保留距查询时间 [RELEVANCE_DAYS] 天内的给药记录（仅曲线页取数用；totalConcentration 全量叠加）。
     * CPA 深室 90 h：50 天 ≈ 13.3 个深室半衰期，截断损失 <0.01%——365 天与 50 天窗口算出的稳态峰一致，
     * 见 cpa_accumulationIsBounded_reachesPlateauWithin30Days。
     */
    private const val RELEVANCE_DAYS = 50.0

    private data class PkProfile(
        val drug: Drug,
        val route: Route,
        val referenceDoseMg: Double,
        val referenceCmax: Double,
        val referenceWeightKg: Double,
        val halfLifeHours: Double,
        val tmaxHours: Double,
        val massToCurveUnit: Double,
        val targetToMicrogramPerLiter: Double,
        val lowScenario: Double,
        val highScenario: Double,
        val evidenceLabel: String,
        /**
         * 进入终末相（慢相）的**剂量份额**，0 表示单相处置。
         *
         * 注意它不是 AUC 份额：AUCᵢ = wᵢ / kᵢ，所以终末相的 AUC 份额是
         * (f/ks) / [(1−f)/kf + f/ks]。舌下 E2 的 f=0.118 对应约 70% 的 AUC 落在终末相。
         *
         * 为什么需要双相：单室单指数必须同时满足 Cmax、Tmax、t½ 与 AUC 四个锚点，
         * 而真实曲线是「吸收/分布相快速回落 + 终末相长尾」。压成单相会把 AUC 抬高三倍
         * （CPA：模型 9 085 vs 药品资料清除率推出的 2 993 ng·h/mL），或者把尾巴压没
         * （舌下 E2 旧版 2 h 半衰期，12 h 只剩个位数）。
         */
        val slowFraction: Double = 0.0,
        val slowHalfLifeHours: Double = 0.0,
        /**
         * 深室份额与半衰期（第三相）。
         *
         * CPA 是脂溶性药物，会进入脂肪组织形成 depot：Kuhnz 1993（Diane-35，15 人，3 个周期）
         * 实测单次 t½ 54.0 ± 26.0 h，第 3 周期末延长到 78.6 ± 16.0 h，Vz 由 986 L 增至 1 304 L，
         * 蓄积约 2 倍，稳态要到第 16 天才平。
         *
         * 用「浅室 36 h + 深室 90 h」两个慢相，线性叠加会自动产生这个现象：单次给药时
         * 深室只占 6.5% 的剂量，表观终末斜率 53.7 h（两天多，与 Kuhnz 单次 54 h 一致）；
         * 连续给药后深室蓄积 5.9 倍、远超浅室的 2.7 倍，稳态后尾部改由深室主导，
         * 斜率升到 78.7 h（三天多，与 Kuhnz 三周期后的 78.6 h 一致）。
         * 不需要时间依赖参数，也不需要手调。
         */
        val deepFraction: Double = 0.0,
        val deepHalfLifeHours: Double = 0.0,
        /** 双相时直接给定吸收速率（舌下 15–25 min 起效），不再由 tmax 反解。 */
        val absorptionRateOverride: Double? = null
    ) {
        val referenceKe = ln(2.0) / halfLifeHours
        /**
         * 吸收速率必须快于快相消除。若 ka < kf，系数 ka/(ka−kf) 变号（flip-flop），
         * 曲线会出现负幅度、峰值扫描失真——调参时很容易踩到（例如给 CPA 套 1 h 滞后
         * 并把快相压到 1.2 h，就会得到「看着更陡、其实系数为负」的假曲线）。
         * 1.05 只是硬下限，现有任何 profile 的取值都不受影响。
         */
        val ka = (absorptionRateOverride ?: solveKa(referenceKe, tmaxHours))
            .coerceAtLeast(referenceKe * 1.05)
        val referenceKeSlow: Double = if (slowHalfLifeHours > 0.0) ln(2.0) / slowHalfLifeHours else 0.0
        val referenceKeDeep: Double = if (deepHalfLifeHours > 0.0) ln(2.0) / deepHalfLifeHours else 0.0
        val dualPhase: Boolean = slowFraction > 0.0 && referenceKeSlow > 0.0
        val triPhase: Boolean = dualPhase && deepFraction > 0.0 && referenceKeDeep > 0.0

        /** 单剂量 AUC 的形状积分（单位 h）：单相 1/ke；双相 Σwᵢ/kᵢ。 */
        val referenceAucShape: Double = when {
            triPhase -> (1.0 - slowFraction - deepFraction) / referenceKe +
                slowFraction / referenceKeSlow + deepFraction / referenceKeDeep
            dualPhase -> (1.0 - slowFraction) / referenceKe + slowFraction / referenceKeSlow
            else -> 1.0 / referenceKe
        }
        val referenceVd: Double
        val referenceCl: Double

        init {
            // 多相时峰值不一定落在快相 tmax 上，用数值扫描取真实峰值再做 Vd 校准
            val shape = if (dualPhase) {
                peakShape(ka, referenceKe, referenceKeSlow, slowFraction, referenceKeDeep, deepFraction)
            } else {
                absorptionShape(ka, referenceKe, tmaxHours)
            }
            referenceVd = referenceDoseMg * massToCurveUnit * shape / referenceCmax
            // CL = D / AUC —— 必须按形状积分反推，不能写 ke×Vd：
            // 双相下 ke 只是分布相的速率，ke×Vd 会比真实清除率高一个数量级。
            referenceCl = referenceVd / referenceAucShape
        }
    }

    private val profiles = listOf(
        PkProfile(
            Drug.ESTRADIOL_VALERATE, Route.ORAL,
            referenceDoseMg = 1.0,
            referenceCmax = 20.47,
            referenceWeightKg = 57.0,
            halfLifeHours = 14.48,
            tmaxHours = 10.0,
            massToCurveUnit = 1_000_000.0,
            targetToMicrogramPerLiter = 0.001,
            lowScenario = 0.5,
            highScenario = 2.0,
            evidenceLabel = "EV 口服：1 mg 单次、绝经后女性研究（Cmax 20.47、Tmax 10 h、t½ 14.48 h）；人群外推"
        ),
        PkProfile(
            Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL,
            referenceDoseMg = 1.0,
            // 峰值仍锚定 144 pg/mL（1 mg 17β-E2 舌下，10 名跨性别女性交叉研究），
            // 再按摩尔当量折成 1 mg EV；双相只改处置形状，不动峰值基准。
            referenceCmax = 144.0 * E2_MOLAR_EQUIVALENT,
            referenceWeightKg = 70.0,
            // 分布相 0.8 h（含服后 2 h 内快速回落）+ 终末相 14 h（8–24 h 的尾巴与蓄积）。
            // f 由「AUC = 626 pg·h/mL」反解：终末相承担约 70% 的 AUC。
            halfLifeHours = 0.8,
            tmaxHours = 0.7,
            massToCurveUnit = 1_000_000.0,
            targetToMicrogramPerLiter = 0.001,
            lowScenario = 0.25,
            highScenario = 3.0,
            evidenceLabel = "EV 舌下：双相处置（分布相 0.8 h + 终末相 14 h，终末相约 70% AUC）；峰值由 17β-E2 舌下研究按摩尔当量外推",
            slowFraction = 0.118,
            slowHalfLifeHours = 14.0,
            absorptionRateOverride = 2.5
        ),
        PkProfile(
            Drug.CPA, Route.ORAL,
            referenceDoseMg = 50.0,
            referenceCmax = 140.0,
            referenceWeightKg = 70.0,
            // 三相处置：主相 4 h（69.5%）+ 浅室 36 h（24%）+ 深室 90 h（6.5%）。
            //
            // 依据：
            // - Siterone/Androcur SPC：50 mg → Cmax 140 @3 h；24–120 h 段 t½ 43.9 ± 12.8 h；
            //   CL 3.5 ± 1.5 mL/min/kg；F 0.88；「每日给药蓄积约 3 倍」。
            // - Kuhnz 1993（Diane-35，15 人，3 个周期）：单次双相 t½ 0.8 h + **54.0 ± 26 h**，
            //   **第 3 周期末延长到 78.6 ± 16 h**，Vz 986 → 1 304 L，稳态第 16 天才平。
            //   作者归因于「分布进入深室 + 深室缓慢释放」。
            //
            // 关键认识：文献里那三个「半衰期」不是同一个相，而是同一组参数在不同窗口的斜率。
            // 所以参数不按「某个文献值直接抄成某一相」，而是要求整条曲线同时重现三个窗口：
            //   24→120 h 斜率 44.7 ≈ SPC 的 43.9（单次、白天段）
            //   96→168 h 斜率 53.7 ≈ Kuhnz 单次的 54.0（单次、尾巴段）
            //   稳态后 168→336 h 斜率 78.7 ≈ Kuhnz 三周期后的 78.6（长期给药）
            // 只要深室存在，线性叠加就自动产生「长期给药后终末相继续变长」——深室单次只占
            // 6.5% 的剂量，但 q24 给药时它的蓄积倍数 1/(1−e^−k_d·τ)=5.9，远高于浅室的 2.7，
            // 于是稳态后尾部改由深室主导，斜率从 53.7 h 拉到 78.7 h。全程无时间依赖参数。
            //
            // 现状数值（50 mg / 70 kg）：AUC 4 995、CL/F 10.0 L/h（总 CL 2.10 mL/min/kg）、
            // q24 稳态 峰 285 / 谷 152 / 平均 208、谷蓄积 ×3.38、峰蓄积 ×2.03；
            // 单次斜率 24→48 h 37.2、48→72 h 45.3、24→120 h 44.7、96→168 h 53.7、168→336 h 70.6。
            halfLifeHours = 4.0,
            tmaxHours = 3.0,
            massToCurveUnit = 1_000.0,
            targetToMicrogramPerLiter = 1.0,
            lowScenario = 0.5,
            highScenario = 2.0,
            evidenceLabel = "CPA 口服：三相处置（主相 4 h + 浅室 36 h + 深室 90 h）；单次表观终末 53.7 h（约 2.2 天），长期给药 78.7 h（约 3.3 天）；50 mg Cmax 140@3 h、AUC≈5 000、q24 谷蓄积 ×3.4；70 kg 时 12.5 mg/日稳态峰约 71 ng/mL，60 kg 时约 81 ng/mL；约 30 天接近平台，不随年限无限增长；12.5 mg 缺少直接实测，按 50 mg 线性外推仅作情景估算",
            slowFraction = 0.24,
            slowHalfLifeHours = 36.0,
            deepFraction = 0.065,
            deepHalfLifeHours = 90.0,
            absorptionRateOverride = 0.714
        )
    )

    // ---------------------------------------------------------------- 缓存
    // 全部为纯函数结果缓存；只在主线程/ViewModel 线程读写，用 synchronized 保护代价可忽略。
    private data class ParamsKey(
        val drug: Drug,
        val route: Route,
        val weightBits: Long,
        val exposureBits: Long,
        val eliminationBits: Long,
        val absorptionBits: Long,
        val levelOrdinal: Int
    )

    private val parameterCache = HashMap<ParamsKey, ModelParameters>()

    private data class FitCacheKey(
        val drug: Drug,
        val samplesHash: Int,
        val dosesHash: Int,
        val samplesSize: Int,
        val dosesSize: Int,
        val weightBits: Long
    )

    private val fitCache = HashMap<FitCacheKey, Map<String, IndividualPkFit>>()

    private data class PdCacheKey(
        val samplesHash: Int,
        val dosesHash: Int,
        val samplesSize: Int,
        val dosesSize: Int,
        val weightBits: Long,
        val baselineBits: Long
    )

    private val pdCache = HashMap<PdCacheKey, IndividualPdFit>()

    fun calibrationKey(drug: Drug, route: Route): String = "${drug.name}_${route.name}"

    fun parameters(
        drug: Drug,
        route: Route,
        weightKg: Double,
        individualFit: IndividualPkFit? = null
    ): ModelParameters {
        val safeRoute = if (drug == Drug.CPA) Route.ORAL else route
        val safeWeight = weightKg.coerceIn(35.0, 250.0)
        val fit = individualFit?.takeIf { it.drug == drug && it.route == safeRoute }
        val key = ParamsKey(
            drug = drug,
            route = safeRoute,
            weightBits = safeWeight.toRawBits(),
            exposureBits = (fit?.exposureFactor ?: 1.0).toRawBits(),
            eliminationBits = (fit?.eliminationFactor ?: 1.0).toRawBits(),
            absorptionBits = (fit?.absorptionFactor ?: 1.0).toRawBits(),
            levelOrdinal = fit?.level?.ordinal ?: -1
        )
        synchronized(parameterCache) { parameterCache[key]?.let { return it } }
        val computed = buildParameters(drug, safeRoute, safeWeight, fit)
        synchronized(parameterCache) {
            if (parameterCache.size > 4096) parameterCache.clear()
            parameterCache[key] = computed
        }
        return computed
    }

    private fun buildParameters(
        drug: Drug,
        safeRoute: Route,
        safeWeight: Double,
        fit: IndividualPkFit?
    ): ModelParameters {
        val profile = profile(drug, safeRoute)
        val ratio = safeWeight / profile.referenceWeightKg
        val exposure = fit?.exposureFactor ?: 1.0
        val vd = profile.referenceVd * ratio / exposure
        // 异速缩放：Vd ∝ W，CL ∝ W^0.75 → ke ∝ W^-0.25（与旧版 refCl×ratio^0.75/(refVd×ratio) 等价）
        val eliminationFactor = fit?.eliminationFactor ?: 1.0
        val ke = profile.referenceKe * ratio.pow(-0.25) * eliminationFactor
        // 慢相与快相同比例缩放：个体拟合的 eliminationFactor 是「整体消除快慢」，不该只动其中一相
        val slowKe = if (profile.dualPhase) {
            profile.referenceKeSlow * ratio.pow(-0.25) * eliminationFactor
        } else {
            0.0
        }
        // 深室相同样按整体消除快慢缩放（个体拟合不应只动其中一相）
        val deepKe = if (profile.triPhase) {
            profile.referenceKeDeep * ratio.pow(-0.25) * eliminationFactor
        } else {
            0.0
        }
        val ka = profile.ka * (fit?.absorptionFactor ?: 1.0)
        val slowFraction = if (slowKe > 0.0) profile.slowFraction else 0.0
        val deepFraction = if (deepKe > 0.0) profile.deepFraction else 0.0
        val aucShape = when {
            deepKe > 0.0 -> (1.0 - slowFraction - deepFraction) / ke +
                slowFraction / slowKe + deepFraction / deepKe
            slowKe > 0.0 -> (1.0 - slowFraction) / ke + slowFraction / slowKe
            else -> 1.0 / ke
        }
        val peakHours = if (slowKe > 0.0) {
            peakTime(ka, ke, slowKe, slowFraction, deepKe, deepFraction)
        } else {
            tmax(ka, ke)
        }
        return ModelParameters(
            drug = drug,
            route = safeRoute,
            weightKg = safeWeight,
            referenceWeightKg = profile.referenceWeightKg,
            apparentClearance = vd / aucShape,
            apparentVolume = vd,
            absorptionRate = ka,
            eliminationRate = ke,
            halfLifeHours = ln(2.0) / ke,
            tmaxHours = peakHours,
            referenceDoseMg = profile.referenceDoseMg,
            referenceCmax = profile.referenceCmax,
            slowEliminationRate = slowKe,
            slowFraction = slowFraction,
            slowHalfLifeHours = if (slowKe > 0.0) ln(2.0) / slowKe else 0.0,
            slowAucShare = if (slowKe > 0.0) (slowFraction / slowKe) / aucShape else 0.0,
            deepEliminationRate = deepKe,
            deepFraction = deepFraction,
            deepHalfLifeHours = if (deepKe > 0.0) ln(2.0) / deepKe else 0.0,
            deepAucShare = if (deepKe > 0.0) (deepFraction / deepKe) / aucShape else 0.0,
            apparentHalfLifeHours = halfLifeFromPeak(ka, ke, slowKe, slowFraction, peakHours, deepKe, deepFraction),
            apparentTerminalHalfLifeHours = if (deepKe > 0.0) {
                terminalSlopeHalfLife(ka, ke, slowKe, slowFraction, deepKe, deepFraction)
            } else {
                0.0
            },
            longTermTerminalHalfLifeHours = if (deepKe > 0.0) {
                steadyStateTerminalHalfLife(ka, ke, slowKe, slowFraction, deepKe, deepFraction)
            } else {
                0.0
            },
            evidenceLabel = profile.evidenceLabel + if (fit != null && fit.sampleCount > 0) "；${fit.level.label}" else ""
        )
    }

    fun concentrationAt(
        event: DoseEvent,
        atMillis: Long,
        weightKg: Double,
        calibrationFactor: Double = 1.0,
        individualFit: IndividualPkFit? = null
    ): Double {
        val elapsedHours = (atMillis - event.takenAtMillis) / 3_600_000.0
        if (elapsedHours < 0.0) return 0.0
        val p = parameters(event.drug, event.route, weightKg, individualFit)
        val profile = profile(event.drug, if (event.drug == Drug.CPA) Route.ORAL else event.route)
        val shape = dispositionShape(
            hours = elapsedHours,
            ka = p.absorptionRate,
            fastKe = p.eliminationRate,
            slowKe = p.slowEliminationRate,
            slowFraction = p.slowFraction,
            deepKe = p.deepEliminationRate,
            deepFraction = p.deepFraction
        )
        return event.doseMg * profile.massToCurveUnit / p.apparentVolume * shape * calibrationFactor
    }

    /**
     * 把一条给药记录预编译成「幅度 + 各相速率」，之后算浓度只剩几次 exp。
     * 未编译时每个点都要重算 parameters，是旧版卡顿的主因。
     */
    private data class DoseKernel(
        val takenAtMillis: Long,
        val amplitude: Double,
        val eliminationRate: Double,
        val absorptionRate: Double,
        val cutoffHours: Double,
        /** 慢相幅度；单相药物为 0，此时 [slowEliminationRate] 也不参与计算。 */
        val slowAmplitude: Double = 0.0,
        val slowEliminationRate: Double = 0.0,
        /** 深室幅度；只有 CPA 的三相模型非 0。 */
        val deepAmplitude: Double = 0.0,
        val deepEliminationRate: Double = 0.0
    )

    private fun compile(
        drug: Drug,
        doses: List<DoseEvent>,
        weightKg: Double,
        calibration: Map<String, Double> = emptyMap(),
        fits: Map<String, IndividualPkFit> = emptyMap()
    ): List<DoseKernel> {
        val out = ArrayList<DoseKernel>(doses.size)
        doses.forEach { event ->
            if (event.drug != drug) return@forEach
            val route = if (event.drug == Drug.CPA) Route.ORAL else event.route
            val key = calibrationKey(event.drug, route)
            val p = parameters(event.drug, route, weightKg, fits[key])
            val pkProfile = profile(event.drug, route)
            val factor = calibration[key] ?: 1.0
            val unitPerVolume = pkProfile.massToCurveUnit / p.apparentVolume * factor
            val fastCoef = fastCoefficient(
                p.absorptionRate, p.eliminationRate, p.slowEliminationRate, p.slowFraction, p.deepFraction
            )
            val slowCoef = slowCoefficient(p.absorptionRate, p.eliminationRate, p.slowEliminationRate, p.slowFraction)
            val deepCoef = deepCoefficient(p.absorptionRate, p.deepEliminationRate, p.deepFraction)
            val amplitude = event.doseMg * unitPerVolume * fastCoef
            val slowAmplitude = event.doseMg * unitPerVolume * slowCoef
            val deepAmplitude = event.doseMg * unitPerVolume * deepCoef
            val fastCutoff = KERNEL_DECAY_CUTOFF * ln(2.0) / p.eliminationRate
            val slowCutoff = if (p.slowEliminationRate > 0.0) {
                KERNEL_DECAY_CUTOFF * ln(2.0) / p.slowEliminationRate
            } else {
                0.0
            }
            val deepCutoff = if (p.deepEliminationRate > 0.0) {
                KERNEL_DECAY_CUTOFF * ln(2.0) / p.deepEliminationRate
            } else {
                0.0
            }
            out += DoseKernel(
                takenAtMillis = event.takenAtMillis,
                amplitude = amplitude,
                eliminationRate = p.eliminationRate,
                absorptionRate = p.absorptionRate,
                cutoffHours = maxOf(fastCutoff, slowCutoff, deepCutoff),
                slowAmplitude = slowAmplitude,
                slowEliminationRate = p.slowEliminationRate,
                deepAmplitude = deepAmplitude,
                deepEliminationRate = p.deepEliminationRate
            )
        }
        return out
    }

    private fun concentrationOf(kernels: List<DoseKernel>, atMillis: Long): Double {
        var sum = 0.0
        kernels.forEach { kernel ->
            val hours = (atMillis - kernel.takenAtMillis) / 3_600_000.0
            if (hours < 0.0 || hours > kernel.cutoffHours) return@forEach
            val decay = exp(-kernel.absorptionRate * hours)
            sum += kernel.amplitude * (exp(-kernel.eliminationRate * hours) - decay)
            if (kernel.slowAmplitude != 0.0) {
                sum += kernel.slowAmplitude * (exp(-kernel.slowEliminationRate * hours) - decay)
            }
            if (kernel.deepAmplitude != 0.0) {
                sum += kernel.deepAmplitude * (exp(-kernel.deepEliminationRate * hours) - decay)
            }
        }
        return sum
    }

    fun totalConcentration(
        drug: Drug,
        atMillis: Long,
        doses: List<DoseEvent>,
        weightKg: Double,
        calibration: Map<String, Double> = emptyMap(),
        individualFits: Map<String, IndividualPkFit> = emptyMap()
    ): Double {
        if (doses.isEmpty()) return 0.0
        val scoped = doses.filter { it.drug == drug && it.takenAtMillis <= atMillis }
        if (scoped.isEmpty()) return 0.0
        return concentrationOf(compile(drug, scoped, weightKg, calibration, individualFits), atMillis)
    }

    /** Legacy exposure-only diagnostic; new curves use individualFit below. */
    fun calibrate(
        drug: Drug,
        route: Route,
        samples: List<LabSample>,
        doses: List<DoseEvent>,
        weightKg: Double
    ): CalibrationResult {
        val expectedAnalyte = if (drug == Drug.ESTRADIOL_VALERATE) Analyte.E2 else Analyte.CPA
        val ratios = samples.filter { sample ->
            sample.analyte == expectedAnalyte && sample.drug == drug && sample.route == route
        }.mapNotNull { sample ->
            val predicted = totalConcentration(drug, sample.sampledAtMillis, doses, weightKg)
            if (predicted > 0.0001 && sample.value > 0.0) sample.value / predicted else null
        }
        if (ratios.isEmpty()) return CalibrationResult(1.0, 0, emptyList())
        val geometricMean = exp(ratios.map { ln(it) }.average()).coerceIn(0.2, 5.0)
        return CalibrationResult(geometricMean, ratios.size, ratios)
    }

    fun individualFit(
        drug: Drug,
        route: Route,
        samples: List<LabSample>,
        doses: List<DoseEvent>,
        weightKg: Double,
        otherFits: Map<String, IndividualPkFit> = emptyMap()
    ): IndividualPkFit {
        val safeRoute = if (drug == Drug.CPA) Route.ORAL else route
        if (otherFits.isEmpty()) {
            return individualFits(drug, samples, doses, weightKg)[calibrationKey(drug, safeRoute)]
                ?: IndividualPkFit(drug, safeRoute)
        }
        return individualFitRaw(drug, safeRoute, samples, doses, weightKg, otherFits)
    }

    private fun individualFitRaw(
        drug: Drug,
        route: Route,
        samples: List<LabSample>,
        doses: List<DoseEvent>,
        weightKg: Double,
        otherFits: Map<String, IndividualPkFit>
    ): IndividualPkFit {
        val safeRoute = if (drug == Drug.CPA) Route.ORAL else route
        val key = calibrationKey(drug, safeRoute)
        val expected = if (drug == Drug.ESTRADIOL_VALERATE) Analyte.E2 else Analyte.CPA
        val ownDoses = doses.filter { it.drug == drug && (if (drug == Drug.CPA) Route.ORAL else it.route) == safeRoute }
        if (ownDoses.isEmpty()) return IndividualPkFit(drug, safeRoute)
        // 不再要求化验点必须挂在“仍然存在的”那条给药记录上：删掉记录后，
        // 只要该时间点之前仍有同药同途径的给药，预测浓度依然成立，点仍可用。
        val usable = samples.filter { sample ->
            sample.analyte == expected && sample.drug == drug && sample.route == safeRoute &&
                sample.value > 0.0 &&
                ownDoses.any { it.takenAtMillis <= sample.sampledAtMillis }
        }.distinctBy { it.id }
        if (usable.isEmpty()) return IndividualPkFit(drug, safeRoute)

        val oldestSample = usable.minOf { it.sampledAtMillis }
        val scopedDoses = doses.filter { it.takenAtMillis >= oldestSample - (RELEVANCE_DAYS * 86_400_000L).toLong() }
            .ifEmpty { doses }

        val base = parameters(drug, safeRoute, weightKg)
        val doseCount = usable.map { it.relatedDoseId }.distinct().size
        val elapsed = usable.map { it.elapsedHours }
        val hasLate = (elapsed.maxOrNull() ?: 0.0) > base.tmaxHours * 1.5
        val hasEarly = (elapsed.minOrNull() ?: Double.MAX_VALUE) < base.tmaxHours * 0.8
        // 只要有一个点就拟合暴露；参数可识别度只决定"能放开几个参数"，不作为是否能拟合的前提。
        val level = when {
            usable.size >= 3 && hasEarly && hasLate -> IndividualFitLevel.FULL_PK
            usable.size >= 2 && hasLate -> IndividualFitLevel.ELIMINATION
            else -> IndividualFitLevel.EXPOSURE
        }
        val activeCount = when (level) {
            IndividualFitLevel.EXPOSURE -> 1
            IndividualFitLevel.ELIMINATION -> 2
            IndividualFitLevel.FULL_PK -> 3
            IndividualFitLevel.POPULATION -> 0
        }
        val lower = doubleArrayOf(ln(0.2), ln(1.0 / 3.0), ln(1.0 / 3.0))
        val upper = doubleArrayOf(ln(5.0), ln(3.0), ln(3.0))
        // 点少时把先验收得更紧，避免 2–3 个点就把消除/吸收拉走；点多了再逐步放开。
        val shrink = when {
            level == IndividualFitLevel.FULL_PK && usable.size < 5 -> 0.6
            level == IndividualFitLevel.ELIMINATION && usable.size < 3 -> 0.7
            else -> 1.0
        }
        val priorSigma = doubleArrayOf(0.70, 0.35 * shrink, 0.45 * shrink)

        fun candidate(x: DoubleArray) = IndividualPkFit(
            drug = drug,
            route = safeRoute,
            level = level,
            exposureFactor = exp(x[0]),
            eliminationFactor = if (activeCount >= 2) exp(x[1]) else 1.0,
            absorptionFactor = if (activeCount >= 3) exp(x[2]) else 1.0,
            sampleCount = usable.size,
            distinctDoseCount = doseCount
        )

        fun objective(x: DoubleArray): Double {
            val fit = candidate(x)
            val fits = otherFits + (key to fit)
            // 一次编译，多个采样点复用：避免每个点都把全部剂量重新编译一遍
            val kernels = compile(drug, scopedDoses, weightKg, emptyMap(), fits)
            val residuals = usable.sumOf { sample ->
                val predicted = concentrationOf(kernels, sample.sampledAtMillis)
                if (predicted <= 0.0001) 100.0 else {
                    val residual = ln(sample.value / predicted) / 0.30
                    residual * residual
                }
            }
            val priors = (0 until activeCount).sumOf { index ->
                val penalty = x[index] / priorSigma[index]
                penalty * penalty
            }
            return residuals + priors
        }

        val initialRatio = usable.map { sample ->
            val predicted = totalConcentration(drug, sample.sampledAtMillis, scopedDoses, weightKg, individualFits = otherFits)
            ln(sample.value / predicted.coerceAtLeast(0.0001))
        }.average().coerceIn(lower[0], upper[0])
        val optimum = optimizeLogParameters(DoubleArray(3) { if (it == 0) initialRatio else 0.0 }, activeCount, lower, upper, ::objective)
        val fitted = candidate(optimum)
        val finalKernels = compile(drug, scopedDoses, weightKg, emptyMap(), otherFits + (key to fitted))
        val rmse = sqrt(usable.map { sample ->
            ln(sample.value / concentrationOf(finalKernels, sample.sampledAtMillis).coerceAtLeast(0.0001)).let { it * it }
        }.average())
        return fitted.copy(logRmse = rmse)
    }

    fun individualFits(
        drug: Drug,
        samples: List<LabSample>,
        doses: List<DoseEvent>,
        weightKg: Double
    ): Map<String, IndividualPkFit> {
        val cacheKey = FitCacheKey(
            drug = drug,
            samplesHash = samples.hashCode(),
            dosesHash = doses.hashCode(),
            samplesSize = samples.size,
            dosesSize = doses.size,
            weightBits = weightKg.toRawBits()
        )
        synchronized(fitCache) { fitCache[cacheKey]?.let { return it } }
        val computed = fitRoutes(drug, samples, doses, weightKg)
        synchronized(fitCache) {
            if (fitCache.size > 16) fitCache.clear()
            fitCache[cacheKey] = computed
        }
        return computed
    }

    private fun fitRoutes(
        drug: Drug,
        samples: List<LabSample>,
        doses: List<DoseEvent>,
        weightKg: Double
    ): Map<String, IndividualPkFit> {
        val routes = doses.filter { it.drug == drug }
            .map { if (drug == Drug.CPA) Route.ORAL else it.route }
            .distinct()
        if (routes.isEmpty()) return emptyMap()
        var fits = emptyMap<String, IndividualPkFit>()
        repeat(2) {
            routes.forEach { route ->
                val key = calibrationKey(drug, route)
                fits = fits + (key to individualFitRaw(drug, route, samples, doses, weightKg, fits - key))
            }
        }
        return fits
    }

    /**
     * 给 UI 的「下一步能识别什么」提示。只描述参数可识别性，不构成拟合前提：
     * 哪怕只有 1 个点，也已经在校准暴露。
     */
    fun fitHint(fit: IndividualPkFit, drug: Drug, route: Route, weightKg: Double): String {
        if (fit.sampleCount == 0) {
            return "还没有可用实测点。记录任意一次给药后的采血值，第一个点保存后就会立即参与拟合。"
        }
        val tmax = parameters(drug, if (drug == Drug.CPA) Route.ORAL else route, weightKg).tmaxHours
        return when (fit.level) {
            IndividualFitLevel.POPULATION, IndividualFitLevel.EXPOSURE ->
                "已用 ${fit.sampleCount} 个点校准整体暴露。若再有一个 Tmax 之后（约 ${"%.0f".format(tmax * 1.5)} h 以上）的点，就能开始估消除。"
            IndividualFitLevel.ELIMINATION ->
                "已加入消除估计。若再有 1 个峰前点（约 ${"%.0f".format(tmax * 0.8)} h 以内）并覆盖一次完整给药周期，就能开始估吸收。"
            IndividualFitLevel.FULL_PK ->
                "暴露、消除、吸收都已参与拟合。继续补不同日期或不同剂量的点只会让参数更稳，不是继续拟合的前提。"
        }
    }

    /**
     * 睾酮 PD 拟合。**不再要求未治疗基线**：
     * 没有基线时，把基线本身当作待估参数（带群体先验 550 ng/dL），
     * 与 Emax 一起用治疗中的 T 实测点拟合；只有 1 个点时就退化为「基线锚定」（Emax 取先验）。
     */
    fun testosteroneFit(
        samples: List<LabSample>,
        doses: List<DoseEvent>,
        profile: UserProfile
    ): IndividualPdFit {
        val baselineGiven = profile.baselineTestosteroneNgDl?.takeIf { it > 0.0 }
        val usable = samples.filter { it.analyte == Analyte.TESTOSTERONE && toNgDl(it.value, it.unit) > 0.0 }
            .distinctBy { it.id }
        if (usable.isEmpty()) return IndividualPdFit(baselineNgDl = baselineGiven)

        val cacheKey = PdCacheKey(
            samplesHash = samples.hashCode(),
            dosesHash = doses.hashCode(),
            samplesSize = samples.size,
            dosesSize = doses.size,
            weightBits = profile.weightKg.toRawBits(),
            baselineBits = baselineGiven?.toRawBits() ?: 0L
        )
        synchronized(pdCache) { pdCache[cacheKey]?.let { return it } }

        val computed = computeTestosteroneFit(usable, doses, profile, baselineGiven)
        synchronized(pdCache) {
            if (pdCache.size > 16) pdCache.clear()
            pdCache[cacheKey] = computed
        }
        return computed
    }

    private fun computeTestosteroneFit(
        usable: List<LabSample>,
        doses: List<DoseEvent>,
        profile: UserProfile,
        baselineGiven: Double?
    ): IndividualPdFit {
        val oldest = usable.minOf { it.sampledAtMillis }
        val scopedDoses = doses.filter { it.takenAtMillis >= oldest - (RELEVANCE_DAYS * 86_400_000L).toLong() }
        val pkFits = individualFits(Drug.ESTRADIOL_VALERATE, usable, scopedDoses, profile.weightKg) +
            individualFits(Drug.CPA, usable, scopedDoses, profile.weightKg)
        val e2Kernels = compile(Drug.ESTRADIOL_VALERATE, scopedDoses, profile.weightKg, emptyMap(), pkFits)
        val cpaKernels = compile(Drug.CPA, scopedDoses, profile.weightKg, emptyMap(), pkFits)
        // 每个实测点的驱动量只算一次：优化器每次迭代都重算会让 PD 拟合慢一个数量级。
        val drives = usable.map {
            testosteroneDrive(it.sampledAtMillis, profile.weightKg, e2Kernels, cpaKernels)
        }
        val anyDrive = drives.any { it.cpaEffect > 0.000001 || it.e2MeanPgMl > 0.000001 }
        if (!anyDrive) return IndividualPdFit(baselineNgDl = baselineGiven)

        val observed = usable.map { toNgDl(it.value, it.unit) }
        val e2Means = drives.map { it.e2MeanPgMl }
        val mean = e2Means.average()
        val cv = if (mean > 0.0) sqrt(e2Means.map { (it - mean) * (it - mean) }.average()) / mean else 0.0
        val distinctDays = usable.map { it.sampledAtMillis / 86_400_000L }.distinct().size

        val fitBaseline = baselineGiven == null
        val fitSensitivity = usable.size >= 3 && distinctDays >= 2 && cv >= 0.10
        val fitEmax = baselineGiven != null || usable.size >= 2

        // 参数槽顺序：[ln 基线]（仅无基线时）→ Emax → ln 敏感度
        val slots = ArrayList<Int>(3)
        if (fitBaseline) slots += SLOT_BASELINE
        if (fitEmax) slots += SLOT_EMAX
        if (fitSensitivity) slots += SLOT_SENSITIVITY

        val lower = DoubleArray(slots.size)
        val upper = DoubleArray(slots.size)
        val start = DoubleArray(slots.size)
        slots.forEachIndexed { index, slot ->
            when (slot) {
                SLOT_BASELINE -> {
                    lower[index] = ln(50.0)
                    upper[index] = ln(2000.0)
                    start[index] = ln(DEFAULT_T_BASELINE_NG_DL)
                }
                SLOT_EMAX -> {
                    lower[index] = 0.02
                    upper[index] = 0.98
                    start[index] = 0.93
                }
                else -> {
                    lower[index] = ln(0.1)
                    upper[index] = ln(10.0)
                    start[index] = 0.0
                }
            }
        }

        fun decode(x: DoubleArray): Triple<Double, Double, Double> {
            var baseline = baselineGiven ?: DEFAULT_T_BASELINE_NG_DL
            var emax = 0.93
            var sensitivity = 1.0
            slots.forEachIndexed { index, slot ->
                when (slot) {
                    SLOT_BASELINE -> baseline = exp(x[index])
                    SLOT_EMAX -> emax = x[index]
                    else -> sensitivity = exp(x[index])
                }
            }
            return Triple(baseline, emax, sensitivity)
        }

        fun objective(x: DoubleArray): Double {
            val (baseline, emax, sensitivity) = decode(x)
            var residual = 0.0
            drives.forEachIndexed { index, drive ->
                val predicted = baseline * remainingFromDrive(drive, emax, sensitivity)
                val r = ln(observed[index] / predicted.coerceAtLeast(0.01)) / 0.25
                residual += r * r
            }
            slots.forEachIndexed { index, slot ->
                val penalty = when (slot) {
                    SLOT_BASELINE -> (x[index] - ln(DEFAULT_T_BASELINE_NG_DL)) / 0.55
                    SLOT_EMAX -> (x[index] - 0.93) / 0.25
                    else -> x[index] / 0.70
                }
                residual += penalty * penalty
            }
            return residual
        }

        val optimum = optimizeLogParameters(start, slots.size, lower, upper, ::objective)
        val (baseline, emax, sensitivity) = decode(optimum)
        val rmse = sqrt(drives.mapIndexed { index, drive ->
            val predicted = baseline * remainingFromDrive(drive, emax, sensitivity)
            ln(observed[index] / predicted.coerceAtLeast(0.01)).let { it * it }
        }.average())

        val level = when {
            fitBaseline && !fitEmax -> PdFitLevel.ANCHOR
            fitSensitivity -> PdFitLevel.EMAX_EC50
            else -> PdFitLevel.EMAX
        }
        return IndividualPdFit(
            level = level,
            emax = emax,
            e2Sensitivity = sensitivity,
            sampleCount = usable.size,
            logRmse = rmse,
            baselineNgDl = baseline,
            baselineFitted = fitBaseline
        )
    }

    fun maintenanceDose(
        drug: Drug,
        route: Route,
        weightKg: Double,
        clinicianTarget: Double,
        intervalHours: Double
    ): Double? {
        if (clinicianTarget <= 0.0 || intervalHours <= 0.0) return null
        val p = parameters(drug, route, weightKg)
        val conversion = profile(drug, if (drug == Drug.CPA) Route.ORAL else route).targetToMicrogramPerLiter
        return p.apparentClearance * clinicianTarget * conversion * intervalHours / 1000.0
    }

    /**
     * 单次给药的 AUC0-∞ = D × K ÷ CL/F（因为 CL/F 本身就是由 D/AUC 定义的）。
     * 界面上用它把「文献 AUC」和「当前模型 AUC」并排显示，避免把某个写死的数字抄进文案。
     */
    fun singleDoseAuc(
        drug: Drug,
        route: Route,
        doseMg: Double,
        weightKg: Double,
        individualFit: IndividualPkFit? = null
    ): Double {
        if (doseMg <= 0.0) return 0.0
        val safeRoute = if (drug == Drug.CPA) Route.ORAL else route
        val p = parameters(drug, safeRoute, weightKg, individualFit)
        return doseMg * profile(drug, safeRoute).massToCurveUnit / p.apparentClearance
    }

    fun curve(
        analyte: Analyte,
        doses: List<DoseEvent>,
        labs: List<LabSample>,
        profile: UserProfile,
        calibration: Map<String, Double>,
        horizonHours: Int,
        referenceMillis: Long = System.currentTimeMillis()
    ): CurveSeries? {
        if (doses.isEmpty()) return null
        val anchor = referenceMillis
        val historyHours = horizonHours / 2.0
        val times = 0..160
        val scopedDoses = doses.filter {
            it.takenAtMillis >= anchor - (RELEVANCE_DAYS * 86_400_000L).toLong()
        }
        if (analyte == Analyte.TESTOSTERONE) {
            val startDate = effectiveHrtStartDate(doses) ?: return null
            val anchorDate = Instant.ofEpochMilli(anchor).atZone(ZoneId.systemDefault()).toLocalDate()
            if (ChronoUnit.DAYS.between(startDate, anchorDate) < 90) return null
            val pkFits = individualFits(Drug.ESTRADIOL_VALERATE, labs, scopedDoses, profile.weightKg) +
                individualFits(Drug.CPA, labs, scopedDoses, profile.weightKg)
            val pdFit = testosteroneFit(labs, doses, profile)
            val e2Kernels = compile(Drug.ESTRADIOL_VALERATE, scopedDoses, profile.weightKg, emptyMap(), pkFits)
            val cpaKernels = compile(Drug.CPA, scopedDoses, profile.weightKg, emptyMap(), pkFits)
            val baseline = pdFit.baselineNgDl ?: DEFAULT_T_BASELINE_NG_DL
            val hasIndividualPd = pdFit.sampleCount > 0
            val points = times.map { index ->
                val hours = -historyHours + horizonHours * index / 160.0
                val at = anchor + (hours * 3_600_000.0).toLong()
                val drive = testosteroneDrive(at, profile.weightKg, e2Kernels, cpaKernels)
                val remaining = remainingFromDrive(drive, pdFit.emax, pdFit.e2Sensitivity)
                val value = baseline * remaining
                CurvePoint(hours, value, value * 0.5, value * 1.8)
            }
            val labPoints = labs.filter { it.analyte == analyte }.mapNotNull { sample ->
                val hours = (sample.sampledAtMillis - anchor) / 3_600_000.0
                if (hours in -historyHours..(horizonHours - historyHours)) {
                    LabPoint(hours, toNgDl(sample.value, sample.unit), "实测 T")
                } else null
            }
            val windowNotice = "当前时间为 0：左 ${"%.0f".format(historyHours)} h 为历史回溯，右 ${"%.0f".format(horizonHours - historyHours)} h 为未来预测。T 由 7 日平均 E2 与 7 日平均 CPA 血药浓度共同驱动：CPA 浓度先折算成等效日剂量，再按 HPG 轴饱和函数压低 T，约 10–12.5 mg/日以上进入平台（ENIGI 882 例：10/25/50/100 mg 的 T 均约 0.9–1.1 nmol/L）。因为走的是浓度而不是剂量，停用 CPA 后抑睾效应随 43.9 h 半衰期逐步消退，不会在漏服当天就跳回基线。"
            val notice = windowNotice + when {
                hasIndividualPd && pdFit.baselineFitted ->
                    "个体 PD：${pdFit.level.label}，${pdFit.sampleCount} 个 T 点同时估出未治疗基线 ${"%.0f".format(baseline)} ng/dL（受群体先验约束），对数 RMSE ${"%.2f".format(pdFit.logRmse)}；训练内误差不是预测保证。"
                hasIndividualPd ->
                    "个体 PD：${pdFit.level.label}，${pdFit.sampleCount} 个 T 点，对数 RMSE ${"%.2f".format(pdFit.logRmse)}；训练内误差不是预测保证。"
                profile.baselineTestosteroneNgDl != null ->
                    "已输入基线 T，但尚无可用治疗中 T 点：当前仍是未验证的 Emax 情景。"
                else ->
                    "暂无治疗中 T 实测点：纵轴按群体参考基线 ${"%.0f".format(DEFAULT_T_BASELINE_NG_DL)} ng/dL 的情景刻度显示，绝对值不代表本人；录入任意一个 T 实测点后会立即拟合基线与 Emax。"
            }
            return CurveSeries(
                analyte, "ng/dL", points, labPoints,
                notice = notice,
                quantitative = false,
                anchoredByLab = hasIndividualPd
            )
        }

        val drug = if (analyte == Analyte.E2) Drug.ESTRADIOL_VALERATE else Drug.CPA
        // 只有很久以前记录的用户也要能看到曲线：截断窗口为空时回退到该药全部记录
        val relevant = scopedDoses.filter { it.drug == drug }.ifEmpty { doses.filter { it.drug == drug } }
        if (relevant.isEmpty()) return null
        val fits = individualFits(drug, labs, scopedDoses, profile.weightKg)
        val kernels = compile(drug, relevant, profile.weightKg, calibration, fits)
        val points = times.map { index ->
            val hours = -historyHours + horizonHours * index / 160.0
            val at = anchor + (hours * 3_600_000.0).toLong()
            val value = concentrationOf(kernels, at)
            val scenario = relevant.minByOrNull { abs(it.takenAtMillis - at) }?.let {
                profile(it.drug, if (it.drug == Drug.CPA) Route.ORAL else it.route)
            } ?: profile(drug, Route.ORAL)
            CurvePoint(hours, value, value * scenario.lowScenario, value * scenario.highScenario)
        }
        val labPoints = labs.filter { it.analyte == analyte }.mapNotNull { sample ->
            val hours = (sample.sampledAtMillis - anchor) / 3_600_000.0
            if (hours in -historyHours..(horizonHours - historyHours)) LabPoint(hours, sample.value, "实测") else null
        }
        val usedPoints = fits.values.filter { it.drug == drug }.sumOf { it.sampleCount }
        val windowNotice = "当前时间为 0：左 ${"%.0f".format(historyHours)} h 为历史回溯，右 ${"%.0f".format(horizonHours - historyHours)} h 为未来预测。长期重复给药按所有历史记录线性叠加，CPA 这类长半衰期药物会显示蓄积；这不计入漏服、饱和或清除率变化。"
        val bioNotice = if (analyte == Analyte.E2) {
            " 口服与舌下各自用本途径的实测 Cmax / Tmax / t½ 锚定，表观 Vd/F、CL/F 已包含生物利用度差异；" +
                "T 抑制也用同一条曲线（不再额外乘 F，避免与实测峰值重复计入）。"
        } else ""
        return CurveSeries(
            analyte = analyte,
            unit = analyte.defaultUnit,
            points = points,
            labPoints = labPoints,
            notice = windowNotice + bioNotice + when {
                usedPoints > 0 -> "已自动应用仅属于本机当前记录的个体 PK 拟合（$usedPoints 点）；阴影仍为宽情景范围，不是置信区间。"
                analyte == Analyte.E2 -> "尚无可用 E2 实测点，当前为群体先验；阴影是情景范围，不是统计置信区间。"
                else -> "尚无可用 CPA 实测点；曲线按三相处置（主相 4 h + 浅室 36 h + 深室 90 h）锚定药品资料，单次终末 53.7 h、长期 78.7 h。蓄积会达到有限平台，不随服药年限无限上升；峰到服药后 24 h 谷的下降包含吸收/分布相，不能用这个比值反推终末半衰期。"
            },
            quantitative = true
        )
    }

    /**
     * HRT 起点完全由给药记录决定：取最早一条记录所在的日期。
     * 不再读取个人资料里的开始日期（该输入已从界面移除），避免补录历史后仍被旧日期卡住 90 天门槛。
     */
    fun effectiveHrtStartDate(doses: List<DoseEvent>): LocalDate? = doses.minOfOrNull {
        Instant.ofEpochMilli(it.takenAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    fun hrtDays(doses: List<DoseEvent>, atMillis: Long = System.currentTimeMillis()): Long {
        val start = effectiveHrtStartDate(doses) ?: return 0
        val date = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return ChronoUnit.DAYS.between(start, date)
    }

    fun hasPlateau(atMillis: Long, doses: List<DoseEvent> = emptyList()): Boolean {
        return hrtDays(doses, atMillis) >= 90
    }

    private data class TestosteroneDrive(
        val e2MeanPgMl: Double,
        /** 7 日平均 CPA 浓度折算出的等效日剂量（mg/day），稳态下等于实际日剂量。 */
        val cpaDriveMg: Double,
        val cpaEffect: Double
    )

    /**
     * 7 日窗口内血药的平均浓度，对双相曲线在窗口内解析积分。
     *
     * 这里直接用曲线本身的幅度，不再乘任何「生物利用度」系数：Cmax 锚点本身就是该途径
     * 的实测血药浓度（舌下 144 pg/mL 由 LC-MS/MS 测得），F 已经包含在锚点里；
     * 再乘一次会让 PD 由 2.2 倍于用户所见浓度的量驱动，两条曲线互相矛盾。
     */
    private fun windowMean(
        endMillis: Long,
        kernels: List<DoseKernel>,
        windowHours: Double = 24.0 * PD_WINDOW_DAYS
    ): Double {
        var sum = 0.0
        kernels.forEach { kernel ->
            val ageEnd = (endMillis - kernel.takenAtMillis) / 3_600_000.0
            if (ageEnd <= 0.0) return@forEach
            // 早已洗脱完的剂量对窗口内平均无贡献
            if (ageEnd > kernel.cutoffHours && ageEnd > windowHours) return@forEach
            val ke = kernel.eliminationRate
            val ka = kernel.absorptionRate
            val ageStart = (ageEnd - windowHours).coerceAtLeast(0.0)
            val meanShape = (exp(-ke * ageStart) - exp(-ke * ageEnd)) / ke -
                (exp(-ka * ageStart) - exp(-ka * ageEnd)) / ka
            sum += kernel.amplitude * meanShape / windowHours
            if (kernel.slowAmplitude != 0.0) {
                val ks = kernel.slowEliminationRate
                val slowShape = (exp(-ks * ageStart) - exp(-ks * ageEnd)) / ks -
                    (exp(-ka * ageStart) - exp(-ka * ageEnd)) / ka
                sum += kernel.slowAmplitude * slowShape / windowHours
            }
            if (kernel.deepAmplitude != 0.0) {
                val kd = kernel.deepEliminationRate
                val deepShape = (exp(-kd * ageStart) - exp(-kd * ageEnd)) / kd -
                    (exp(-ka * ageStart) - exp(-ka * ageEnd)) / ka
                sum += kernel.deepAmplitude * deepShape / windowHours
            }
        }
        return sum.coerceAtLeast(0.0)
    }

    /**
     * 把 7 日平均 CPA 浓度折算成「等效日剂量」（mg/day）：
     * 基准是同一体重下 50 mg q24 稳态的 7 日均值（= 单次 AUC ÷ 24）。
     *
     * 稳态下 等效剂量 ≡ 实际日剂量（浓度与剂量成正比），所以 ENIGI 的剂量-抑睾平台
     * （10/25/50/100 mg 的 T 均 0.9–1.1 nmol/L）保持不变；差别只出现在停药、漏服、
     * 刚开始服药的时间过程上，而那个时间过程现在由 CPA 自己的 43.9 h 曲线给出。
     */
    private fun cpaDriveEquivalentMg(atMillis: Long, cpaKernels: List<DoseKernel>, weightKg: Double): Double {
        if (cpaKernels.isEmpty()) return 0.0
        val mean = windowMean(atMillis, cpaKernels)
        val referenceMean = singleDoseAuc(Drug.CPA, Route.ORAL, 50.0, weightKg) / 24.0
        if (mean <= 0.0 || referenceMean <= 0.0) return 0.0
        return (50.0 * mean / referenceMean).coerceIn(0.0, 300.0)
    }

    private fun testosteroneDrive(
        atMillis: Long,
        weightKg: Double,
        e2Kernels: List<DoseKernel>,
        cpaKernels: List<DoseKernel>
    ): TestosteroneDrive {
        val e2Mean = windowMean(atMillis, e2Kernels)
        val cpaDrive = cpaDriveEquivalentMg(atMillis, cpaKernels, weightKg)
        val cpaEffect = (1.0 - exp(-cpaDrive / CPA_DAILY_SCALE_MG)).coerceIn(0.0, 1.0)
        return TestosteroneDrive(e2Mean, cpaDrive, cpaEffect)
    }

    private fun remainingFromDrive(
        drive: TestosteroneDrive,
        emax: Double,
        e2Sensitivity: Double
    ): Double {
        val ratio = (drive.e2MeanPgMl * e2Sensitivity / E2_REFERENCE_PG_ML).coerceAtLeast(0.0)
        val powered = ratio.pow(E2_HILL)
        val estrogenEffect = if (powered <= 0.0) 0.0 else powered / (1.0 + powered)
        val combined = 1.0 - (1.0 - estrogenEffect) * (1.0 - drive.cpaEffect)
        return (1.0 - emax * combined).coerceIn(0.02, 1.0)
    }

    /**
     * 数值稳态：按 τ 重复给药 40 次后取一个间隔内的峰、谷与平均。
     *
     * 为什么不用闭式 1/(1−e^−ke·τ)：那个公式只在「单相 + 峰时远小于 τ」时成立。
     * 舌下 E2（t½ 0.8 h）会给出 ×1.00 的荒谬值，实际峰蓄积是 1.09（q24）/1.24（q12）/1.40（q8）；
     * 口服 EV 因为 tmax 10 h 与 τ 同量级，闭式 ×1.46 也低于实测 ×1.67。
     */
    data class SteadyStateMetrics(
        val peak: Double,
        val trough: Double,
        val average: Double,
        val singlePeak: Double,
        /** 稳态峰 / 首剂峰 */
        val peakAccumulation: Double,
        /** 稳态谷 / 首剂谷 */
        val troughAccumulation: Double
    )

    fun steadyState(
        drug: Drug,
        route: Route,
        doseMg: Double,
        weightKg: Double,
        intervalHours: Double,
        doses: Int = 40,
        individualFit: IndividualPkFit? = null
    ): SteadyStateMetrics? {
        if (doseMg <= 0.0 || intervalHours <= 0.0 || doses < 2) return null
        val safeRoute = if (drug == Drug.CPA) Route.ORAL else route
        val p = parameters(drug, safeRoute, weightKg, individualFit)
        val pkProfile = profile(drug, safeRoute)
        val unitPerVolume = pkProfile.massToCurveUnit / p.apparentVolume
        val fastCoef = fastCoefficient(
            p.absorptionRate, p.eliminationRate, p.slowEliminationRate, p.slowFraction, p.deepFraction
        )
        val slowCoef = slowCoefficient(p.absorptionRate, p.eliminationRate, p.slowEliminationRate, p.slowFraction)
        val deepCoef = deepCoefficient(p.absorptionRate, p.deepEliminationRate, p.deepFraction)
        val fastAmp = doseMg * unitPerVolume * fastCoef
        val slowAmp = doseMg * unitPerVolume * slowCoef
        val deepAmp = doseMg * unitPerVolume * deepCoef

        fun valueAt(ageHours: Double): Double {
            if (ageHours < 0.0) return 0.0
            val decay = exp(-p.absorptionRate * ageHours)
            var sum = fastAmp * (exp(-p.eliminationRate * ageHours) - decay)
            if (slowAmp != 0.0) sum += slowAmp * (exp(-p.slowEliminationRate * ageHours) - decay)
            if (deepAmp != 0.0) sum += deepAmp * (exp(-p.deepEliminationRate * ageHours) - decay)
            return sum
        }
        fun atSteady(hoursIntoInterval: Double): Double {
            var sum = 0.0
            for (n in 0 until doses) sum += valueAt(hoursIntoInterval + n * intervalHours)
            return sum
        }

        val steps = 240
        var peak = 0.0
        var trough = Double.MAX_VALUE
        var area = 0.0
        var previous = atSteady(0.0)
        for (i in 0..steps) {
            val h = intervalHours * i / steps
            val v = atSteady(h)
            if (v > peak) peak = v
            if (v < trough) trough = v
            if (i > 0) area += (previous + v) / 2.0 * (intervalHours / steps)
            previous = v
        }
        val singlePeak = (0..steps).maxOf { valueAt(intervalHours * it / steps) }
        val singleTrough = valueAt(intervalHours)
        return SteadyStateMetrics(
            peak = peak,
            trough = trough,
            average = area / intervalHours,
            singlePeak = singlePeak,
            peakAccumulation = if (singlePeak > 1e-9) peak / singlePeak else 1.0,
            troughAccumulation = if (singleTrough > 1e-9) trough / singleTrough else 1.0
        )
    }

    /** 达到 90% 稳态所需给药次数；按最慢一相估算（双相时用终末相）。 */
    fun dosesToNinetyPercent(halfLifeHours: Double, intervalHours: Double): Double {
        if (halfLifeHours <= 0.0 || intervalHours <= 0.0) return 0.0
        return ln(10.0) * halfLifeHours / (ln(2.0) * intervalHours)
    }

    private fun optimizeLogParameters(
        start: DoubleArray,
        activeCount: Int,
        lower: DoubleArray,
        upper: DoubleArray,
        objective: (DoubleArray) -> Double
    ): DoubleArray {
        var best = start.copyOf()
        var bestValue = objective(best)
        var step = 0.60
        repeat(18) {
            for (index in 0 until activeCount) {
                val candidates = listOf(-1.0, -0.5, 0.0, 0.5, 1.0)
                candidates.forEach { multiplier ->
                    val trial = best.copyOf()
                    trial[index] = (trial[index] + step * multiplier).coerceIn(lower[index], upper[index])
                    val value = objective(trial)
                    if (value < bestValue) {
                        best = trial
                        bestValue = value
                    }
                }
            }
            step *= 0.68
        }
        return best
    }

    private fun toNgDl(value: Double, unit: String): Double =
        if (unit.equals("nmol/L", ignoreCase = true)) value * 28.85 else value

    private fun profile(drug: Drug, route: Route): PkProfile =
        profiles.first { it.drug == drug && it.route == route }

    private fun absorptionShape(ka: Double, ke: Double, hours: Double): Double =
        ka / (ka - ke) * (exp(-ke * hours) - exp(-ka * hours))

    /**
     * 处置项系数。
     *
     * 单相：ka/(ka−ke)，AUC 形状积分 = 1/ke（∫(e^−ke·t − e^−ka·t)dt = 1/ke − 1/ka，
     * 乘以 ka/(ka−ke) 正好等于 1/ke），所以 CL = ke×Vd 成立。
     *
     * 双相：每相按**剂量份额**拆分，(1−f)·ka/(ka−kf) 与 f·ka/(ka−ks)。
     * 旧实现多乘了一个 kf / ks，让形状积分变成无量纲的 1，直接后果是 CL 与 AUC 不自洽
     * （舌下 CL 1 384 vs 实际 1 597 L/h），而且无法用「AUC」做锚点校准 CPA。
     */
    private fun fastCoefficient(
        ka: Double,
        fastKe: Double,
        slowKe: Double,
        slowFraction: Double,
        deepFraction: Double = 0.0
    ): Double =
        if (slowFraction > 0.0 && slowKe > 0.0) {
            (1.0 - slowFraction - deepFraction) * ka / (ka - fastKe)
        } else {
            ka / (ka - fastKe)
        }

    private fun slowCoefficient(ka: Double, fastKe: Double, slowKe: Double, slowFraction: Double): Double =
        if (slowFraction > 0.0 && slowKe > 0.0) slowFraction * ka / (ka - slowKe) else 0.0

    /** 深室（脂肪 depot）项的系数；未启用时恒为 0。 */
    private fun deepCoefficient(ka: Double, deepKe: Double, deepFraction: Double): Double =
        if (deepFraction > 0.0 && deepKe > 0.0) deepFraction * ka / (ka - deepKe) else 0.0

    private fun dispositionShape(
        hours: Double,
        ka: Double,
        fastKe: Double,
        slowKe: Double,
        slowFraction: Double,
        deepKe: Double = 0.0,
        deepFraction: Double = 0.0
    ): Double {
        val absorptionTerm = exp(-ka * hours)
        val fast = fastCoefficient(ka, fastKe, slowKe, slowFraction, deepFraction) *
            (exp(-fastKe * hours) - absorptionTerm)
        var total = fast
        if (slowFraction > 0.0 && slowKe > 0.0) {
            total += slowCoefficient(ka, fastKe, slowKe, slowFraction) * (exp(-slowKe * hours) - absorptionTerm)
        }
        if (deepFraction > 0.0 && deepKe > 0.0) {
            total += deepCoefficient(ka, deepKe, deepFraction) * (exp(-deepKe * hours) - absorptionTerm)
        }
        return total
    }

    /** 多相曲线的真实峰值（用于把参考 Cmax 校准回剂量-浓度基准）。 */
    private fun peakShape(
        ka: Double,
        fastKe: Double,
        slowKe: Double,
        slowFraction: Double,
        deepKe: Double = 0.0,
        deepFraction: Double = 0.0
    ): Double =
        peakTimeAndValue(ka, fastKe, slowKe, slowFraction, 48.0, deepKe, deepFraction)
            .second.coerceAtLeast(1e-9)

    /** 多相曲线的真实峰时（h）。tmax(ka,ke) 只对单相成立，多相必须数值求解。 */
    private fun peakTime(
        ka: Double,
        fastKe: Double,
        slowKe: Double,
        slowFraction: Double,
        deepKe: Double = 0.0,
        deepFraction: Double = 0.0
    ): Double =
        peakTimeAndValue(ka, fastKe, slowKe, slowFraction, 72.0, deepKe, deepFraction).first

    private fun peakTimeAndValue(
        ka: Double,
        fastKe: Double,
        slowKe: Double,
        slowFraction: Double,
        horizonHours: Double,
        deepKe: Double = 0.0,
        deepFraction: Double = 0.0
    ): Pair<Double, Double> {
        var bestTime = 0.0
        var bestValue = 0.0
        var hours = 0.0
        while (hours <= horizonHours) {
            val value = dispositionShape(hours, ka, fastKe, slowKe, slowFraction, deepKe, deepFraction)
            if (value > bestValue) {
                bestValue = value
                bestTime = hours
            }
            hours += 0.01
        }
        return bestTime to bestValue
    }

    /**
     * 峰值后降半时间：从峰值时刻起，浓度落到峰值一半所需的时长。
     *
     * 这是「看曲线能量出来」的那个半衰期，与终末相斜率无关。多相曲线下两者差别很大
     * （CPA：13 h vs 54 h），只报终末相会让用户觉得曲线与标注互相矛盾。
     */
    private fun halfLifeFromPeak(        ka: Double,
        fastKe: Double,
        slowKe: Double,
        slowFraction: Double,
        peakHours: Double,
        deepKe: Double = 0.0,
        deepFraction: Double = 0.0
    ): Double {
        val peakValue = dispositionShape(peakHours, ka, fastKe, slowKe, slowFraction, deepKe, deepFraction)
        if (peakValue <= 0.0) return 0.0
        val target = peakValue / 2.0
        var low = peakHours
        var high = peakHours + 2_000.0
        repeat(90) {
            val mid = (low + high) / 2.0
            if (dispositionShape(mid, ka, fastKe, slowKe, slowFraction, deepKe, deepFraction) > target) {
                low = mid
            } else {
                high = mid
            }
        }
        return ((low + high) / 2.0 - peakHours).coerceAtLeast(0.0)
    }

    /**
     * 单次给药的表观终末半衰期：用 96–168 h 段的对数斜率反推。
     *
     * 三相模型下深室单次只占 6.5%，斜率落在浅室 36 h 与深室 90 h 之间（53.7 h，
     * 与 Kuhnz 1993 的单次实测 54.0 ± 26 h 对得上）。
     */
    private fun terminalSlopeHalfLife(
        ka: Double,
        fastKe: Double,
        slowKe: Double,
        slowFraction: Double,
        deepKe: Double,
        deepFraction: Double
    ): Double {
        val from = dispositionShape(96.0, ka, fastKe, slowKe, slowFraction, deepKe, deepFraction)
        val to = dispositionShape(168.0, ka, fastKe, slowKe, slowFraction, deepKe, deepFraction)
        if (from <= 0.0 || to <= 0.0 || from <= to) return 0.0
        return 72.0 * ln(2.0) / ln(from / to)
    }

    /**
     * **长期给药后**的终末半衰期：先按 q24 叠加 40 剂到稳态，再取停药后 168–336 h 的对数斜率。
     *
     * 这是用户真正感受到的那个数，也是 Kuhnz 1993「3 个周期后 78.6 ± 16 h」的测量口径：
     * 深室的蓄积倍数是 1/(1−e^−kd·τ)，远大于浅室，稳态后尾部改由深室接管，
     * 同一窗口的斜率从单次的 53.7 h 拉长到 78.7 h（约 3.3 天）。
     * 若再往后取 336–672 h，会继续趋向深室本身的 90 h。
     */
    private fun steadyStateTerminalHalfLife(
        ka: Double,
        fastKe: Double,
        slowKe: Double,
        slowFraction: Double,
        deepKe: Double,
        deepFraction: Double,
        intervalHours: Double = 24.0,
        doses: Int = 40
    ): Double {
        fun stack(hours: Double): Double {
            var sum = 0.0
            var index = 0
            while (index < doses) {
                sum += dispositionShape(
                    hours + index * intervalHours,
                    ka, fastKe, slowKe, slowFraction, deepKe, deepFraction
                )
                index++
            }
            return sum
        }
        val from = stack(168.0)
        val to = stack(336.0)
        if (from <= 0.0 || to <= 0.0 || from <= to) return 0.0
        return 168.0 * ln(2.0) / ln(from / to)
    }

    private fun tmax(ka: Double, ke: Double): Double = ln(ka / ke) / (ka - ke)

    private fun solveKa(ke: Double, desiredTmax: Double): Double {
        var low = ke * 1.000001
        var high = 1.0
        while (tmax(high, ke) > desiredTmax && high < 100.0) high *= 2.0
        repeat(100) {
            val mid = (low + high) / 2.0
            if (tmax(mid, ke) > desiredTmax) low = mid else high = mid
        }
        return (low + high) / 2.0
    }

    private const val SLOT_BASELINE = 0
    private const val SLOT_EMAX = 1
    private const val SLOT_SENSITIVITY = 2
}
