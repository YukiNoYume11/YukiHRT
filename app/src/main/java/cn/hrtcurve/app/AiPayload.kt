package cn.hrtcurve.app

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 把本机记录压成一段模型可读的纯文本。
 *
 * 之所以不用「只发最近 30 条」：那样模型看不到更早的用药方案，会回复“无法读取较早数据”。
 * 这里把**全部历史**按天合并成「方案区间」——连续多天方案相同就只留一行，
 * 因此一年的每日双药记录同样只占 1–2 行；不规律用药时退化为按月汇总。
 * 逐条明细只保留最近若干天，用于看依从性；化验点数量少，全部保留。
 */
object AiPayload {
    private const val MAX_TIMELINE_LINES = 160
    private const val RECENT_DAYS = 21L
    private const val MAX_LABS = 60
    private const val MAX_CHARS = 14_000

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 允许无参调用写空行；普通函数类型不能带默认参数，所以包一层。 */
    private class Writer(private val sink: (String) -> Unit) {
        fun line(text: String = "") = sink(text)
        operator fun invoke(text: String = "") = sink(text)
    }

    private data class DayEntry(
        val drug: Drug,
        val route: Route,
        val doseMg: Double,
        val minuteOfDay: Int
    )

    private data class RegimenPeriod(
        val start: LocalDate,
        val end: LocalDate,
        val entries: List<DayEntry>
    ) {
        val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
    }

    fun build(state: AppState, nowMillis: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val doses = state.doses.sortedBy { it.takenAtMillis }
        val labs = state.labs.sortedBy { it.sampledAtMillis }
        val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        fun clockOf(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(TIME)

        val startDate = PkEngine.effectiveHrtStartDate(doses)
        val hrtDays = PkEngine.hrtDays(doses, nowMillis)

        val out = StringBuilder()
        val line = Writer { text -> out.append(text).append('\n') }

        line("【数据范围】")
        line("体重 ${num(state.profile.weightKg)} kg；HRT 起点 ${startDate?.format(DATE) ?: "无给药记录"}（已 $hrtDays 天）")
        if (doses.isEmpty()) {
            line("给药记录：0 条")
        } else {
            line(
                "给药记录 ${doses.size} 条：${dateOf(doses.first().takenAtMillis).format(DATE)} ${clockOf(doses.first().takenAtMillis)}" +
                    " → ${dateOf(doses.last().takenAtMillis).format(DATE)} ${clockOf(doses.last().takenAtMillis)}（全量，未截断）"
            )
        }
        if (labs.isEmpty()) {
            line("实测记录：0 条")
        } else {
            line(
                "实测记录 ${labs.size} 条：${dateOf(labs.first().sampledAtMillis).format(DATE)}" +
                    " → ${dateOf(labs.last().sampledAtMillis).format(DATE)}（全量，未截断）"
            )
        }
        line("未治疗基线 T：${state.profile.baselineTestosteroneNgDl?.let { "${num(it)} ng/dL（本人填写）" } ?: "未提供"}")
        line("说明：下方「用药方案时间线」把方案相同的连续日期合并为区间，覆盖第一条到最后一条记录的全部历史；区间合并不等于数据缺失。")
        line()

        if (doses.isNotEmpty()) {
            appendTimeline(doses, zone, line)
            appendTotals(doses, line)
            appendRecent(doses, nowDate, zone, line)
        }

        appendLabs(labs, zone, line)
        appendModelOutputs(state, nowMillis, line)

        val text = out.toString().trimEnd()
        return if (text.length <= MAX_CHARS) text else {
            // 极端不规律记录才会触顶：按行截断，并明确标注，避免模型误以为记录只到某天。
            text.take(MAX_CHARS).substringBeforeLast('\n') +
                "\n（摘要因长度上限在此截断，后面还有记录未展示；不要据此判断用药已停止。）"
        }
    }

    private fun appendTimeline(
        doses: List<DoseEvent>,
        zone: ZoneId,
        line: Writer
    ) {
        val perDay = doses.groupBy {
            Instant.ofEpochMilli(it.takenAtMillis).atZone(zone).toLocalDate()
        }
        val periods = mutableListOf<RegimenPeriod>()
        var start: LocalDate? = null
        var end: LocalDate? = null
        var signature: List<DayEntry> = emptyList()
        for (date in perDay.keys.sorted()) {
            val entries = perDay.getValue(date).map { dose ->
                val local = Instant.ofEpochMilli(dose.takenAtMillis).atZone(zone)
                DayEntry(dose.drug, dose.route, dose.doseMg, local.hour * 60 + local.minute)
            }.sortedWith(compareBy({ it.drug.ordinal }, { it.route.ordinal }, { it.doseMg }, { it.minuteOfDay }))
            if (signature == entries && end != null && end.plusDays(1) == date) {
                end = date
            } else {
                if (end != null && start != null) periods += RegimenPeriod(start, end, signature)
                start = date
                end = date
                signature = entries
            }
        }
        if (end != null && start != null) periods += RegimenPeriod(start, end, signature)

        line("【用药方案时间线（全量）】")
        if (periods.size <= MAX_TIMELINE_LINES) {
            periods.forEach { period -> line(renderPeriod(period)) }
        } else {
            line("记录不规律（${periods.size} 个不同方案区间），改为按月汇总；个体差异请看下方最近逐条记录。")
            val byMonth = doses.groupBy {
                YearMonth.from(Instant.ofEpochMilli(it.takenAtMillis).atZone(zone).toLocalDate())
            }
            byMonth.entries.sortedBy { it.key }.forEach { (month, monthDoses) ->
                val detail = monthDoses.groupBy { Triple(it.drug, it.route, it.doseMg) }
                    .map { (key, list) ->
                        "${key.first.shortLabel} ${key.second.label} ${num(key.third)} mg × ${list.size} 次"
                    }.joinToString("；")
                line("${month}：$detail")
            }
        }
        line()
    }

    private fun renderPeriod(period: RegimenPeriod): String {
        val header = if (period.days == 1) {
            period.start.format(DATE)
        } else {
            "${period.start.format(DATE)} ~ ${period.end.format(DATE)}（${period.days} 天）"
        }
        val doseText = period.entries.groupBy { Triple(it.drug, it.route, it.doseMg) }
            .map { (key, list) ->
                val times = list.map { minute -> minuteToClock(minute.minuteOfDay) }.distinct().joinToString("、")
                "${key.first.shortLabel} ${key.second.label} ${num(key.third)} mg @$times"
            }
            .joinToString("；")
        val perDay = if (period.days > 1) "，每天 ${period.entries.size} 次" else ""
        return "$header$perDay：$doseText"
    }

    private fun appendTotals(
        doses: List<DoseEvent>,
        line: Writer
    ) {
        line("【累计用量】")
        doses.groupBy { Triple(it.drug, it.route, it.doseMg) }
            .entries.sortedBy { it.key.first.ordinal }
            .forEach { (key, list) ->
                val total = list.sumOf { it.doseMg }
                // 每种方案用自己的首末记录算平均，避免“只吃了 4 个月的药除以两年跨度”这种失真。
                val first = list.minOf { it.takenAtMillis }
                val last = list.maxOf { it.takenAtMillis }
                val spanDays = ChronoUnit.DAYS.between(dateOf(first), dateOf(last)).coerceAtLeast(0) + 1
                val perDay = total / spanDays
                line(
                    "${key.first.shortLabel} ${key.second.label} ${num(key.third)} mg：" +
                        "${list.size} 次，累计 ${num(total)} mg，该方案跨度 $spanDays 天，平均 ${"%.2f".format(perDay)} mg/日"
                )
            }
        line()
    }

    private fun appendRecent(
        doses: List<DoseEvent>,
        nowDate: LocalDate,
        zone: ZoneId,
        line: Writer
    ) {
        val from = nowDate.minusDays(RECENT_DAYS - 1)
        val recent = doses.filter {
            !Instant.ofEpochMilli(it.takenAtMillis).atZone(zone).toLocalDate().isBefore(from)
        }
        line("【最近 $RECENT_DAYS 天逐条记录（${recent.size} 条，升序）】")
        if (recent.isEmpty()) {
            val last = doses.maxOf { it.takenAtMillis }
            line("最近 $RECENT_DAYS 天没有记录；最后一条为 ${Instant.ofEpochMilli(last).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}。")
        } else {
            recent.forEach { dose ->
                val local = Instant.ofEpochMilli(dose.takenAtMillis).atZone(zone)
                line("${local.format(DATE)} ${local.format(TIME)} ${dose.drug.shortLabel} ${dose.route.label} ${num(dose.doseMg)} mg")
            }
        }
        line()
    }

    private fun appendLabs(
        labs: List<LabSample>,
        zone: ZoneId,
        line: Writer
    ) {
        line("【实测记录（${labs.size} 条，升序）】")
        if (labs.isEmpty()) {
            line("暂无化验点。")
            line()
            return
        }
        val shown = if (labs.size <= MAX_LABS) {
            labs
        } else {
            labs.take(10) + labs.takeLast(MAX_LABS - 10)
        }
        var gapNoted = false
        shown.forEachIndexed { index, sample ->
            if (labs.size > MAX_LABS && !gapNoted && index == 10) {
                gapNoted = true
                line("……中间 ${labs.size - MAX_LABS} 条较早的化验点已省略，首尾保留……")
            }
            val local = Instant.ofEpochMilli(sample.sampledAtMillis).atZone(zone)
            val drug = sample.drug.shortLabel
            line(
                "${local.format(DATE)} ${local.format(TIME)} ${sample.analyte.label} ${num(sample.value)} ${sample.unit}" +
                    "（关联 $drug ${sample.route.label}，距给药 ${"%.1f".format(sample.elapsedHours)} h）"
            )
        }
        line()
    }

    private fun appendModelOutputs(state: AppState, nowMillis: Long, line: Writer) {
        line("【模型当前输出（估算，非实测）】")
        val fits = Drug.entries.flatMap { drug ->
            PkEngine.individualFits(drug, state.labs, state.doses, state.profile.weightKg).values.toList()
        }.filter { it.sampleCount > 0 }
        if (fits.isEmpty()) {
            line("个体 PK：当前没有可用实测点，曲线为群体先验。")
        } else {
            fits.forEach { fit ->
                line(
                    "个体 PK ${fit.drug.shortLabel} ${fit.route.label}：${fit.level.label}，${fit.sampleCount} 点，" +
                        "暴露 ×${"%.2f".format(fit.exposureFactor)}，消除 ×${"%.2f".format(fit.eliminationFactor)}，" +
                        "吸收 ×${"%.2f".format(fit.absorptionFactor)}，训练内对数 RMSE ${"%.2f".format(fit.logRmse)}"
                )
            }
        }
        val pd = PkEngine.testosteroneFit(state.labs, state.doses, state.profile)
        line(
            if (pd.sampleCount > 0) {
                "T PD：${pd.level.label}，${pd.sampleCount} 点，Emax ${"%.2f".format(pd.emax)}，" +
                    "基线 ${pd.baselineNgDl?.let { "${num(it)} ng/dL${if (pd.baselineFitted) "（由实测点联估，非真实未治疗值）" else "（本人填写）"}" } ?: "未确定"}"
            } else {
                "T PD：尚未拟合（需要至少一个治疗中 T 化验点）。"
            }
        )
        listOf(Analyte.E2, Analyte.CPA, Analyte.TESTOSTERONE).forEach { analyte ->
            val series = PkEngine.curve(
                analyte, state.doses, state.labs, state.profile, state.calibration, 48, nowMillis
            ) ?: return@forEach
            val nowPoint = series.points.minByOrNull { abs(it.hours) } ?: return@forEach
            // 单看“此刻”可能是谷值（舌下 E2 停药 12 h 后已回落到峰值的十分之一左右），同时给出窗口内峰值避免误读为“完全无雌激素”。
            val peak = series.points.maxOf { it.value }
            line(
                "当前估算 ${analyte.label}：${conc(nowPoint.value)} ${series.unit}" +
                    "（48 h 内峰值 ${conc(peak)}，${if (series.quantitative) "模型估算" else "情景估算"}）"
            )
        }
        line("以上全部为模型估算，不是化验结果，也不是目标值。")
    }

    private fun dateOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun minuteToClock(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** 浓度数量级差异大（pg/mL 到 ng/mL），按位数给有效数字，避免出现“0”或“76.21354”。 */
    private fun conc(value: Double): String = when {
        value < 0.05 -> "≈0"
        value < 10.0 -> "%.2f".format(value)
        value < 100.0 -> "%.1f".format(value)
        else -> "%.0f".format(value)
    }

    private fun num(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')
}
