package cn.hrtcurve.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class PkEngineTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val takenAt = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun oralEv_reproducesReferencePeakAndTmax() {
        val event = DoseEvent("oral", Drug.ESTRADIOL_VALERATE, Route.ORAL, 1.0, takenAt)
        val p = PkEngine.parameters(event.drug, event.route, 57.0)
        val atPeak = takenAt + (p.tmaxHours * 3_600_000).toLong()
        assertEquals(10.0, p.tmaxHours, 0.001)
        assertEquals(20.47, PkEngine.concentrationAt(event, atPeak, 57.0), 0.01)
    }

    @Test
    fun sublingualScenario_reproducesReferencePeak() {
        val event = DoseEvent("sl", Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 1.0, takenAt)
        val p = PkEngine.parameters(event.drug, event.route, 70.0)
        assertEquals(0.70, p.tmaxHours, 0.03)
        // 1 mg EV 舌下峰值锚定 144 pg/mL × 摩尔当量 0.764 ≈ 110 pg/mL
        // 双相曲线的峰值由 0.02 h 网格扫描得到，容许 0.5% 的网格误差
        assertEquals(110.016, PkEngine.concentrationAt(event, takenAt + (p.tmaxHours * 3_600_000).toLong(), 70.0), 0.6)
    }

    @Test
    fun sublingual_hasDualPhaseDisposition() {
        val p = PkEngine.parameters(Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 70.0)
        assertTrue("舌下应为双相", p.dualPhase)
        assertEquals(0.8, p.halfLifeHours, 0.01)
        assertEquals(14.0, p.slowHalfLifeHours, 0.01)
        // f 是「进入终末相的剂量份额」，不是 AUC 份额：AUCᵢ = wᵢ/kᵢ，
        // 所以 f=0.118 对应终末相约 70% 的 AUC。
        assertEquals(0.118, p.slowFraction, 0.001)
        assertEquals(0.70, p.slowAucShare, 0.02)
        // 口服 EV 仍是单相；CPA 因为要同时满足 Cmax/Tmax/t½/AUC 也改成了双相
        assertTrue(!PkEngine.parameters(Drug.ESTRADIOL_VALERATE, Route.ORAL, 70.0).dualPhase)
        assertTrue(PkEngine.parameters(Drug.CPA, Route.ORAL, 70.0).dualPhase)
    }

    /**
     * 关键自洽性：CL/F 必须等于 D/AUC。
     *
     * 旧实现给双相系数多乘了一个 k，形状积分变成无量纲的 1，于是 CL = ke×Vd 与 AUC 脱钩：
     * 舌下报 1 384 L/h 而实际 AUC 对应 1 597 L/h（差 15%），CPA 更是差了 2.7 倍。
     */
    @Test
    fun clearance_equalsDoseOverAuc_forEveryProfile() {
        listOf(
            Triple(Drug.ESTRADIOL_VALERATE, Route.ORAL, 57.0),
            Triple(Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 70.0),
            Triple(Drug.CPA, Route.ORAL, 70.0)
        ).forEach { (drug, route, weight) ->
            val event = DoseEvent("e", drug, route, 1.0, takenAt)
            val p = PkEngine.parameters(drug, route, weight)
            val unit = if (drug == Drug.CPA) 1_000.0 else 1_000_000.0
            var auc = 0.0
            val step = 0.02
            var t = 0.0
            while (t < 4000.0) {
                val a = PkEngine.concentrationAt(event, takenAt + (t * 3_600_000).toLong(), weight)
                val b = PkEngine.concentrationAt(event, takenAt + ((t + step) * 3_600_000).toLong(), weight)
                auc += (a + b) / 2.0 * step
                t += step
            }
            val impliedCl = unit / auc
            assertEquals(
                "$drug/$route CL 与 AUC 不自洽：CL=${p.apparentClearance} vs D/AUC=$impliedCl",
                impliedCl, p.apparentClearance, p.apparentClearance * 0.02
            )
        }
    }

    /**
     * 旧版把舌下压成单室 2 h 半衰期时，2 mg 在 12 h 只剩约 5.8 pg/mL（个位数），
     * 24 h 约 0.09 pg/mL。双相之后尾巴由 14 h 的终末相托住，12 h 约 24 pg/mL。
     */
    @Test
    fun sublingual_keepsMeasurableTailAt12And24Hours() {
        val event = DoseEvent("sl", Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 2.0, takenAt)
        val at12 = PkEngine.concentrationAt(event, takenAt + (12 * 3_600_000L), 70.0)
        val at24 = PkEngine.concentrationAt(event, takenAt + (24 * 3_600_000L), 70.0)
        assertTrue("12 h 应远高于个位数，实际 $at12", at12 > 20.0)
        assertTrue("24 h 仍应可测，实际 $at24", at24 > 10.0)
        // 早段仍要保留快速回落：2 h 约为峰值的 5–7 成
        val at2 = PkEngine.concentrationAt(event, takenAt + (2 * 3_600_000L), 70.0)
        assertTrue("2 h 应已明显回落，实际 $at2", at2 < 160.0 && at2 > 100.0)
    }

    @Test
    fun sublingual_dailyDosingAccumulates() {
        val doses = (0 until 7).map {
            DoseEvent("d$it", Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 2.0, takenAt + it * 86_400_000L)
        }
        val troughDay1 = PkEngine.totalConcentration(Drug.ESTRADIOL_VALERATE, takenAt + 86_400_000L, doses, 70.0)
        val troughDay7 = PkEngine.totalConcentration(Drug.ESTRADIOL_VALERATE, takenAt + 7 * 86_400_000L, doses, 70.0)
        val troughNoAccumulation = troughDay1
        assertTrue(
            "每日一次也应看到蓄积：day7 $troughDay7 vs day1 $troughNoAccumulation",
            troughDay7 > troughDay1 * 1.35
        )
    }

    @Test
    fun sublingual_thriceDailyDosingAccumulatesMore() {
        val single = DoseEvent("one", Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 2.0, takenAt)
        val singleAt8 = PkEngine.concentrationAt(single, takenAt + (8 * 3_600_000L), 70.0)
        val doses = (0 until 10).map {
            DoseEvent("t$it", Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 2.0, takenAt + it * (8 * 3_600_000L))
        }
        // 第 10 剂前的谷值（第 9 剂给药后 8 h）
        val steadyTrough = PkEngine.totalConcentration(
            Drug.ESTRADIOL_VALERATE, takenAt + 9 * (8 * 3_600_000L), doses, 70.0
        )
        assertTrue(
            "q8h 稳态谷应显著高于单剂 8 h 残余：$steadyTrough vs $singleAt8",
            steadyTrough > singleAt8 * 1.8
        )
    }

    @Test
    fun cpa_reproducesDatasheetPeak() {
        val event = DoseEvent("cpa", Drug.CPA, Route.ORAL, 50.0, takenAt)
        val p = PkEngine.parameters(event.drug, event.route, 70.0)
        val atPeak = takenAt + (p.tmaxHours * 3_600_000).toLong()
        // 药品资料：约 3 h 达峰（双相下数值解为 3.0 h）
        assertEquals(3.0, p.tmaxHours, 0.1)
        assertEquals(140.0, PkEngine.concentrationAt(event, atPeak, 70.0), 0.5)
    }

    /**
     * CPA 的 AUC 锚点存在两套口径，差 46%：
     * - 加拿大生物等效性研究（2×50 mg）AUC0-∞ 6 908 ng·h/mL → 折 50 mg ≈ 3 450（受试者 CL ≈ 3.0 mL/min/kg）
     * - 说明书总清除率 3.5 ± 1.5 mL/min/kg，取下限 2.0 → F·D/CL ≈ 5 238
     *
     * 1.0.9–1.0.13 为了贴住 3 450，把 87% 剂量塞进 2 h 快相，曲线 6 h 掉 22%、9.3 h 减半，
     * 与「t½ 43.9 h」的标注自相矛盾。1.0.14 改取清除率下限口径（AUC 约 5 000、CL 约 2.1），
     * 曲线前半段才平得下来；稳态平均约 208 ng/mL 与 Frolich 的间隔采血范围仅作量级对照。
     */
    @Test
    fun cpa_aucMatchesClearanceLowerBound() {
        val event = DoseEvent("cpa", Drug.CPA, Route.ORAL, 50.0, takenAt)
        var auc = 0.0
        val step = 0.05
        var t = 0.0
        while (t < 3000.0) {
            val a = PkEngine.concentrationAt(event, takenAt + (t * 3_600_000).toLong(), 70.0)
            val b = PkEngine.concentrationAt(event, takenAt + ((t + step) * 3_600_000).toLong(), 70.0)
            auc += (a + b) / 2.0 * step
            t += step
        }
        assertTrue("50 mg CPA 的 AUC0-∞ 应落在 4 800–5 300，实际 $auc", auc in 4_800.0..5_300.0)
        assertEquals(auc, PkEngine.singleDoseAuc(Drug.CPA, Route.ORAL, 50.0, 70.0), 1.0)
        // CL/F ≈ 9.9 L/h；换成总清除率 9.9×0.88 ≈ 8.7 L/h ≈ 2.1 mL/min/kg，落在 3.5±1.5 的下限侧
        val clPerKg = PkEngine.parameters(Drug.CPA, Route.ORAL, 70.0).apparentClearance * 0.88 * 1000.0 / 60.0 / 70.0
        assertTrue("总清除率 $clPerKg mL/min/kg 应落在 2.0..5.0", clPerKg in 1.9..5.0)
    }

    /**
     * 三个慢相速率必须与文献对得上，而且**长期给药后终末相要自动延长**。
     *
     * - 浅室 43.9 h：Siterone/Androcur SPC 的「24–120 h 段」斜率。
     * - 深室 78.6 h：Kuhnz 1993 第 3 周期末的终末半衰期（54.0 → 78.6 h，Vz 986 → 1 304 L）。
     * - 单次给药时深室只占 8%，表观斜率 ≈57 h（近两天，与 Kuhnz 单次 54 h 一致）；
     *   连续给药后深室蓄积最多，稳态停药后的斜率趋向 78.6 h（三天多）。
     *
     * 这不是靠时间依赖参数做出来的，而是三相线性叠加的必然结果 —— 也正是深室药理。
     */
    @Test
    fun cpa_terminalHalfLifeProlongesAfterRepeatedDosing() {
        val event = DoseEvent("cpa", Drug.CPA, Route.ORAL, 50.0, takenAt)
        val p = PkEngine.parameters(Drug.CPA, Route.ORAL, 70.0)
        assertEquals("浅室半衰期", 36.0, p.slowHalfLifeHours, 0.01)
        assertEquals("深室半衰期", 90.0, p.deepHalfLifeHours, 0.01)
        assertEquals("主相半衰期", 4.0, p.halfLifeHours, 0.01)
        assertTrue("必须是三相", p.triPhase)

        fun at(hours: Long) = PkEngine.concentrationAt(event, takenAt + hours * 3_600_000L, 70.0)
        fun slope(from: Long, to: Long) =
            (to - from) * Math.log(2.0) / Math.log(at(from) / at(to))

        // 单次：深室尚未蓄积。三个窗口分别对三份文献：
        // 24→120 h ≈ 43.9（Siterone SPC）、96→168 h ≈ 54.0（Kuhnz 单次）、稳态 168→336 h ≈ 78.6
        assertTrue("24→120 h 应约 44 h，实际 ${slope(24, 120)}", slope(24, 120) in 42.0..47.0)
        assertTrue("单次 96→168 h 斜率应约 54 h，实际 ${slope(96, 168)}", slope(96, 168) in 51.0..57.0)
        assertEquals("24→48 h 是浅室段，约 37 h", 37.2, slope(24, 48), 3.0)
        assertTrue("48→72 h 进入浅室+深室混合段，约 45 h", slope(48, 72) in 42.0..49.0)

        assertTrue("24 h 应约 45 ng/mL，实际 ${at(24)}", at(24) in 41.0..49.0)
        assertTrue("48 h 应约 29 ng/mL，实际 ${at(48)}", at(48) in 26.0..32.0)
        assertTrue("72 h 应约 20 ng/mL，实际 ${at(72)}", at(72) in 18.0..22.0)

        // 长期服药后：深室蓄积倍数（÷(1−e^−kd·τ)）远大于浅室，尾部改由深室接管，
        // 终末半衰期由 53.7 h 延长到 78.7 h（三天多）——这就是 Kuhnz 的 54 → 78.6 h。
        assertTrue("长期终末应约 79 h，实际 ${p.longTermTerminalHalfLifeHours}",
            p.longTermTerminalHalfLifeHours in 74.0..84.0)
        assertTrue(
            "长期终末 ${p.longTermTerminalHalfLifeHours} h 必须明显长于单次 ${p.apparentTerminalHalfLifeHours} h",
            p.longTermTerminalHalfLifeHours > p.apparentTerminalHalfLifeHours + 15.0
        )

        val repeated = List(12) { DoseEvent("cpa$it", Drug.CPA, Route.ORAL, 50.0, takenAt + it * 86_400_000L) }
        val lastDose = takenAt + 11 * 86_400_000L
        fun steadyAt(days: Long) = PkEngine.totalConcentration(
            Drug.CPA, lastDose + days * 86_400_000L, repeated, 70.0
        )
        val steadyTailSlope = 3.0 * 24.0 * Math.log(2.0) / Math.log(steadyAt(7) / steadyAt(10))
        assertTrue("12 剂后停药斜率应已到 70 h 以上，实际 $steadyTailSlope", steadyTailSlope > 70.0)
    }

    /**
     * 曲线上「能量出来」的半衰期：从 Cmax 落到一半的时长。
     *
     * 三相下 6 h 保留 83%、8 h 69%，给药后约 12 h 才减半。注意这是「峰值后」的时长，
     * wall clock 要加 Tmax（3 h），界面必须写清，否则会被读成「给药 6 小时后浓度减半」。
     */
    @Test
    fun cpa_peakToHalfStaysConsistentWithTerminalPhase() {
        val cpa = PkEngine.parameters(Drug.CPA, Route.ORAL, 70.0)
        assertEquals("峰值后降半", 9.9, cpa.apparentHalfLifeHours, 0.8)
        assertTrue(
            "峰值后降半 ${cpa.apparentHalfLifeHours} h 应仍明显短于长期终末 ${cpa.longTermTerminalHalfLifeHours} h",
            cpa.apparentHalfLifeHours < cpa.longTermTerminalHalfLifeHours / 5.0
        )

        val sublingual = PkEngine.parameters(Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 70.0)
        assertEquals("舌下峰值后降半", 1.5, sublingual.apparentHalfLifeHours, 0.4)
        assertTrue(
            "舌下峰值后降半 ${sublingual.apparentHalfLifeHours} h 应远小于终末 14 h",
            sublingual.apparentHalfLifeHours < 4.0
        )

        // 曲线自洽：6 h 保留 83%、8 h 69%、24 h 30%
        val event = DoseEvent("cpa", Drug.CPA, Route.ORAL, 50.0, takenAt)
        fun at(hours: Long) = PkEngine.concentrationAt(event, takenAt + hours * 3_600_000L, 70.0)
        assertTrue("6 h 应保留约 85%，实际 ${at(6) / 140.0}", at(6) / 140.0 in 0.81..0.88)
        assertTrue("8 h 应保留约 71%，实际 ${at(8) / 140.0}", at(8) / 140.0 in 0.68..0.74)
        assertTrue("24 h 应保留约 32%，实际 ${at(24) / 140.0}", at(24) / 140.0 in 0.29..0.35)
    }

    /**
     * 50 mg q24h 的稳态：平均 210（= AUC/τ）、谷约 153、峰约 286。
     * Frolich 1980 长期 50 mg/日 的间隔采血范围为 199–228 ng/mL，只作量级对照，
     * 不能把它当作完整 24 h 平均；说明书称每日给药蓄积约 3 倍。
     */
    @Test
    fun cpa_steadyStateMatchesClearanceDerivedAverage() {
        val steady = PkEngine.steadyState(Drug.CPA, Route.ORAL, 50.0, 70.0, 24.0)
        assertNotNull(steady)
        assertEquals(210.0, steady!!.average, 12.0)
        assertTrue("稳态谷应在 145–162，实际 ${steady.trough}", steady.trough in 145.0..162.0)
        assertTrue("稳态峰应在 275–298，实际 ${steady.peak}", steady.peak in 275.0..298.0)
        assertTrue("峰蓄积 ${steady.peakAccumulation}", steady.peakAccumulation in 1.9..2.2)
    }

    @Test
    fun sublingual_singleDoseAucMatchesReference() {
        val event = DoseEvent("sl", Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 1.0, takenAt)
        var auc = 0.0
        val step = 0.02
        var t = 0.0
        while (t < 1200.0) {
            val a = PkEngine.concentrationAt(event, takenAt + (t * 3_600_000).toLong(), 70.0)
            val b = PkEngine.concentrationAt(event, takenAt + ((t + step) * 3_600_000).toLong(), 70.0)
            auc += (a + b) / 2.0 * step
            t += step
        }
        assertEquals("1 mg EV 舌下 AUC0-∞ 约 626 pg·h/mL", 626.0, auc, 626.0 * 0.03)
        // 0–8 h 的 AUC 应明显大于口服 EV 同剂量（Doll 研究的方向）
        assertTrue(auc > 400.0)
    }

    /**
     * 口服 EV 的结构性偏差：单相在同时满足 Cmax 20.47 / Tmax 10 h / t½ 14.48 h 时，
     * AUC0-∞ 必然落在 690 左右，而 Zhang 2024 的实测是 576（几何均值 534）。
     * 这是单室结构的代价，不是算错；记录在此避免以后误以为可以对上。
     */
    @Test
    fun oralEv_aucIsAboutTwentyPercentAbovePublishedValue() {
        val event = DoseEvent("oral", Drug.ESTRADIOL_VALERATE, Route.ORAL, 1.0, takenAt)
        var auc = 0.0
        val step = 0.1
        var t = 0.0
        while (t < 1000.0) {
            val a = PkEngine.concentrationAt(event, takenAt + (t * 3_600_000).toLong(), 57.0)
            val b = PkEngine.concentrationAt(event, takenAt + ((t + step) * 3_600_000).toLong(), 57.0)
            auc += (a + b) / 2.0 * step
            t += step
        }
        assertTrue("AUC=$auc 应落在 660–720", auc in 660.0..720.0)
        assertTrue("相对文献 576 的偏差应控制在 +25% 内", auc / 576.0 < 1.25)
    }

    @Test
    fun weightScaling_usesDeclaredExponents() {
        val light = PkEngine.parameters(Drug.CPA, Route.ORAL, 35.0)
        val reference = PkEngine.parameters(Drug.CPA, Route.ORAL, 70.0)
        assertEquals(0.5, light.apparentVolume / reference.apparentVolume, 0.0001)
        assertEquals(Math.pow(0.5, 0.75), light.apparentClearance / reference.apparentClearance, 0.0001)
    }

    @Test
    fun futureDose_hasNoConcentration() {
        val event = DoseEvent("future", Drug.CPA, Route.ORAL, 10.0, takenAt + 3_600_000)
        assertEquals(0.0, PkEngine.concentrationAt(event, takenAt, 60.0), 0.0)
    }

    @Test
    fun repeatedDoses_areAdditive() {
        val first = DoseEvent("1", Drug.CPA, Route.ORAL, 10.0, takenAt)
        val second = DoseEvent("2", Drug.CPA, Route.ORAL, 10.0, takenAt + 86_400_000)
        val at = takenAt + 100_800_000
        val expected = PkEngine.concentrationAt(first, at, 60.0) + PkEngine.concentrationAt(second, at, 60.0)
        assertEquals(expected, PkEngine.totalConcentration(Drug.CPA, at, listOf(first, second), 60.0), 0.0001)
    }

    @Test
    fun doseSeries_generatesInclusiveRegularHistory() {
        val events = buildDoseSeries(
            Drug.CPA, Route.ORAL, 12.5,
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 5),
            LocalTime.of(8, 0),
            intervalDays = 2
        )
        assertEquals(3, events.size)
        assertEquals(Drug.CPA, events.first().drug)
        assertEquals(Route.ORAL, events.first().route)
        assertTrue(events.all { it.doseMg == 12.5 })
        assertTrue(events.zipWithNext().all { (left, right) ->
            right.takenAtMillis - left.takenAtMillis == 2 * 86_400_000L
        })
    }

    @Test
    fun doseSeries_keepsSublingualRouteAndDiffersFromOral() {
        val day = LocalDate.of(2026, 11, 1)
        val oral = buildDoseSeries(
            Drug.ESTRADIOL_VALERATE, Route.ORAL, 2.0, day, day, LocalTime.of(8, 0), intervalDays = 1
        ).single()
        val sublingual = buildDoseSeries(
            Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 2.0, day, day, LocalTime.of(8, 0), intervalDays = 1
        ).single()
        assertEquals(Route.SUBLINGUAL, sublingual.route)
        val oneHour = sublingual.takenAtMillis + 3_600_000L
        val oralValue = PkEngine.concentrationAt(oral, oneHour, 60.0)
        val sublingualValue = PkEngine.concentrationAt(sublingual, oneHour, 60.0)
        assertTrue(sublingualValue > oralValue * 2.0)
        val oralPeak = PkEngine.parameters(Drug.ESTRADIOL_VALERATE, Route.ORAL, 57.0)
        val sublingualPeak = PkEngine.parameters(Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 70.0)
        assertEquals(10.0, oralPeak.tmaxHours, 0.01)
        assertEquals(0.70, sublingualPeak.tmaxHours, 0.03)
        assertTrue(sublingualPeak.absorptionRate > oralPeak.absorptionRate)
    }

    @Test
    fun doseSeries_rejectsOversizedOrInvalidRange() {
        val error = runCatching {
            buildDoseSeries(
                Drug.CPA, Route.ORAL, 10.0,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2025, 1, 1),
                LocalTime.of(8, 0),
                intervalDays = 1
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("1000"))
    }

    @Test
    fun testosteroneCurve_usesEarliestDoseAsStart() {
        val now = System.currentTimeMillis()
        val event = DoseEvent("long-history", Drug.CPA, Route.ORAL, 10.0, now - 365 * 86_400_000L)
        val profile = UserProfile(60.0)
        val series = PkEngine.curve(Analyte.TESTOSTERONE, listOf(event), emptyList(), profile, emptyMap(), 48, now)
        assertNotNull(series)
        // 无基线时也输出绝对单位，才能提供 ng/dL ↔ nmol/L 切换
        assertEquals("ng/dL", series!!.unit)
        assertEquals(2, series.absoluteUnitOptions().size)
        assertTrue(series.points.all { it.value <= 550.01 })
        assertEquals(LocalDate.now().minusDays(365), PkEngine.effectiveHrtStartDate(listOf(event)))
        assertTrue(PkEngine.hrtDays(listOf(event), now) >= 90)
        assertEquals(0L, PkEngine.hrtDays(emptyList(), now))
        assertNull(PkEngine.effectiveHrtStartDate(emptyList()))
    }

    /**
     * PD 必须由「用户看到的同一条曲线」驱动。
     *
     * 旧版在 7 日均值上再乘一次舌下 F=2，等效 E2 = 208 pg/mL，T 被压到 2.4 nmol/L，
     * 而曲线本身只有 104 pg/mL —— 用户无法用自己看到的这条曲线解释 T 为什么这么低。
     * 这里锁住「曲线均值 104 → T 约 5 nmol/L」的一致性。
     */
    @Test
    fun testosteronePd_usesCurveConcentrationWithoutSecondBioavailabilityFactor() {
        val now = System.currentTimeMillis()
        val profile = UserProfile(60.0)
        val anchor = DoseEvent("anchor", Drug.ESTRADIOL_VALERATE, Route.ORAL, 0.01, now - 200 * 86_400_000L)
        val doses = (0 until 260).map {
            DoseEvent("s$it", Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 2.0, now - (259 - it) * 12 * 3_600_000L)
        }
        val series = PkEngine.curve(
            Analyte.TESTOSTERONE, listOf(anchor) + doses, emptyList(), profile, emptyMap(), 24, now
        )
        assertNotNull(series)
        val t = series!!.points.minBy { kotlin.math.abs(it.hours) }.value
        // 60 kg 下 7 日均 E2 ≈ 117 pg/mL → r=2.61、g=0.808 → T ≈ 128 ng/dL ≈ 4.43 nmol/L。
        // 若 PD 再乘一次 F=2（等效 234 pg/mL），T 会掉到 2.4 nmol/L 以下。
        assertEquals("T 应约 4.4 nmol/L（不能因为二次乘 F 掉到 2.4）", 4.43, t / 28.85, 0.30)
    }

    @Test
    fun longTermDailyDosing_showsAccumulation() {
        val events = buildDoseSeries(
            Drug.CPA, Route.ORAL, 10.0,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 30),
            LocalTime.of(8, 0),
            intervalDays = 1
        )
        val reference = events.last().takenAtMillis + 3 * 3_600_000L
        val total = PkEngine.totalConcentration(Drug.CPA, reference, events, 70.0)
        val latestOnly = PkEngine.concentrationAt(events.last(), reference, 70.0)
        assertTrue(total > latestOnly * 1.2)
    }

    @Test
    fun curve_usesHistoryAndFutureHalvesWithCurrentReference() {
        val reference = takenAt + 24 * 3_600_000L
        val event = DoseEvent("history", Drug.CPA, Route.ORAL, 10.0, reference - 24 * 3_600_000L)
        val lab = LabSample(
            "lab", Analyte.CPA, Drug.CPA, Route.ORAL, event.id,
            12.0, reference - 12 * 3_600_000L, 30.0, "ng/mL"
        )
        val curve = PkEngine.curve(Analyte.CPA, listOf(event), listOf(lab), UserProfile(70.0), emptyMap(), 48, reference)
        assertNotNull(curve)
        assertEquals(-24.0, curve!!.points.first().hours, 0.01)
        assertEquals(24.0, curve.points.last().hours, 0.01)
        assertEquals(1, curve.labPoints.size)
        assertEquals(-12.0, curve.labPoints.first().hours, 0.01)
    }

    @Test
    fun calibration_usesGeometricMean() {
        val event = DoseEvent("1", Drug.ESTRADIOL_VALERATE, Route.ORAL, 1.0, takenAt)
        val at = takenAt + 10 * 3_600_000
        val predicted = PkEngine.totalConcentration(event.drug, at, listOf(event), 57.0)
        val samples = listOf(1.5, 2.0).mapIndexed { index, ratio ->
            LabSample("$index", Analyte.E2, event.drug, event.route, event.id, 10.0, at, predicted * ratio, "pg/mL")
        }
        val result = PkEngine.calibrate(event.drug, event.route, samples, listOf(event), 57.0)
        assertEquals(Math.sqrt(3.0), result.factor, 0.0001)
        assertEquals(2, result.sampleCount)
    }

    @Test
    fun testosteroneCurve_requiresNinetyDays() {
        val reference = takenAt + 10 * 86_400_000L
        val profile = UserProfile(70.0)
        val recent = DoseEvent("recent", Drug.CPA, Route.ORAL, 10.0, reference - 10 * 86_400_000L)
        val longRunning = DoseEvent("long", Drug.CPA, Route.ORAL, 10.0, reference - 100 * 86_400_000L)
        assertNull(PkEngine.curve(Analyte.TESTOSTERONE, listOf(recent), emptyList(), profile, emptyMap(), 48, reference))
        assertNotNull(PkEngine.curve(Analyte.TESTOSTERONE, listOf(longRunning), emptyList(), profile, emptyMap(), 48, reference))
    }

    @Test
    fun maintenanceDose_requiresExplicitTarget() {
        assertNull(PkEngine.maintenanceDose(Drug.CPA, Route.ORAL, 70.0, 0.0, 24.0))
        val md = PkEngine.maintenanceDose(Drug.CPA, Route.ORAL, 70.0, 40.0, 24.0)
        assertNotNull(md)
        assertTrue(md!! > 0.0)
    }

    @Test
    fun individualPk_withoutLabs_staysAtPopulationPrior() {
        val event = DoseEvent("1", Drug.CPA, Route.ORAL, 10.0, takenAt)
        val fit = PkEngine.individualFit(Drug.CPA, Route.ORAL, emptyList(), listOf(event), 70.0)
        assertEquals(IndividualFitLevel.POPULATION, fit.level)
        assertEquals(1.0, fit.exposureFactor, 0.0)
        assertEquals(0, fit.sampleCount)
    }

    @Test
    fun individualPk_onePoint_updatesExposureOnly() {
        val event = DoseEvent("1", Drug.ESTRADIOL_VALERATE, Route.ORAL, 1.0, takenAt)
        val at = takenAt + 10 * 3_600_000
        val predicted = PkEngine.totalConcentration(event.drug, at, listOf(event), 57.0)
        val lab = LabSample("lab", Analyte.E2, event.drug, event.route, event.id, 10.0, at, predicted * 1.8, "pg/mL")
        val fit = PkEngine.individualFit(event.drug, event.route, listOf(lab), listOf(event), 57.0)
        val personalized = PkEngine.totalConcentration(event.drug, at, listOf(event), 57.0, individualFits = mapOf(PkEngine.calibrationKey(event.drug, event.route) to fit))
        assertEquals(IndividualFitLevel.EXPOSURE, fit.level)
        assertEquals(1.0, fit.eliminationFactor, 0.0)
        assertEquals(1.0, fit.absorptionFactor, 0.0)
        assertTrue(fit.exposureFactor > 1.0)
        assertTrue(kotlin.math.abs(lab.value - personalized) < kotlin.math.abs(lab.value - predicted))
    }

    @Test
    fun individualPk_dataCoverage_unlocksParametersGradually() {
        val first = DoseEvent("1", Drug.ESTRADIOL_VALERATE, Route.ORAL, 1.0, takenAt)
        val second = DoseEvent("2", Drug.ESTRADIOL_VALERATE, Route.ORAL, 1.0, takenAt + 48 * 3_600_000)
        val doses = listOf(first, second)
        fun lab(id: String, dose: DoseEvent, hours: Int): LabSample {
            val at = dose.takenAtMillis + hours * 3_600_000L
            val value = PkEngine.totalConcentration(dose.drug, at, doses, 57.0) * 1.15
            return LabSample(id, Analyte.E2, dose.drug, dose.route, dose.id, hours.toDouble(), at, value, "pg/mL")
        }
        val three = listOf(lab("a", first, 10), lab("b", first, 24), lab("c", second, 10))
        val elimination = PkEngine.individualFit(first.drug, first.route, three, doses, 57.0)
        assertEquals(IndividualFitLevel.ELIMINATION, elimination.level)
        assertEquals(1.0, elimination.absorptionFactor, 0.0)
        val five = three + listOf(lab("d", first, 2), lab("e", second, 24))
        val full = PkEngine.individualFit(first.drug, first.route, five, doses, 57.0)
        assertEquals(IndividualFitLevel.FULL_PK, full.level)
        assertTrue(full.absorptionFactor > 0.0)
        assertTrue(full.logRmse.isFinite())
    }

    @Test
    fun individualPd_withoutBaseline_anchorsOnFirstPoint() {
        val event = DoseEvent("cpa", Drug.CPA, Route.ORAL, 10.0, takenAt)
        val at = takenAt + 24 * 3_600_000
        val lab = LabSample("t", Analyte.TESTOSTERONE, Drug.CPA, Route.ORAL, event.id, 24.0, at, 30.0, "ng/dL")
        // 没有未治疗基线也立即拟合：1 个点先锚定基线，Emax 取先验
        val anchored = PkEngine.testosteroneFit(listOf(lab), listOf(event), UserProfile(70.0))
        assertEquals(PdFitLevel.ANCHOR, anchored.level)
        assertTrue(anchored.baselineFitted)
        assertEquals(1, anchored.sampleCount)
        assertTrue(anchored.baselineNgDl!! in 50.0..2000.0)
    }

    @Test
    fun individualPd_withoutBaseline_jointlyEstimatesBaselineAndEmax() {
        val start = LocalDate.now().minusDays(120)
        // 12.5 mg/日吃到 40 天前停药，之后 T 应回升到基线附近
        val doses = buildDoseSeries(
            Drug.CPA, Route.ORAL, 12.5, start, LocalDate.now().minusDays(40), LocalTime.of(8, 0), 1, zone
        )
        val now = System.currentTimeMillis()
        val suppressedAt = now - 45 * 86_400_000L
        val recoveredAt = now - 1 * 86_400_000L
        val labSuppressed = LabSample("t1", Analyte.TESTOSTERONE, Drug.CPA, Route.ORAL, doses.first().id, 24.0, suppressedAt, 25.0, "ng/dL")
        val labRecovered = LabSample("t2", Analyte.TESTOSTERONE, Drug.CPA, Route.ORAL, doses.last().id, 24.0, recoveredAt, 480.0, "ng/dL")
        val profile = UserProfile(70.0)
        val fit = PkEngine.testosteroneFit(listOf(labSuppressed, labRecovered), doses, profile)
        assertEquals(PdFitLevel.EMAX, fit.level)
        assertTrue(fit.baselineFitted)
        assertEquals(2, fit.sampleCount)
        assertTrue(fit.baselineNgDl!! in 200.0..1200.0)
        assertTrue(fit.emax in 0.2..0.98)

        val curve = PkEngine.curve(Analyte.TESTOSTERONE, doses, listOf(labSuppressed, labRecovered), profile, emptyMap(), 48, now)
        assertNotNull(curve)
        assertTrue(curve!!.anchoredByLab)
        // 停药 40 天后应回到基线量级，而不是停在群体 550 的情景值
        val current = curve.points.first { it.hours >= 0.0 }.value
        assertEquals(fit.baselineNgDl!!, current, fit.baselineNgDl!! * 0.25)
    }

    /**
     * 单个 T 点 + 已知基线时应能估 Emax。
     *
     * 注意给药必须是「稳态」的：CPA 抑睾现在由 7 日平均浓度驱动，单次 10 mg 的暴露只有
     * 稳态的几十分之一，不可能把 T 压到 20 ng/dL。旧版本用日剂量驱动才允许这种不现实的输入。
     */
    @Test
    fun individualPd_withBaseline_fitsEmax() {
        val doses = buildDoseSeries(
            Drug.CPA, Route.ORAL, 50.0,
            LocalDate.now().minusDays(120), LocalDate.now().minusDays(5),
            LocalTime.of(8, 0), 1, zone
        )
        val at = doses.last().takenAtMillis + 24 * 3_600_000L
        val lab = LabSample("t", Analyte.TESTOSTERONE, Drug.CPA, Route.ORAL, doses.last().id, 24.0, at, 20.0, "ng/dL")
        val withBaseline = UserProfile(70.0, baselineTestosteroneNgDl = 500.0)
        val fit = PkEngine.testosteroneFit(listOf(lab), doses, withBaseline)
        assertEquals(PdFitLevel.EMAX, fit.level)
        assertEquals(1, fit.sampleCount)
        assertTrue("Emax=${fit.emax}", fit.emax in 0.05..0.97)
        assertTrue(!fit.baselineFitted)
        assertEquals(500.0, fit.baselineNgDl!!, 0.001)
        val reference = doses.last().takenAtMillis + 72 * 3_600_000L
        val curve = PkEngine.curve(Analyte.TESTOSTERONE, doses, listOf(lab), withBaseline, emptyMap(), 48, reference)
        assertNotNull(curve)
        assertEquals("ng/dL", curve!!.unit)
        assertTrue(curve.anchoredByLab)
    }

    /**
     * 停药后抑睾效应必须随 CPA 曲线消退，而不是在漏服当天归零。
     *
     * 12.5 mg/日吃到 30 天前停药：CPA 的 43.9 h 半衰期意味着停药 3 天后仍有约 1/3 的浓度，
     * 7 日均值衰减更慢。旧版用「7 日剂量窗口」，停药第 8 天窗口清零，T 会在一天内跳回基线；
     * 现在改成浓度驱动，T 应在停药后 1–2 周内渐进回升。
     */
    @Test
    fun stoppingCpa_keepsSuppressionThenRecoversGradually() {
        val start = LocalDate.now().minusDays(200)
        val stop = LocalDate.now().minusDays(30)
        val doses = buildDoseSeries(Drug.CPA, Route.ORAL, 12.5, start, stop, LocalTime.of(8, 0), 1, zone)
        val profile = UserProfile(70.0, start.toEpochDay(), baselineTestosteroneNgDl = 550.0)
        val lastDose = doses.last().takenAtMillis

        fun tAfterStop(days: Long): Double {
            val at = lastDose + days * 86_400_000L
            val curve = PkEngine.curve(Analyte.TESTOSTERONE, doses, emptyList(), profile, emptyMap(), 48, at)
            assertNotNull(curve)
            return curve!!.points.first { it.hours >= 0.0 }.value
        }

        val day1 = tAfterStop(1)
        val day3 = tAfterStop(3)
        val day7 = tAfterStop(7)
        val day14 = tAfterStop(14)
        val day30 = tAfterStop(30)

        assertTrue("停药 1 天应仍被抑制，实际 $day1 ng/dL", day1 < 80.0)
        assertTrue("停药后应单调回升：$day1 < $day3 < $day7 < $day14 < $day30", day1 < day3 && day3 < day7 && day7 < day14 && day14 < day30)
        assertTrue("停药 14 天应已明显回升，实际 $day14", day14 > day1 * 3.0)
        assertTrue("停药 30 天应回到基线附近，实际 $day30", day30 > 550.0 * 0.9)
    }

    @Test
    fun individualPk_sublingualSamplesDoNotLeakIntoOralFit() {
        val day = LocalDate.now().minusDays(10)
        val sublingual = buildDoseSeries(
            Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 2.0, day, day, LocalTime.of(8, 0), 1, zone
        ).single()
        val at = sublingual.takenAtMillis + 3_600_000L
        val predicted = PkEngine.totalConcentration(sublingual.drug, at, listOf(sublingual), 60.0)
        val lab = LabSample("s", Analyte.E2, sublingual.drug, sublingual.route, sublingual.id, 1.0, at, predicted * 1.5, "pg/mL")
        val fits = PkEngine.individualFits(sublingual.drug, listOf(lab), listOf(sublingual), 60.0)
        val subFit = fits[PkEngine.calibrationKey(sublingual.drug, Route.SUBLINGUAL)]
        assertNotNull(subFit)
        assertEquals(1, subFit!!.sampleCount)
        assertNull(fits[PkEngine.calibrationKey(sublingual.drug, Route.ORAL)])
    }

    @Test
    fun individualPk_keepsLabWhenItsDoseRecordWasDeleted() {
        val first = DoseEvent("1", Drug.ESTRADIOL_VALERATE, Route.ORAL, 2.0, takenAt)
        val second = DoseEvent("2", Drug.ESTRADIOL_VALERATE, Route.ORAL, 2.0, takenAt + 86_400_000L)
        val at = takenAt + 86_400_000L + 8 * 3_600_000L
        val predicted = PkEngine.totalConcentration(first.drug, at, listOf(first, second), 60.0)
        // 关联的给药记录已不存在，点仍应参与拟合
        val lab = LabSample("lab", Analyte.E2, first.drug, first.route, "deleted-dose-id", 8.0, at, predicted * 1.4, "pg/mL")
        val fit = PkEngine.individualFit(first.drug, first.route, listOf(lab), listOf(first, second), 60.0)
        assertEquals(1, fit.sampleCount)
        assertTrue(fit.exposureFactor > 1.0)
        assertTrue(PkEngine.fitHint(fit, first.drug, first.route, 60.0).isNotBlank())
    }

    @Test
    fun displayUnit_convertsCurveAndLabPointsFromBaseUnits() {
        val e2 = CurveSeries(
            analyte = Analyte.E2,
            unit = "pg/mL",
            points = listOf(CurvePoint(0.0, 100.0, 50.0, 180.0)),
            labPoints = listOf(LabPoint(0.0, 120.0, "实测")),
            notice = "",
            quantitative = true
        )
        val converted = e2.withDisplayUnit("pmol/L")
        assertEquals("pmol/L", converted.unit)
        assertEquals(367.1, converted.points.single().value, 0.001)
        assertEquals(183.55, converted.points.single().low, 0.001)
        assertEquals(440.52, converted.labPoints.single().value, 0.001)

        val t = e2.copy(analyte = Analyte.TESTOSTERONE, unit = "ng/dL")
        val tConverted = t.withDisplayUnit("nmol/L")
        assertEquals(3.466, tConverted.points.single().value, 0.001)

        val relative = e2.copy(unit = RELATIVE_UNIT)
        assertTrue(relative.absoluteUnitOptions().isEmpty())
        assertEquals(RELATIVE_UNIT, relative.withDisplayUnit("pmol/L").unit)
    }

    @Test
    fun cpaDosePlateau_noAdditionalTestosteroneSuppressionAbove12mg() {
        val start = LocalDate.now().minusDays(150)
        val end = LocalDate.now().minusDays(1)
        val profile = UserProfile(70.0, start.toEpochDay(), baselineTestosteroneNgDl = 550.0)
        val now = System.currentTimeMillis()

        fun testosteroneAt(doseMg: Double): Double {
            val doses = buildDoseSeries(
                Drug.CPA, Route.ORAL, doseMg, start, end, LocalTime.of(8, 0), 1, zone
            )
            val curve = PkEngine.curve(Analyte.TESTOSTERONE, doses, emptyList(), profile, emptyMap(), 48, now)
            assertNotNull(curve)
            return curve!!.points.first { it.hours >= 0.0 }.value
        }

        val lowDose = testosteroneAt(12.5)
        val highDose = testosteroneAt(50.0)
        val veryHighDose = testosteroneAt(100.0)
        assertEquals(lowDose, highDose, lowDose * 0.03)
        assertEquals(lowDose, veryHighDose, lowDose * 0.03)
        // 5 mg/日以下的低剂量段仍应保留剂量反应
        assertTrue(testosteroneAt(1.0) > lowDose * 1.5)
    }

    @Test
    fun cpaAddsSuppressionBeyondEstrogenOnly() {
        val start = LocalDate.now().minusDays(150)
        val end = LocalDate.now().minusDays(1)
        val profile = UserProfile(60.0, start.toEpochDay(), baselineTestosteroneNgDl = 550.0)
        val now = System.currentTimeMillis()
        val e2 = buildDoseSeries(
            Drug.ESTRADIOL_VALERATE, Route.ORAL, 4.0, start, end, LocalTime.of(8, 0), 1, zone
        )
        val cpa = buildDoseSeries(
            Drug.CPA, Route.ORAL, 10.0, start, end, LocalTime.of(8, 0), 1, zone
        )
        fun value(doses: List<DoseEvent>): Double {
            val curve = PkEngine.curve(Analyte.TESTOSTERONE, doses, emptyList(), profile, emptyMap(), 48, now)
            assertNotNull(curve)
            return curve!!.points.first { it.hours >= 0.0 }.value
        }
        val estrogenOnly = value(e2)
        val combined = value(e2 + cpa)
        assertTrue(estrogenOnly in 80.0..220.0)
        assertTrue(combined < estrogenOnly * 0.5)
    }

    @Test
    fun individualFit_firstPointStartsFittingAndHintsNextStep() {
        val dose = DoseEvent("1", Drug.ESTRADIOL_VALERATE, Route.ORAL, 2.0, takenAt)
        fun lab(id: String, hours: Int): LabSample {
            val at = takenAt + hours * 3_600_000L
            val predicted = PkEngine.totalConcentration(dose.drug, at, listOf(dose), 60.0)
            return LabSample(id, Analyte.E2, dose.drug, dose.route, dose.id, hours.toDouble(), at, predicted * 1.2, "pg/mL")
        }
        val one = PkEngine.individualFit(dose.drug, dose.route, listOf(lab("a", 30)), listOf(dose), 60.0)
        assertEquals(IndividualFitLevel.EXPOSURE, one.level)
        assertTrue(one.exposureFactor > 1.0)
        assertTrue(PkEngine.fitHint(one, dose.drug, dose.route, 60.0).contains("消除"))

        // 2 个点（含峰后）+ 同一给药周期即可估消除，不再要求跨多次给药
        val two = PkEngine.individualFit(
            dose.drug, dose.route, listOf(lab("a", 4), lab("b", 30)), listOf(dose), 60.0
        )
        assertEquals(IndividualFitLevel.ELIMINATION, two.level)

        // 3 个点覆盖峰前与峰后即可估吸收
        val three = PkEngine.individualFit(
            dose.drug, dose.route, listOf(lab("a", 4), lab("b", 10), lab("c", 30)), listOf(dose), 60.0
        )
        assertEquals(IndividualFitLevel.FULL_PK, three.level)
        assertTrue(PkEngine.fitHint(three, dose.drug, dose.route, 60.0).contains("都已参与拟合"))
    }

    @Test
    fun aiPayload_sendsAllHistoryButCompressesIdenticalDays() {
        val start = LocalDate.of(2025, 1, 1)
        val end = start.plusDays(364)
        val ev = buildDoseSeries(Drug.ESTRADIOL_VALERATE, Route.SUBLINGUAL, 2.0, start, end, LocalTime.of(8, 0), 1)
        val cpa = buildDoseSeries(Drug.CPA, Route.ORAL, 12.5, start, end, LocalTime.of(8, 0), 1)
        val now = end.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val text = AiPayload.build(AppState(doses = ev + cpa), now)

        assertTrue(text.contains("给药记录 730 条"))
        assertTrue(text.contains("全量，未截断"))
        // 连续相同方案必须合并成一条区间，而不是被裁掉
        assertTrue(text.contains("2025-01-01 ~ 2025-12-31（365 天）"))
        assertTrue(text.contains("每天 2 次"))
        assertTrue(text.contains("累计"))
        // 全量历史 + 逐条明细仍然远小于上下文上限
        assertTrue("payload 长度 ${text.length}", text.length < 9000)
    }

    @Test
    fun aiPayload_flagsGapWhenRecentDaysHaveNoDose() {
        val start = LocalDate.of(2026, 8, 1)
        val dose = DoseEvent(
            "old", Drug.CPA, Route.ORAL, 12.5,
            start.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        )
        val now = LocalDate.of(2026, 11, 12).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val text = AiPayload.build(AppState(doses = listOf(dose)), now)
        assertTrue(text.contains("最近 21 天没有记录"))
        assertTrue(text.contains("最后一条为"))
    }

    @Test
    fun aiPayload_fallsBackToMonthlySummaryWhenRegimenIsIrregular() {
        val start = LocalDate.of(2025, 1, 1)
        val doses = (0 until 200).map { index ->
            DoseEvent(
                "d$index", Drug.ESTRADIOL_VALERATE, Route.ORAL, 1.0 + index * 0.01,
                start.plusDays(index.toLong()).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            )
        }
        val now = start.plusDays(200).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val text = AiPayload.build(AppState(doses = doses), now)
        assertTrue(text.contains("改为按月汇总"))
        assertTrue(text.contains("2025-01："))
    }

    @Test
    fun maskSecret_keepsOnlyEdges() {
        assertEquals("未保存", maskSecret("   "))
        assertEquals("••••", maskSecret("abcd"))
        assertEquals("sk-a…Zx9Q", maskSecret("sk-abcdefghijZx9Q"))
    }

    /**
     * 长期 CPA 蓄积必须有界，不能随服药年限无限上升。
     *
     * 50 mg/日、70 kg 下，第 7/16/30 天峰值分别约为稳态的 91%/98.6%/99.9%，
     * 第 30 天后 365 天与 730 天的峰值基本相同。Kuhnz 1993 观察到约第 16 天达稳态、
     * 第 3 周期终末半衰期延长，但并没有证明它会随用药年限继续无限延长。
     *
     * 70 kg 的模型稳态峰：50→约 285、12.5→约 71 ng/mL；60 kg 的 12.5 mg/日
     * 才是用户看到的约 82 ng/mL，不能把不同体重的阈值混写。
     */
    @Test
    fun cpa_accumulationIsBounded_reachesPlateauWithin30Days() {
        val end = 1_762_000_000_000L
        fun daily(mg: Double, days: Int): List<DoseEvent> =
            List(days) { DoseEvent("acc$it", Drug.CPA, Route.ORAL, mg, end - (days - 1 - it) * 86_400_000L) }
        fun peakOf(mg: Double, days: Int): Double {
            val doses = daily(mg, days)
            var peak = 0.0
            var hours = 0.0
            while (hours <= 6.0) {
                val value = PkEngine.totalConcentration(Drug.CPA, end + (hours * 3_600_000L).toLong(), doses, 70.0)
                if (value > peak) peak = value
                hours += 0.25
            }
            return peak
        }

        val peak365 = peakOf(50.0, 365)
        assertTrue("50 mg/日 365 天峰值应约 285 ng/mL，实际 $peak365", peak365 in 276.0..292.0)

        // 蓄积有界：第 7/16/30 天达到 91%/98.6%/99.9%；30 天后永远不再涨
        assertTrue("第 7 天应约 91% 稳态，实际 ${peakOf(50.0, 7) / peak365}", peakOf(50.0, 7) / peak365 in 0.88..0.94)
        assertTrue("第 16 天应约 98.6% 稳态，实际 ${peakOf(50.0, 16) / peak365}", peakOf(50.0, 16) / peak365 in 0.97..0.997)
        assertTrue("第 30 天应已到 99.9% 稳态，实际 ${peakOf(50.0, 30) / peak365}", peakOf(50.0, 30) / peak365 > 0.995)
        assertTrue("730 天峰值必须与 365 天一致（有界蓄积）", abs(peakOf(50.0, 730) / peak365 - 1.0) < 0.002)

        // 谷浓度与谷蓄积：说明书「约 ×3」、Frolich 长期实测 199–228（平均 208 一致）
        val doses365 = daily(50.0, 365)
        val trough = PkEngine.totalConcentration(Drug.CPA, end - 60_000L, doses365, 70.0)
        assertTrue("稳态谷应约 152 ng/mL，实际 $trough", trough in 145.0..160.0)
        val single = listOf(DoseEvent("one", Drug.CPA, Route.ORAL, 50.0, end - 365L * 86_400_000L))
        val singleTrough = PkEngine.totalConcentration(Drug.CPA, single[0].takenAtMillis + 86_400_000L, single, 70.0)
        assertEquals("谷蓄积应约 ×3.38（说明书「约 ×3」）", 3.38, trough / singleTrough, 0.15)

        // 线性：稳态峰与日剂量成正比。「峰值 <82」⟺ 日剂量 ≲ 14.4 mg
        val peak125 = peakOf(12.5, 365)
        assertTrue("12.5 mg/日稳态峰应约 71 ng/mL，实际 $peak125", peak125 in 66.0..76.0)
        assertTrue("12.5 mg/日峰值应确实低于 82（用户观察到的现象）", peak125 < 82.0)
        val peak144 = peakOf(14.4, 365)
        assertTrue("14.4 mg/日稳态峰应约 82 ng/mL（70 kg 的边界剂量），实际 $peak144", peak144 in 78.0..86.0)
    }

    @Test
    fun cpa_oneYearTwelvePointFiveMg_peakAndTwentyFourHourTroughArePlausible() {
        val steady = PkEngine.steadyState(
            Drug.CPA, Route.ORAL, doseMg = 12.5, weightKg = 60.0,
            intervalHours = 24.0, doses = 365
        )
        assertNotNull(steady)

        // 默认 60 kg 场景：用户看到约 82 ng/mL 是体重缩放 + 线性叠加的结果，不是未蓄积。
        assertEquals("一年稳态峰", 80.72, steady!!.peak, 1.0)
        assertEquals("服药后 24 h 谷值", 42.30, steady.trough, 1.0)
        assertEquals("稳态 24 h 平均", 58.40, steady.average, 1.0)
        assertTrue("一年稳态峰应约 81 ng/mL，实际 ${steady.peak}", steady.peak in 76.0..86.0)
        assertTrue("24 h 谷值应约为峰值的 45%–60%，实际 ${steady.trough / steady.peak}",
            steady.trough / steady.peak in 0.45..0.60)
        assertTrue("谷值仍应明显高于 12.5 mg 单剂 24 h 残余，体现有界蓄积",
            steady.troughAccumulation > 2.5)

        // 峰→24 h 谷包含新剂量的吸收/分布与既往剂量残余，不是终末相斜率。
        val p = PkEngine.parameters(Drug.CPA, Route.ORAL, 60.0)
        assertTrue(p.longTermTerminalHalfLifeHours > 72.0)
        val impliedEarlyHalfLife = 21.0 * Math.log(2.0) / Math.log(steady.peak / steady.trough)
        assertTrue("峰到次日谷的表观降半时间 $impliedEarlyHalfLife h 不应被当成长期终末半衰期",
            impliedEarlyHalfLife < p.longTermTerminalHalfLifeHours / 2.0)
    }
}
