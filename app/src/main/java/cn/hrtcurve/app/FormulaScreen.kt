package cn.hrtcurve.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun FormulaScreen(state: AppState) {
    var drug by remember { mutableStateOf(Drug.ESTRADIOL_VALERATE) }
    var route by remember { mutableStateOf(Route.ORAL) }
    var target by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("24") }
    val p = PkEngine.parameters(drug, route, state.profile.weightKg)
    val targetValue = target.toDoubleOrNull()
    val intervalValue = interval.toDoubleOrNull()
    val md = if (targetValue != null && intervalValue != null) {
        PkEngine.maintenanceDose(drug, route, state.profile.weightKg, targetValue, intervalValue)
    } else null
    val uriHandler = LocalUriHandler.current

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppHeader("公式与证据", "把假设、单位和参考锚点放在同一页，便于审计")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FoldCard(
                title = "01 · 选择模型",
                caption = "参数随当前记录体重 ${trimNumber(state.profile.weightKg)} kg 更新",
                preview = "${drug.label} · ${if (drug == Drug.CPA) "口服" else route.label}",
                initiallyExpanded = true
            ) {
                ChoiceChips(options = Drug.entries.map { it to it.label }, selected = drug, onSelect = {
                    drug = it
                    if (it == Drug.CPA) route = Route.ORAL
                    target = ""
                })
                if (drug == Drug.ESTRADIOL_VALERATE) {
                    ChoiceChips(options = Route.entries.map { it to it.label }, selected = route, onSelect = {
                        route = it
                        target = ""
                    })
                }
                NoticeBanner(p.evidenceLabel, warning = drug == Drug.ESTRADIOL_VALERATE && route == Route.SUBLINGUAL)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("参考剂量", "${trimNumber(p.referenceDoseMg)} mg", Modifier.weight(1f))
                    Metric("参考 Cmax", "${"%.2f".format(p.referenceCmax)} ${if (drug == Drug.CPA) "ng/mL" else "pg/mL"}", Modifier.weight(1f))
                }
            }

            FoldCard(
                title = "02 · 体重异速缩放",
                caption = "Wref = ${trimNumber(p.referenceWeightKg)} kg；指数是通用情景假设，并非本人群拟合结果",
                tint = SoftBlue,
                preview = if (p.dualPhase) {
                    "峰值后降半 ${"%.1f".format(p.apparentHalfLifeHours)} h · 终末 t½ ${
                        "%.1f".format(p.terminalHalfLifeHours)
                    } h · Tmax ${"%.1f".format(p.tmaxHours)} h"
                } else {
                    "t½ ${"%.1f".format(p.terminalHalfLifeHours)} h · Tmax ${
                        "%.1f".format(p.tmaxHours)
                    } h · CL/F ${"%.1f".format(p.apparentClearance)} L/h"
                },
                initiallyExpanded = true
            ) {
                FormulaBlock(
                    "Vd/F = (Vd/F)ref × (W / Wref)\n" +
                        "CL/F = (CL/F)ref × (W / Wref)^0.75\n" +
                        "ke = (CL/F) / (Vd/F)\n" +
                        "tmax = ln(ka/ke) / (ka−ke)"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("CL/F", "${"%.3f".format(p.apparentClearance)} L/h", Modifier.weight(1f))
                    Metric("Vd/F", "${"%.2f".format(p.apparentVolume)} L", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("ka", "${"%.3f".format(p.absorptionRate)} h⁻¹", Modifier.weight(1f))
                    Metric("Tmax", "${"%.1f".format(p.tmaxHours)} h", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (p.triPhase) {
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
                    } else {
                        Metric(
                            if (p.dualPhase) "终末相 t½" else "t½",
                            "${"%.1f".format(p.terminalHalfLifeHours)} h（≈${"%.1f".format(p.terminalHalfLifeDays)} 天）",
                            Modifier.weight(1f)
                        )
                        if (p.dualPhase) {
                            Metric("峰值后降半", "${"%.1f".format(p.apparentHalfLifeHours)} h", Modifier.weight(1f))
                        } else {
                            Metric("终末相 AUC", "100%", Modifier.weight(1f))
                        }
                    }
                }
                Text("这里的 CL/F、Vd/F 是在「单相 / 两相 / 三相」结构上同时复现公开 Cmax、Tmax、t½ 与 AUC 的曲线等效参数（CL/F = D/AUC）。CPA 的真实药代仍是多室分布，不能把这些值当成个体生理清除率。")
                if (p.triPhase) {
                    Text(
                        "CPA 是三相处置：主相 ${"%.0f".format(p.halfLifeHours)} h（${
                            "%.1f".format((1.0 - p.slowFraction - p.deepFraction) * 100)
                        }%）、浅室 ${"%.1f".format(p.slowHalfLifeHours)} h（${
                            "%.1f".format(p.slowFraction * 100)
                        }%）、深室 ${"%.1f".format(p.deepHalfLifeHours)} h（${
                            "%.1f".format(p.deepFraction * 100)
                        }%，脂肪 depot）。深室不是拟合出来的装饰项：Kuhnz 1993 实测 CPA 单次 t½ 54.0 ± 26 h，" +
                            "第 3 周期末延长到 78.6 ± 16 h，Vz 从 986 L 增到 1 304 L，稳态要到第 16 天才平。" +
                            "线性叠加会自动复现这个现象——单次深室只占 ${"%.1f".format(p.deepFraction * 100)}%，" +
                            "表观终末约 ${"%.1f".format(p.apparentTerminalHalfLifeHours)} h（近两天）；" +
                            "连续服药后深室的蓄积倍数 1/(1−e^−kd·τ) 远大于浅室，尾部改由深室主导，" +
                            "停药后的斜率延长到 ${"%.1f".format(p.longTermTerminalHalfLifeHours)} h（三天多）。",
                        color = AccentBlue
                    )
                    Text(
                        "50 mg q24 稳态：峰约 285、谷约 152、平均 208 ng/mL，谷蓄积 ×3.4、峰蓄积 ×2.0；" +
                            "Frolich 1980 长期 50 mg/日 的间隔采血范围为 199–228 ng/mL（不是完整 24 h 平均，只作量级对照）。说明书「每日给药蓄积约 3 倍」与这里的谷值口径一致，" +
                            "与这里一致。早期（1.0.9–1.0.13）用两相时无法既让 6 h 保留八成、又让长期半衰期继续延长，" +
                            "这就是「怎么调都不对」的根源。",
                        color = MutedInk
                    )
                } else {
                    Text(
                        "两个半衰期不要混用。「峰值后降半」是从 Cmax 落到一半的实际时长——舌下 E2 1.5 h；" +
                            "「终末相 t½」是 24–120 h 段的对数斜率——舌下 E2 为 14 h，决定蓄积倍数与停药后的清除尾。",
                        color = AccentBlue
                    )
                }
                if (p.dualPhase && !p.triPhase) {
                    Text(
                        "双相处置：主相 t½ ${"%.1f".format(p.halfLifeHours)} h + 终末相 t½ ${"%.1f".format(p.slowHalfLifeHours)} h。" +
                            "终末相承担 ${"%.0f".format(p.slowAucShare * 100)}% 的 AUC（按剂量份额 ${"%.1f".format(p.slowFraction * 100)}% 拆分，" +
                            "AUCᵢ = wᵢ/kᵢ，所以两者不相等）。舌下单相会把 12 h 之后的尾巴压没，所以必须留终末相。",
                        color = MutedInk
                    )
                }
            }

            FoldCard(
                title = "03 · Cp 与多次给药",
                caption = "E2 的 K = 1,000,000；CPA 的 K = 1,000",
                preview = "多相公式（单 / 双 / 三相）、AUC 与 CL/F 的自洽关系、合并给药的线性叠加",
                initiallyExpanded = false
            ) {
                FormulaBlock(
                    "Cp(t) = D × K / (Vd/F) × ka/(ka−ke)\n" +
                        "        × [e^(−ke·t) − e^(−ka·t)]\n\n" +
                        "双相（舌下 E2、CPA）：\n" +
                        "Cp(t) = D × K / (Vd/F) × {\n" +
                        "  (1−f)·ka/(ka−ke₁)·[e^(−ke₁t) − e^(−ka·t)]\n" +
                        "+ f·ka/(ka−ke₂)·[e^(−ke₂t) − e^(−ka·t)] }\n" +
                        "AUC = D×K/(Vd/F) × [(1−f)/ke₁ + f/ke₂]\n" +
                        "CL/F = D/AUC = (Vd/F) ÷ [(1−f)/ke₁ + f/ke₂]\n\n" +
                        "合并给药：Cp,total(t) = Σ Cpᵢ(t)\n" +
                        "个体参数：Vd/F,ind = Vd/F ÷ fE\n" +
                        "ke,ind = ke × fKe；ka,ind = ka × fKa"
                )
                Text("t 为距每次给药的小时数；晚于计算时刻的给药不计入该时刻浓度，但右半未来窗口会包含已录入且即将发生的给药。EV 曲线估算的是水解后血清 E2，不是戊酸雌二醇母体浓度。")
                Text(
                    "口服与舌下分别用实测 Cmax、Tmax、半衰期锚定，因此表观 Vd/F 与 CL/F 已包含两条途径的生物利用度差异；模型不再另乘 F。" +
                        "CPA 的 CL/F 同时受终末斜率（单次 53.7 h、稳态 78.7 h）与总清除率（3.5 ± 1.5 mL/min/kg）约束：50 mg 单次在 70 kg 参考体重下 " +
                        "AUC0-∞ ≈ ${"%.0f".format(PkEngine.singleDoseAuc(Drug.CPA, Route.ORAL, 50.0, 70.0))} ng·h/mL，" +
                        "较生物等效性研究（2×50 mg AUC 6 908 → 折半 3 450）高约 45%，因为当前模型选择清除率下限口径；这不是实测吻合，意味着 12.5 mg 的峰谷绝对值只能作情景外推。",
                    color = MutedInk
                )
            }

            FoldCard(
                title = "04 · 长期给药与蓄积",
                caption = "批量历史记录会全部进入逐次叠加",
                tint = SoftBlue,
                preview = "40 次给药的数值稳态：峰 / 谷 / 平均与蓄积倍数",
                initiallyExpanded = false
            ) {
                FormulaBlock(
                    "等间隔近似：C_n(t) ≈ Csingle(t) × (1−e^(−n·ke·τ)) / (1−e^(−ke·τ))\n" +
                        "稳态近似：C_ss(t) ≈ Csingle(t) / (1−e^(−ke·τ))\n" +
                        "本应用计算：Ctotal(t) = Σ Cpᵢ(t)，包含全部历史记录"
                )
                if (intervalValue != null && intervalValue > 0.0) {
                    val steady = PkEngine.steadyState(drug, route, p.referenceDoseMg, state.profile.weightKg, intervalValue)
                    val slowHalf = if (p.dualPhase) p.slowHalfLifeHours else p.halfLifeHours
                    val dosesTo90 = PkEngine.dosesToNinetyPercent(slowHalf, intervalValue)
                    if (steady != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Metric("稳态峰蓄积", "×${"%.2f".format(steady.peakAccumulation)}", Modifier.weight(1f))
                            Metric("约90%稳态", "${"%.0f".format(dosesTo90)} 次给药", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Metric("稳态峰/谷", "${"%.1f".format(steady.peak)} / ${"%.1f".format(steady.trough)}", Modifier.weight(1f))
                            Metric("稳态平均", "%.1f".format(steady.average), Modifier.weight(1f))
                        }
                        Text(
                            "按 ${trimNumber(p.referenceDoseMg)} mg q${trimNumber(intervalValue)}h、40 次给药逐次叠加数值求解；" +
                                "闭式 1/(1−e^(−ke·τ)) 只在单相且峰时远小于 τ 时成立，双相或慢吸收时会偏差 10–30%。",
                            color = MutedInk
                        )
                    }
                }
                Text("CPA 终末半衰期长，深室蓄积会使长期给药的尾部进一步延长，所以连续每日给药的蓄积非常明显（谷值约 3–4 倍）。但蓄积有有限平台，不会随一年、两年无限上升；文献也没有证明终末半衰期会无限延长。峰到服药后 24 h 谷的下降包含吸收/分布相，与停药后晚段终末半衰期不是同一口径。真实分布受依从性、漏服、肝功能和蛋白结合影响；这里显示的是情景叠加，不是实测谷浓度。", color = Danger)
            }

            FoldCard(
                title = "05 · 个体 PK 拟合",
                caption = "正则化 MAP 思路：实测似然 + 群体先验；全部计算仅在本机",
                tint = SoftPink,
                preview = "1 点即拟合暴露；峰后点加消除；峰前 + 峰后加吸收",
                initiallyExpanded = false
            ) {
                val fit = PkEngine.individualFits(drug, state.labs, state.doses, state.profile.weightKg)[PkEngine.calibrationKey(drug, route)]
                FormulaBlock(
                    "η = ln(θindividual / θpopulation)\n" +
                        "min Σ[ln(Cobs/Cpred)/0.30]² + Σ(η/σ)²\n" +
                        "σE=0.70，σKe=0.35，σKa=0.45\n" +
                        "fE∈[0.2,5]；fKe/fKa∈[1/3,3]"
                )
                Text(
                    if (fit != null && fit.sampleCount > 0) {
                        "当前：${fit.level.label}；fE=${"%.2f".format(fit.exposureFactor)}，fKe=${"%.2f".format(fit.eliminationFactor)}，fKa=${"%.2f".format(fit.absorptionFactor)}；训练内对数 RMSE=${"%.2f".format(fit.logRmse)}。"
                    } else "当前途径没有有效实测点，保持群体先验。",
                    fontWeight = FontWeight.SemiBold
                )
                Text("1 点即拟合 fE；再有 1 个峰后点加入 fKe；3 点且覆盖峰前与峰后才加入 fKa。点少时先验自动收紧，点数只影响可识别度，不保证外部预测表现。", color = Danger)
            }

            FoldCard(
                title = "06 · 理论维持量 Md",
                caption = "仅可输入医生已经确认的讨论目标；仍使用群体参数，不带入个体拟合",
                tint = SoftLavender,
                preview = "不会自动给出剂量：必须先输入医生确认的目标浓度与间隔",
                initiallyExpanded = false
            ) {
                AppTextField(
                    target,
                    { target = it },
                    "医生确认的 Css 讨论目标（${if (drug == Drug.CPA) "ng/mL" else "pg/mL"}）",
                    keyboardType = KeyboardType.Decimal,
                    supporting = "留空则不计算；不要自行填写所谓“理想浓度”"
                )
                AppTextField(interval, { interval = it }, "医生方案中的给药间隔 τ（小时）", keyboardType = KeyboardType.Decimal)
                FormulaBlock(
                    if (drug == Drug.CPA) {
                        "Md(mg) = (CL/F) × Css,target × τ / 1000"
                    } else {
                        "Md(mg) = (CL/F) × Css,target × τ / 1,000,000"
                    }
                )
                if (md != null) {
                    Surface(color = SoftPink, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("理论 Md = ${"%.3f".format(md)} mg", style = MaterialTheme.typography.headlineSmall, color = Danger)
                            Text("这不是推荐剂量，也未计入舌下证据缺口、吸收延迟、个体清除变化、依从性或安全性。不得据此调量。", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    NoticeBanner("未计算：请先由医生确定是否有必要讨论目标及间隔。", warning = true)
                }
            }

            FoldCard(
                title = "07 · T/PD：饱和的 HPG 抑制",
                caption = "CPA 在约 10 mg/日以上进入平台；驱动量是 7 日平均浓度，不是处方剂量",
                preview = "7 日平均 E2（Hill）+ 7 日平均 CPA 浓度（折算等效剂量）共同驱动 T",
                initiallyExpanded = false
            ) {
                Text("CPA 的抑睾作用主要来自孕激素负反馈抑制 HPG 轴，而不是简单的“血药浓度 ÷ AR IC50”。ENIGI 882 例显示：单用雌激素时 T≈5.5 nmol/L，而 CPA 10、25、50、100 mg/日的 T 都在 0.9–1.1 nmol/L，剂量之间没有可辨差异。")
                FormulaBlock(
                    "Ē2 = 7 日窗口平均 E2\n" +
                        "r = Ē2 × s / 45 pg/mL\n" +
                        "gE2 = r^1.5 / (1 + r^1.5)\n" +
                        "C̄CPA = 7 日窗口平均 CPA 浓度\n" +
                        "D̄CPA = 50 mg × C̄CPA / C̄(50 mg q24 稳态)\n" +
                        "gCPA = 1 − exp(−D̄CPA / 1.5 mg·d⁻¹)\n" +
                        "G = 1 − (1 − gE2)(1 − gCPA)\n" +
                        "Tpred = Tbase × [1 − Emax × G]"
                )
                Text("稳态下 C̄CPA 与日剂量成正比，所以 D̄CPA 就等于处方日剂量，12.5 / 50 / 100 mg 的曲线几乎重合，差异只出现在约 5 mg/日以下的低剂量段。区别在时间过程：停药或漏服后，驱动量随 CPA 的终末相逐日衰减（单次约 54 h、长期延长到 79 h，而不是在漏服当天归零），T 大约在 2–4 周内回到基线。E2̄ 直接取曲线幅度，不再另乘 F——峰值锚点本身已含两条途径的生物利用度差异。s 为个体 E2 敏感度，只有至少 4 个 T 点、跨 3 个日期且 E2 暴露有变化时才尝试拟合。高剂量 CPA 仍会升高泌乳素、降低 HDL-C 并增加脑膜瘤累积风险，这部分不在 T 曲线里。", color = Danger)
            }

            FoldCard(
                title = "公开来源与参数映射",
                caption = "点击可查看原文；本应用不引用第三方模拟器作为验证数据",
                tint = SoftBlue,
                preview = "共 ${sources.size} 条：EV 口服 / 舌下 E2 / CPA 药品资料 / ENIGI",
                initiallyExpanded = false
            ) {
                sources.forEach { source ->
                    Column {
                        TextButton(onClick = { uriHandler.openUri(source.url) }) { Text(source.title) }
                        Text(source.detail, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            NoticeBanner("v1.0 的合理定位是“透明的情景模拟与记录原型”。要成为临床工具，还需人群外部验证、游离浓度/多室模型、检测方法分层、伦理与医疗器械合规评估。", warning = true)
        }
    }
}

private data class EvidenceSource(val title: String, val detail: String, val url: String)

private val sources = listOf(
    EvidenceSource(
        "Zhang et al. · 2024 · PMC11246651",
        "1 mg EV 单次口服，绝经后中国女性；参考制剂空腹 E2 Cmax 20.47 pg/mL、Tmax 中位 10 h、t½ 14.48 h。用于口服 EV 曲线锚点，外推到其他人群存在限制。",
        "https://pmc.ncbi.nlm.nih.gov/articles/PMC11246651/"
    ),
    EvidenceSource(
        "Doll et al. · PMID 34781041",
        "10 名跨性别女性、1 mg 17β-E2 交叉研究：舌下 Cmax 144 pg/mL、Tmax 1 h，口服 35 pg/mL、Tmax 8 h；0–8 h AUC 比约 1.8。不是 EV 舌下直接试验。",
        "https://pubmed.ncbi.nlm.nih.gov/34781041/"
    ),
    EvidenceSource(
        "Pharmacokinetics of estradiol（汇总）",
        "舌下 E2 的终末半衰期为 8–18 h，而静脉推注仅 0.5–2 h；文献同时记录 1 mg 舌下 1 h 达峰约 450 pg/mL、3 h 已回落至约 85 pg/mL。说明舌下是「快速分布相 + 较慢终末相」的双相过程，不能压成单一 2 h 半衰期。",
        "https://en.wikipedia.org/wiki/Pharmacokinetics_of_estradiol"
    ),
    EvidenceSource(
        "Medsafe · Siterone（CPA）药品资料",
        "50 mg CPA 口服后约 140 ng/mL、3 h 达峰，24–120 h 段终末半衰期 43.9 ± 12.8 h，总清除率 3.5 ± 1.5 mL/min/kg，F≈88%；每日重复给药蓄积约 3 倍（谷值口径）。注意这是单次给药后的斜率——长期治疗期会进一步延长，见 Kuhnz 1993。",
        "https://www.medsafe.govt.nz/profs/datasheet/s/Siteronetab.pdf"
    ),
    EvidenceSource(
        "Mylan-Cyproterone · 生物等效性研究（2×50 mg）",
        "29 名健康男性：Cmax 242 ng/mL、AUC₀₋∞ 6 908 ng·h/mL、AUC₀₋₇₂ 4 244、终末 t½ 74.5 h。折 50 mg 得 AUC ≈ 3 450（受试者 CL ≈ 3.0 mL/min/kg），用于确认「AUC/Cmax ≈ 25 h 远小于单相 43.9 h 的 63 h」，是多相拆分的依据。",
        "https://s3-us-west-2.amazonaws.com/drugbank/cite_this/attachments/files/000/001/737/original/Monograph_for_cyproterone_acetate.pdf"
    ),
    EvidenceSource(
        "Kuhnz et al. · 1993 · Contraception（Diane-35，15 人，3 个周期）",
        "CPA 2 mg + EE 35 µg：单次后双相 t½ 0.8 ± 0.4 h 与 54.0 ± 26.0 h，CL 3.6 ± 0.9 mL/min/kg，Vz 986 ± 437 L；" +
            "连续 3 个周期后终末 t½ 延长到 78.6 ± 16.0 h、Vz 增至 1 304 ± 427 L、CL 降到 3.0 ± 0.4 mL/min/kg，" +
            "稳态约在第 16 天达到、蓄积约 2 倍。作者归因于分布进入深室与深室缓慢释放。本模型的深室相（90 h）就是对这一段的等效：单次斜率 53.7 h、稳态后斜率 78.7 h，两个窗口同时对上。",
        "https://pubmed.ncbi.nlm.nih.gov/8131397/"
    ),
    EvidenceSource(
        "Frolich et al. · 1980 · J Steroid Biochem",
        "长期服 CPA 的多毛女性：50 mg/日 血药浓度 199–228 ng/mL、100 mg/日 436–520；口服后最初 8 h 的平均消除半衰期为 3.3–4.2 h。199–228 是间隔采血范围，并非完整 24 h 的平均浓度，只用于与本模型稳态暴露作量级对照；3.3–4.2 h 是分室拟合的 α 相速率，本模型 3→8 h 割线为 9.5 h（更平），是遵守 Cmax 140 与深室蓄积的取舍结果。",
        "https://www.sciencedirect.com/science/article/pii/0022473180901429"
    ),
    EvidenceSource(
        "Gerber et al. · 2024 · PMC11416909",
        "回顾性研究提示 CPA 抑睾达标中位时间约 3 个月；同时讨论脑膜瘤等累积剂量相关风险。小样本回顾研究不能证明个体疗效，也不构成剂量建议。",
        "https://pmc.ncbi.nlm.nih.gov/articles/PMC11416909/"
    ),
    EvidenceSource(
        "ENIGI 研究 · 2021 · PMC8571811",
        "882 名跨性别女性：单用雌激素 T≈5.5 nmol/L；CPA 10/25/50/100 mg 的 T 分别 0.9/0.9/1.1/0.9 nmol/L，说明 10 mg 已接近最大抑睾效果，更高剂量不进一步降低 T。用于 T/PD 饱和平台参数。",
        "https://pmc.ncbi.nlm.nih.gov/articles/PMC8571811/"
    )
)
