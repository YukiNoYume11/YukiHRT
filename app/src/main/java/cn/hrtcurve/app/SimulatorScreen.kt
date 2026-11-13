package cn.hrtcurve.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@Composable
fun SimulatorScreen(state: AppState, viewModel: MainViewModel) {
    var weight by remember(state.profile.weightKg) { mutableStateOf(trimNumber(state.profile.weightKg)) }
    var baselineT by remember {
        mutableStateOf(state.profile.baselineTestosteroneNgDl?.let(::trimNumber).orEmpty())
    }
    // 纵轴单位只影响展示，随时可在曲线卡片内切换
    var unitE2 by rememberSaveable { mutableStateOf("pg/mL") }
    var unitT by rememberSaveable { mutableStateOf("ng/dL") }
    var drug by remember { mutableStateOf(Drug.ESTRADIOL_VALERATE) }
    var route by remember { mutableStateOf(Route.ORAL) }
    var dose by remember { mutableStateOf("") }
    // 时间一律存成强类型，只在交给 ViewModel 时才格式化，避免格式串成为用户输入的一部分
    var doseAt by remember { mutableStateOf(LocalDateTime.now().withSecond(0).withNano(0)) }
    var message by remember { mutableStateOf("") }
    var batchStartDate by remember { mutableStateOf(LocalDate.now().minusDays(29)) }
    var batchEndDate by remember { mutableStateOf(LocalDate.now()) }
    var batchClock by remember { mutableStateOf(LocalTime.of(8, 0)) }
    var batchInterval by remember { mutableStateOf("1") }
    var batchRoute by remember { mutableStateOf(Route.ORAL) }
    var batchDose by remember { mutableStateOf("") }
    var batchResult by remember { mutableStateOf<BatchImportResult?>(null) }
    var analyte by remember { mutableStateOf(Analyte.E2) }
    var horizon by remember { mutableIntStateOf(48) }
    var pendingDelete by remember { mutableStateOf<DoseEvent?>(null) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val series = remember(analyte, horizon, state.doses, state.labs, state.profile, state.calibration) {
        PkEngine.curve(analyte, state.doses, state.labs, state.profile, state.calibration, horizon)
    }
    val unitLabel = when (analyte) {
        Analyte.E2 -> unitE2
        Analyte.TESTOSTERONE -> unitT
        Analyte.CPA -> Analyte.CPA.defaultUnit
    }
    val displaySeries = remember(series, unitLabel) { series?.withDisplayUnit(unitLabel) }
    var selectedHour by remember(series) { mutableDoubleStateOf(0.0) }
    val hrtDays = PkEngine.hrtDays(state.doses)
    val effectiveStart = PkEngine.effectiveHrtStartDate(state.doses)
    val batchSpanDays = ChronoUnit.DAYS.between(batchStartDate, batchEndDate).toInt() + 1
    val batchCount = runCatching {
        val interval = batchInterval.trim().toInt()
        require(!batchEndDate.isBefore(batchStartDate) && interval in 1..30)
        (ChronoUnit.DAYS.between(batchStartDate, batchEndDate) / interval + 1).toInt()
    }.getOrNull()

    Column(
        Modifier.verticalScroll(scrollState).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppHeader("YukiHRT", "你的激素曲线 · 群体先验 · 本机个体 PK/PD 拟合 · 不作为调量依据")

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FoldCard(
                title = "01 · 基本信息",
                caption = "体重只用于粗略异速缩放，不能决定个体代谢",
                tint = SoftPink,
                preview = "体重 ${weight.ifBlank { "—" }} kg · 基线 T ${baselineT.ifBlank { "未填" }}",
                initiallyExpanded = true
            ) {
                AppTextField(weight, { weight = it }, "体重（kg）", keyboardType = KeyboardType.Decimal)
                AppTextField(
                    baselineT,
                    { baselineT = it },
                    "未治疗基线 T（ng/dL，可选）",
                    keyboardType = KeyboardType.Decimal,
                    supporting = "仅填写开始 HRT 前、未治疗时的检测值；化验单为 nmol/L 时先 ×28.85 换算为 ng/dL"
                )
                Button(
                    onClick = { message = viewModel.saveProfile(weight, baselineT, "ng/dL") ?: "基本信息与个体模型已更新" },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存信息并更新曲线") }
                if (state.doses.isNotEmpty() && effectiveStart != null) {
                    Text(
                        if (hrtDays >= 90) {
                            "HRT 起点按最早给药记录自动取：$effectiveStart（已 $hrtDays 天），可查看 T 曲线"
                        } else {
                            "HRT 起点按最早给药记录自动取：$effectiveStart（已 $hrtDays 天），满 90 天后显示 T 趋势"
                        },
                        color = MutedInk,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            FoldCard(
                title = "02 · 添加给药记录",
                caption = "输入实际发生的时间与剂量；这里不提供推荐值",
                preview = "当前选择：${drug.label} · ${if (drug == Drug.CPA) "口服" else route.label}",
                initiallyExpanded = true
            ) {
                ChoiceChips(
                    options = Drug.entries.map { it to it.label },
                    selected = drug,
                    onSelect = {
                        drug = it
                        if (it == Drug.CPA) {
                            route = Route.ORAL
                            batchRoute = Route.ORAL
                        }
                    }
                )
                if (drug == Drug.ESTRADIOL_VALERATE) {
                    ChoiceChips(
                        options = Route.entries.map { it to it.label },
                        selected = route,
                        onSelect = { route = it }
                    )
                } else {
                    NoticeBanner("CPA v1.0 仅预设口服途径。")
                }
                AppTextField(dose, { dose = it }, "记录剂量（mg）", keyboardType = KeyboardType.Decimal)
                DateTimeField(
                    label = "给药时间",
                    value = doseAt,
                    maxDate = LocalDate.now(),
                    supporting = "点选日期与时刻；记录的是实际发生的给药时间，补录请选「1 小时前」这类快捷项",
                    onChange = { doseAt = it }
                )
                Button(
                    onClick = {
                        message = viewModel.addDose(
                            drug,
                            route,
                            dose,
                            doseAt.format(MainViewModel.DATE_TIME_FORMAT)
                        ) ?: "已保存给药记录"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存记录并重新拟合") }
                if (message.isNotBlank()) Text(message, color = AccentBlue, style = MaterialTheme.typography.bodyMedium)
            }

            FoldCard(
                title = "03 · 批量加入历史给药",
                caption = "适合补录长期规律方案，例如某月每天同一时间服用相同剂量 CPA",
                tint = SoftLavender,
                preview = "${batchDose.ifBlank { "—" }} mg · 近 $batchSpanDays 天 · 每天 ${
                    batchClock.format(MainViewModel.BATCH_TIME_FORMAT)
                }",
                initiallyExpanded = false
            ) {
                Text("药物沿用上方选择；批量记录可独立填写剂量与服用方式，口服与舌下会生成不同的浓度曲线。")
                AppTextField(
                    batchDose,
                    { batchDose = it },
                    "批量剂量（mg）",
                    keyboardType = KeyboardType.Decimal
                )
                if (drug == Drug.ESTRADIOL_VALERATE) {
                    Text("批量服用方式", style = MaterialTheme.typography.titleMedium)
                    ChoiceChips(
                        options = Route.entries.map { it to it.label },
                        selected = batchRoute,
                        onSelect = { batchRoute = it }
                    )
                    Text(
                        when (batchRoute) {
                            Route.ORAL -> "口服：Tmax 约 10 h，峰值较低但维持较久。"
                            Route.SUBLINGUAL -> "舌下：Tmax 约 1 h，峰值更高、衰减更快；直接人体证据不足，情景范围更宽。"
                        },
                        color = MutedInk,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    NoticeBanner("CPA 仅有口服预设，批量记录会按口服生成。")
                }
                ChoiceChips(
                    options = listOf(7 to "近 7 天", 30 to "近 30 天", 90 to "近 90 天"),
                    selected = batchSpanDays,
                    onSelect = { days ->
                        batchEndDate = LocalDate.now()
                        batchStartDate = LocalDate.now().minusDays((days - 1).toLong())
                    }
                )
                DatePickerField(
                    label = "开始日期（含）",
                    value = batchStartDate,
                    maxDate = batchEndDate,
                    onChange = { batchStartDate = it }
                )
                DatePickerField(
                    label = "结束日期（含）",
                    value = batchEndDate,
                    minDate = batchStartDate,
                    maxDate = LocalDate.now(),
                    supporting = "批量记录只能是历史；日历里晚于今天的日期不可选",
                    onChange = { batchEndDate = it }
                )
                ClockField(
                    label = "每日给药时间",
                    value = batchClock,
                    supporting = "补录历史时，这个时刻决定每天的实际给药时间",
                    onChange = { batchClock = it }
                )
                AppTextField(batchInterval, { batchInterval = it }, "间隔天数（1 = 每天）", keyboardType = KeyboardType.Decimal)
                if (batchCount != null && batchCount in 1..1000) {
                    Text(
                        "将生成约 $batchCount 条记录；单次上限 1000 条，重复的同一时间点会自动跳过。",
                        color = MutedInk,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(
                    onClick = {
                        batchResult = viewModel.addDoseSeries(
                            drug,
                            batchRoute,
                            batchDose,
                            batchStartDate.toString(),
                            batchEndDate.toString(),
                            batchClock.format(MainViewModel.BATCH_TIME_FORMAT),
                            batchInterval
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (batchCount != null && batchCount in 1..1000) "生成 $batchCount 条历史记录" else "按规则生成历史记录"
                    )
                }
                batchResult?.let { result ->
                    NoticeBanner(result.message, warning = !result.success, success = result.success)
                    if (result.success) {
                        Text(
                            "本机现有 ${state.doses.size} 条给药记录；曲线与 T 趋势已重新计算。",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                analyte = Analyte.E2
                                scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                            }) { Text("查看 E2 曲线") }
                            TextButton(onClick = {
                                analyte = Analyte.TESTOSTERONE
                                scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                            }) { Text("查看 T 曲线") }
                        }
                    }
                }
                NoticeBanner("长期重复给药会按全部记录线性叠加，因此 CPA 等长半衰期药物会显示累积；模型不计漏服、呕吐、药片批次、饱和代谢或清除率变化。", warning = true)
            }

            FoldCard(
                title = "为什么口服 ≠ 舌下",
                caption = "同一毫克数不能直接共用一条曲线",
                tint = SoftBlue,
                preview = "口服 EV Tmax 10 h；舌下 E2 峰值更高、衰减更快（双相 t½ 0.8 + 14 h）",
                initiallyExpanded = false
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("口服 EV 参考 Tmax", "10 h", Modifier.weight(1f))
                    Metric("舌下 E2 参考 Tmax", "0.7 h", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("舌下 t½（分布 / 终末）", "0.8 / 14 h", Modifier.weight(1f))
                    Metric("终末相占 AUC", "70%", Modifier.weight(1f))
                }
                Text("口服 EV 经肠道水解及首过代谢，E2 上升较缓；舌下 17β-E2 研究显示更快、更高的峰，且 2 h 内就会明显回落。舌下按「快速分布相 + 14 h 终末相」的双相曲线计算：峰值决定尖峰，终末相决定 8–24 h 的尾巴和每天多次给药的蓄积。舌下 EV 的直接人体数据不足，本应用仅按摩尔当量外推，并加宽情景范围。")
                Text("实际含服常是“口腔吸收 + 部分吞服”的混合过程。给药途径改变应由医生评估，不能用本曲线自行换算剂量。", color = Danger)
            }

            if (state.doses.isNotEmpty()) {
                FoldCard(
                    title = "04 · 历史回溯与未来预测",
                    caption = "当前时间为 0；左半为历史叠加浓度，右半为未来预测",
                    preview = "${analyte.label} · 窗口 ±${horizon / 2} h",
                    initiallyExpanded = true
                ) {
                    ChoiceChips(
                        options = Analyte.entries.map { it to it.label },
                        selected = analyte,
                        onSelect = { analyte = it }
                    )
                    val unitChoices = displaySeries?.absoluteUnitOptions().orEmpty()
                    if (unitChoices.size > 1) {
                        Text("纵轴单位", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                        ChoiceChips(
                            options = unitChoices.map { it.label to it.label },
                            selected = unitLabel,
                            onSelect = { picked ->
                                when (analyte) {
                                    Analyte.E2 -> unitE2 = picked
                                    Analyte.TESTOSTERONE -> unitT = picked
                                    else -> Unit
                                }
                            }
                        )
                    }
                    ChoiceChips(
                        options = listOf(24 to "±12 h", 48 to "±24 h", 72 to "±36 h", 168 to "±84 h"),
                        selected = horizon,
                        onSelect = { horizon = it }
                    )
                    AnimatedContent(
                        targetState = displaySeries,
                        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(180)) },
                        label = "curve-switch"
                    ) { current ->
                        if (current == null) {
                            NoticeBanner(
                                when {
                                    analyte != Analyte.TESTOSTERONE -> "当前项目没有对应给药记录。请先添加记录。"
                                    state.doses.isEmpty() -> "还没有给药记录。导入至少 90 天的历史记录后，才显示 T 趋势。"
                                    hrtDays < 90 -> "按最早给药记录回推的 HRT 起点为 $effectiveStart，仅 $hrtDays 天，需满 90 天；还差 ${90 - hrtDays} 天。可在 03 批量补录更早的记录。"
                                    else -> "T 曲线暂不可用。请检查给药记录。"
                                },
                                warning = true
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CurveChart(current, selectedHour)
                                NoticeBanner(current.notice, warning = !current.quantitative)
                                val nearest = current.points.minByOrNull { abs(it.hours - selectedHour) }
                                Text(
                                    "时间游标：${"%.1f".format(selectedHour)} h（负数 = 历史，正数 = 未来）"
                                )
                                Slider(
                                    value = selectedHour.toFloat(),
                                    onValueChange = { selectedHour = it.toDouble() },
                                    valueRange = -(horizon / 2f)..(horizon / 2f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Metric(
                                        if (analyte == Analyte.TESTOSTERONE) "游标趋势" else "游标 Cp(t)",
                                        "${"%.1f".format(nearest?.value ?: 0.0)} ${current.unit}",
                                        Modifier.weight(1f)
                                    )
                                    Metric(
                                        "窗口内峰值",
                                        "${"%.1f".format(current.points.maxOf { it.value })} ${current.unit}",
                                        Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                if (analyte != Analyte.TESTOSTERONE) {
                    val relevantDrug = if (analyte == Analyte.E2) Drug.ESTRADIOL_VALERATE else Drug.CPA
                    val latestRelevant = state.doses.filter { it.drug == relevantDrug }.maxByOrNull { it.takenAtMillis }
                    if (latestRelevant != null) {
                        val fits = remember(relevantDrug, state.labs, state.doses, state.profile.weightKg) {
                            PkEngine.individualFits(relevantDrug, state.labs, state.doses, state.profile.weightKg)
                        }
                        val fit = fits[PkEngine.calibrationKey(latestRelevant.drug, latestRelevant.route)]
                        val p = PkEngine.parameters(latestRelevant.drug, latestRelevant.route, state.profile.weightKg, fit)
                        FoldCard(
                            title = "当前个体参数与 Cp 公式",
                            caption = p.evidenceLabel,
                            tint = SoftLavender,
                            preview = if (p.triPhase) {
                                "单次终末 ${"%.1f".format(p.apparentTerminalHalfLifeHours)} h · 长期终末 ${
                                    "%.1f".format(p.longTermTerminalHalfLifeHours)
                                } h · CL/F ${"%.1f".format(p.apparentClearance)} L/h"
                            } else if (p.dualPhase) {
                                "峰值后降半 ${"%.1f".format(p.apparentHalfLifeHours)} h · 终末 t½ ${
                                    "%.1f".format(p.terminalHalfLifeHours)
                                } h · CL/F ${"%.1f".format(p.apparentClearance)} L/h"
                            } else {
                                "t½ ${"%.1f".format(p.terminalHalfLifeHours)} h · CL/F ${
                                    "%.1f".format(p.apparentClearance)
                                } L/h · Vd/F ${"%.0f".format(p.apparentVolume)} L"
                            },
                            initiallyExpanded = false
                        ) {
                            if (fit != null && fit.sampleCount > 0) {
                                NoticeBanner("${fit.level.label} · ${fit.sampleCount} 点 / ${fit.distinctDoseCount} 次给药 · 训练内对数 RMSE ${"%.2f".format(fit.logRmse)}")
                            }
                            Text(
                                PkEngine.fitHint(
                                    fit ?: IndividualPkFit(latestRelevant.drug, latestRelevant.route),
                                    latestRelevant.drug,
                                    latestRelevant.route,
                                    state.profile.weightKg
                                ),
                                color = AccentBlue,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Metric("表观 CL/F", "${"%.2f".format(p.apparentClearance)} L/h", Modifier.weight(1f))
                                Metric("表观 Vd/F", "${"%.1f".format(p.apparentVolume)} L", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Metric(
                                    if (p.dualPhase) "峰值后降半" else "t½",
                                    "${"%.1f".format(if (p.dualPhase) p.apparentHalfLifeHours else p.terminalHalfLifeHours)} h",
                                    Modifier.weight(1f)
                                )
                                Metric("Tmax", "${"%.1f".format(p.tmaxHours)} h", Modifier.weight(1f))
                            }
                            if (p.triPhase) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Metric(
                                        "单次终末 t½",
                                        "${"%.1f".format(p.apparentTerminalHalfLifeHours)} h（≈${
                                            "%.1f".format(p.apparentTerminalHalfLifeHours / 24.0)
                                        } 天）",
                                        Modifier.weight(1f)
                                    )
                                    Metric(
                                        "长期终末 t½",
                                        "${"%.1f".format(p.longTermTerminalHalfLifeHours)} h（≈${
                                            "%.1f".format(p.longTermTerminalHalfLifeHours / 24.0)
                                        } 天）",
                                        Modifier.weight(1f)
                                    )
                                }
                                Text(
                                    "三相处置：主相 ${"%.0f".format(p.halfLifeHours)} h、浅室 ${
                                        "%.1f".format(p.slowHalfLifeHours)
                                    } h、深室 ${"%.1f".format(p.deepHalfLifeHours)} h（脂肪 depot）。" +
                                        "单次给药时深室只占 ${"%.1f".format(p.deepFraction * 100)}%，表观终末相约 ${
                                            "%.1f".format(p.apparentTerminalHalfLifeHours)
                                        } h（近两天）；连续服药后深室蓄积倍数远大于浅室，尾部改由深室主导，" +
                                        "终末相延长到 ${"%.1f".format(p.longTermTerminalHalfLifeHours)} h（三天多），" +
                                        "谷浓度蓄积约 ×3.4（Kuhnz 1993：单次 54 h → 3 周期后 78.6 h）。",
                                    color = MutedInk,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else if (p.dualPhase) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Metric(
                                        "终末 t½",
                                        "${"%.1f".format(p.terminalHalfLifeHours)} h（≈${
                                            "%.1f".format(p.terminalHalfLifeDays)
                                        } 天）",
                                        Modifier.weight(1f)
                                    )
                                    Metric("终末相承担", "${"%.0f".format(p.slowAucShare * 100)}% AUC", Modifier.weight(1f))
                                }
                                Text(
                                    "「峰值后降半」从达峰时刻起算：${"%.1f".format(p.apparentHalfLifeHours)} h，" +
                                        "换成给药后是约 ${"%.1f".format(p.apparentHalfLifeHours + p.tmaxHours)} h 才减半。" +
                                        "「终末 t½」描述 24 h 之后的尾巴，决定每日给药蓄积多少。双相曲线两者本来就不相等。",
                                    color = MutedInk,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            FormulaBlock(
                                if (p.triPhase) {
                                    "k₁ = ${"%.4f".format(p.eliminationRate)} h⁻¹（主相 ${
                                        "%.1f".format((1.0 - p.slowFraction - p.deepFraction) * 100)
                                    }%）\n" +
                                        "k₂ = ${"%.4f".format(p.slowEliminationRate)} h⁻¹（浅室 ${
                                            "%.1f".format(p.slowFraction * 100)
                                        }%）\n" +
                                        "k₃ = ${"%.4f".format(p.deepEliminationRate)} h⁻¹（深室 ${
                                            "%.1f".format(p.deepFraction * 100)
                                        }%）\n" +
                                        "CL/F = (Vd/F) ÷ Σ wᵢ/kᵢ = ${"%.2f".format(p.apparentClearance)} L/h\n" +
                                        "个体化：Vd/F ÷ fE；k₁、k₂、k₃ × fKe；ka × fKa\n" +
                                        "fE=${"%.2f".format(fit?.exposureFactor ?: 1.0)}，fKe=${"%.2f".format(fit?.eliminationFactor ?: 1.0)}，fKa=${"%.2f".format(fit?.absorptionFactor ?: 1.0)}"
                                } else if (p.dualPhase) {
                                    "k₁ = ${"%.4f".format(p.eliminationRate)} h⁻¹（主相，f=${"%.1f".format(p.slowFraction * 100)}%）\n" +
                                        "k₂ = ${"%.4f".format(p.slowEliminationRate)} h⁻¹（终末）\n" +
                                        "CL/F = (Vd/F) ÷ [(1−f)/k₁ + f/k₂] = ${"%.2f".format(p.apparentClearance)} L/h\n" +
                                        "个体化：Vd/F ÷ fE；k₁、k₂ × fKe；ka × fKa\n" +
                                        "fE=${"%.2f".format(fit?.exposureFactor ?: 1.0)}，fKe=${"%.2f".format(fit?.eliminationFactor ?: 1.0)}，fKa=${"%.2f".format(fit?.absorptionFactor ?: 1.0)}"
                                } else {
                                    "ke = (CL/F) ÷ (Vd/F) = ${"%.4f".format(p.eliminationRate)} h⁻¹\n" +
                                        "Cp(t) = D × K ÷ (Vd/F) × ka/(ka−ke)\n" +
                                        "        × (e^(−ke·t) − e^(−ka·t))\n" +
                                        "个体化：Vd/F ÷ fE；ke × fKe；ka × fKa\n" +
                                        "fE=${"%.2f".format(fit?.exposureFactor ?: 1.0)}，fKe=${"%.2f".format(fit?.eliminationFactor ?: 1.0)}，fKa=${"%.2f".format(fit?.absorptionFactor ?: 1.0)}"
                                }
                            )
                            Text(
                                "当前途径 ${p.route.label} 的血药浓度全部由本途径的实测 Cmax / Tmax / t½ 锚定，生物利用度已经包含在锚点里；T 抑制直接使用这条曲线，不另乘 F。",
                                color = AccentBlue,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text("这些仍是有先验约束的曲线等效表观参数，不是测得的个体生理清除率；训练内拟合较好不等于未来预测准确。", color = MutedInk)
                        }
                    }
                }

                FoldCard(
                    title = "给药记录",
                    caption = "共 ${state.doses.size} 条；删除给药会同时删除其关联化验点",
                    preview = "最近一次：${state.doses.maxByOrNull { it.takenAtMillis }?.let {
                        "${it.drug.shortLabel} ${trimNumber(it.doseMg)} mg · ${MainViewModel.formatDateTime(it.takenAtMillis)}"
                    } ?: "—"}",
                    initiallyExpanded = false
                ) {
                    state.doses.asReversed().take(8).forEach { event ->
                        DoseRow(event) { pendingDelete = event }
                    }
                    if (state.doses.size > 8) {
                        Text("仅展开显示最近 8 条；全部记录仍参与计算。", color = MutedInk)
                    }
                }
            } else {
                NoticeBanner("还没有给药记录。完成上方两步后，这里会显示 E2、CPA 曲线及平台期 T 趋势。")
            }

            NoticeBanner("任何曲线、Cp、理论 Md 或 AI 文本都不能替代血检与医生判断。不要根据本应用自行开始、停用或调整药物。", warning = true)
        }
    }

    pendingDelete?.let { event ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条给药记录？") },
            text = { Text("${event.drug.label} · ${trimNumber(event.doseMg)} mg\n${MainViewModel.formatDateTime(event.takenAtMillis)}\n关联化验点也会删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDose(event.id)
                    pendingDelete = null
                }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}
