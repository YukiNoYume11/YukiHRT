package cn.hrtcurve.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun LabsScreen(state: AppState, viewModel: MainViewModel) {
    var analyte by remember { mutableStateOf(Analyte.E2) }
    var selectedDoseId by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableStateOf("4") }
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("pg/mL") }
    var message by remember { mutableStateOf("") }
    var calibrationDrug by remember { mutableStateOf(Drug.ESTRADIOL_VALERATE) }
    var calibrationRoute by remember { mutableStateOf(Route.ORAL) }

    val candidates = state.doses.filter {
        when (analyte) {
            Analyte.E2 -> it.drug == Drug.ESTRADIOL_VALERATE
            Analyte.CPA -> it.drug == Drug.CPA
            Analyte.TESTOSTERONE -> true
        }
    }
    val selectedDose = candidates.firstOrNull { it.id == selectedDoseId } ?: candidates.lastOrNull()
    LaunchedEffect(analyte) {
        unit = when (analyte) {
            Analyte.E2 -> "pg/mL"
            Analyte.CPA -> "ng/mL"
            Analyte.TESTOSTERONE -> "ng/dL"
        }
    }
    // 默认选中「真正有数据」的途径。以前固定口服，舌下记录的用户会一直看到“无有效点”。
    LaunchedEffect(calibrationDrug, state.labs, state.doses) {
        if (calibrationDrug == Drug.CPA) {
            calibrationRoute = Route.ORAL
            return@LaunchedEffect
        }
        val byLabs = state.labs.filter { it.drug == Drug.ESTRADIOL_VALERATE }
            .groupingBy { it.route }.eachCount()
        val byDoses = state.doses.filter { it.drug == Drug.ESTRADIOL_VALERATE }
            .groupingBy { it.route }.eachCount()
        val best = (byLabs.maxByOrNull { it.value } ?: byDoses.maxByOrNull { it.value })?.key
        if (best != null) calibrationRoute = best
    }

    // 拟合结果按输入缓存：以前每次重组（包括每敲一个字）都会把全部采样点重拟合一遍，这是卡顿的主要来源。
    val fitsE2 = remember(state.labs, state.doses, state.profile.weightKg) {
        PkEngine.individualFits(Drug.ESTRADIOL_VALERATE, state.labs, state.doses, state.profile.weightKg)
    }
    val fitsCpa = remember(state.labs, state.doses, state.profile.weightKg) {
        PkEngine.individualFits(Drug.CPA, state.labs, state.doses, state.profile.weightKg)
    }
    val pd = remember(state.labs, state.doses, state.profile) {
        PkEngine.testosteroneFit(state.labs, state.doses, state.profile)
    }
    val predictedById = remember(state.labs, state.doses, state.profile.weightKg, fitsE2, fitsCpa) {
        state.labs.associate { sample ->
            sample.id to when (sample.analyte) {
                Analyte.E2 -> PkEngine.totalConcentration(
                    Drug.ESTRADIOL_VALERATE, sample.sampledAtMillis, state.doses,
                    state.profile.weightKg, individualFits = fitsE2
                )
                Analyte.CPA -> PkEngine.totalConcentration(
                    Drug.CPA, sample.sampledAtMillis, state.doses,
                    state.profile.weightKg, individualFits = fitsCpa
                )
                Analyte.TESTOSTERONE -> null
            }
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppHeader("实测校准", "用时间与化验值校正暴露，而不是让模型迎合单次结果")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FoldCard(
                title = "添加监测点",
                caption = "采样时间 = 关联给药时间 + 距给药小时数",
                preview = "${analyte.label} · ${unit} · 关联 ${selectedDose?.let { MainViewModel.formatDateTime(it.takenAtMillis) } ?: "未选择给药"}",
                initiallyExpanded = true
            ) {
                ChoiceChips(options = Analyte.entries.map { it to it.label }, selected = analyte, onSelect = { analyte = it })
                if (candidates.isEmpty()) {
                    NoticeBanner("没有可关联的给药记录，请先在曲线页添加。", warning = true)
                } else {
                    DoseSelector(candidates, selectedDose) { selectedDoseId = it.id }
                }
                AppTextField(elapsed, { elapsed = it }, "距关联给药的时间（小时）", keyboardType = KeyboardType.Decimal)
                AppTextField(value, { value = it }, "实测值", keyboardType = KeyboardType.Decimal)
                when (analyte) {
                    Analyte.E2 -> ChoiceChips(
                        options = listOf("pg/mL" to "pg/mL", "pmol/L" to "pmol/L"),
                        selected = unit,
                        onSelect = { unit = it }
                    )
                    Analyte.CPA -> NoticeBanner("CPA 单位固定为 ng/mL（与 µg/L 数值相等）。")
                    Analyte.TESTOSTERONE -> ChoiceChips(
                        options = listOf("ng/dL" to "ng/dL", "nmol/L" to "nmol/L"),
                        selected = unit,
                        onSelect = { unit = it }
                    )
                }
                Button(
                    enabled = selectedDose != null,
                    onClick = {
                        val dose = selectedDose ?: return@Button
                        val result = viewModel.addLab(analyte, dose, elapsed, value, unit)
                        message = result ?: "实测点已保存；E2 输入若为 pmol/L 已换算为 pg/mL"
                        if (result == null) value = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存实测点") }
                if (message.isNotBlank()) Text(message, color = AccentBlue)
            }

            FoldCard(
                title = "仅属于当前记录的个体 PK",
                caption = "保存实测点后自动重拟合；群体先验作为收缩约束",
                tint = SoftLavender,
                preview = "${calibrationDrug.shortLabel} · ${calibrationRoute.label} · 当前层级 ${
                    (if (calibrationDrug == Drug.ESTRADIOL_VALERATE) fitsE2 else fitsCpa)[
                        PkEngine.calibrationKey(calibrationDrug, calibrationRoute)
                    ]?.takeIf { it.sampleCount > 0 }?.level?.label ?: "群体先验"
                }",
                initiallyExpanded = false
            ) {
                ChoiceChips(options = Drug.entries.map { it to it.shortLabel }, selected = calibrationDrug, onSelect = {
                    calibrationDrug = it
                    if (it == Drug.CPA) calibrationRoute = Route.ORAL
                })
                if (calibrationDrug == Drug.ESTRADIOL_VALERATE) {
                    ChoiceChips(options = Route.entries.map { it to it.label }, selected = calibrationRoute, onSelect = { calibrationRoute = it })
                }
                val fits = if (calibrationDrug == Drug.ESTRADIOL_VALERATE) fitsE2 else fitsCpa
                val fit = fits[PkEngine.calibrationKey(calibrationDrug, calibrationRoute)]
                    ?: IndividualPkFit(calibrationDrug, calibrationRoute)
                val expectedAnalyte = if (calibrationDrug == Drug.ESTRADIOL_VALERATE) Analyte.E2 else Analyte.CPA
                val analyteSamples = state.labs.filter { it.analyte == expectedAnalyte && it.drug == calibrationDrug }
                Metric("当前拟合层级", if (fit.sampleCount > 0) fit.level.label else "群体先验（该途径暂无可用点）")
                if (fit.sampleCount > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Metric("暴露 / 消除", "×${"%.2f".format(fit.exposureFactor)} / ×${"%.2f".format(fit.eliminationFactor)}", Modifier.weight(1f))
                        Metric("吸收 / 残差", "×${"%.2f".format(fit.absorptionFactor)} / ${"%.2f".format(fit.logRmse)}", Modifier.weight(1f))
                    }
                    Text("使用 ${fit.sampleCount} 个实测点、${fit.distinctDoseCount} 次关联给药；残差为训练内对数 RMSE。", color = MutedInk)
                } else if (analyteSamples.isNotEmpty()) {
                    val distribution = analyteSamples.groupingBy { it.route.label }.eachCount()
                        .entries.joinToString("、") { "${it.key} ${it.value} 个" }
                    NoticeBanner(
                        "本机共有 ${analyteSamples.size} 个 ${expectedAnalyte.label} 实测点（$distribution），" +
                            "但当前选择的 ${calibrationDrug.shortLabel} · ${calibrationRoute.label} 下找不到可匹配的给药记录。" +
                            "请在下方选择有数据的途径，或补录对应途径的给药记录。",
                        warning = true
                    )
                } else {
                    Text("该药物还没有 ${expectedAnalyte.label} 实测点。保存任意一个点后会立即参与拟合。", color = MutedInk)
                }
                FormulaBlock(
                    "目标：Σ[ln(C实测/C预测)/0.30]² + Σ(η/σ)²\n" +
                        "η = ln(个体参数/群体参数)，参数始终向群体先验收缩\n\n" +
                        "参考时机（只决定能识别几个参数，不是拟合前提）\n" +
                        "1 点 → 先校准整体暴露\n" +
                        "2 点且含峰后 → 开始估消除\n" +
                        "3 点且覆盖峰前 + 峰后 → 开始估吸收"
                )
                Text(
                    PkEngine.fitHint(fit, calibrationDrug, calibrationRoute, state.profile.weightKg),
                    color = AccentBlue
                )
                Button(onClick = {
                    message = viewModel.recalibrate(calibrationDrug, calibrationRoute)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("查看当前拟合诊断")
                }
                Text("少点时先验自动收得更紧，参数不会因为 1–2 个点大幅漂移；点数只影响可识别度，不影响是否拟合。时间错误、检测方法变化与非稳态采样仍会误导模型，不得据此调量。", color = Danger)
            }

            FoldCard(
                title = "个体睾酮 PD",
                caption = "没有基线也能拟合：基线由 T 实测点联估",
                tint = SoftBlue,
                preview = if (pd.sampleCount > 0) {
                    "${pd.level.label} · ${pd.sampleCount} 点 · RMSE ${"%.2f".format(pd.logRmse)}"
                } else {
                    "尚未进行 PD 拟合"
                },
                initiallyExpanded = false
            ) {
                val suppliedBaseline = state.profile.baselineTestosteroneNgDl
                Text(
                    when {
                        suppliedBaseline != null -> "你填写的未治疗基线：${trimNumber(suppliedBaseline)} ng/dL"
                        pd.baselineFitted && pd.sampleCount > 0 ->
                            "未填写基线：由 ${pd.sampleCount} 个 T 点联估为 ${"%.0f".format(pd.baselineNgDl ?: 0.0)} ng/dL（受群体先验 550 约束）"
                        else -> "未填写基线：录入第一个治疗中 T 点后会立即联估基线"
                    }
                )
                Text(
                    "状态：" + if (pd.sampleCount > 0) {
                        "${pd.level.label} · ${pd.sampleCount} 点 · 对数 RMSE ${"%.2f".format(pd.logRmse)}"
                    } else {
                        "尚未进行 PD 拟合"
                    }
                )
                Text(
                    when {
                        pd.sampleCount == 0 && suppliedBaseline == null ->
                            "没有基线不再是限制。第一个治疗中 T 点保存后就会立即拟合，此时 Emax 取群体先验、基线按该点锚定；补到第 2 个点后 Emax 也会开始估计。"
                        pd.sampleCount == 0 ->
                            "基线已就绪。添加第一个治疗中 T 点后会立即拟合 Emax，不需要等到凑够点数。"
                        pd.baselineFitted ->
                            "基线是估计值而不是化验值，所以绝对数值可信度低于填写真实未治疗基线的情况；补 2 个以上不同日期的点会让基线与 Emax 更稳定。"
                        else ->
                            "第 1 个 T 点就会校准 Emax；补到 3 个点、跨 2 个日期且 E2 暴露有变化后，再拆出 E2 敏感度。这只是可识别性建议，不是使用前提。"
                    }
                )
            }

            FoldCard(
                title = "监测点记录",
                caption = "共 ${state.labs.size} 条",
                preview = "最近一次：${state.labs.maxByOrNull { it.sampledAtMillis }?.let {
                    "${it.analyte.label} ${"%.2f".format(it.value)} ${it.unit}"
                } ?: "暂无实测值"}",
                initiallyExpanded = false
            ) {
                if (state.labs.isEmpty()) {
                    Text("暂无实测值。优先记录准确的给药时间、采样时间与检测方法。", color = MutedInk)
                }
                state.labs.asReversed().forEach { sample ->
                    val predicted = predictedById[sample.id]
                    Surface(color = SoftPink, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${sample.analyte.label} · ${"%.2f".format(sample.value)} ${sample.unit}", style = MaterialTheme.typography.titleMedium)
                                Text("距给药 ${trimNumber(sample.elapsedHours)} h · ${MainViewModel.formatDateTime(sample.sampledAtMillis)}", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                                if (predicted != null) Text("当前个体模型 ${"%.2f".format(predicted)} ${sample.analyte.defaultUnit}", color = AccentBlue, style = MaterialTheme.typography.bodyMedium)
                                if (sample.analyte == Analyte.TESTOSTERONE) Text("T 用于单独 PD 拟合，不反推 CPA 或 E2 的 PK 参数", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { viewModel.removeLab(sample.id) }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除实测点", tint = Danger)
                            }
                        }
                    }
                }
            }

            NoticeBanner("尽量使用同一实验室、同一检测方法，并准确记录是否谷值采样。一次实测不能证明整个曲线正确。", warning = true)
        }
    }
}

@Composable
private fun DoseSelector(candidates: List<DoseEvent>, selected: DoseEvent?, onSelect: (DoseEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.let { "${it.drug.shortLabel} · ${it.route.label} · ${MainViewModel.formatDateTime(it.takenAtMillis)}" } ?: "选择关联给药",
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            candidates.asReversed().take(12).forEach { dose ->
                DropdownMenuItem(
                    text = { Text("${dose.drug.shortLabel} · ${dose.route.label} · ${trimNumber(dose.doseMg)} mg\n${MainViewModel.formatDateTime(dose.takenAtMillis)}") },
                    onClick = {
                        onSelect(dose)
                        expanded = false
                    }
                )
            }
        }
    }
}
