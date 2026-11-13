package cn.hrtcurve.app

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

@Composable
fun TransFlag(modifier: Modifier = Modifier) {
    val colors = listOf(FlagBlue, FlagPink, Color.White, FlagPink, FlagBlue)
    Row(modifier.clip(RoundedCornerShape(8.dp))) {
        colors.forEach { color ->
            Box(Modifier.weight(1f).fillMaxHeight().background(color))
        }
    }
}

@Composable
fun AppHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        TransFlag(Modifier.width(84.dp).height(7.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = tint),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SectionTitle(title: String, caption: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (caption != null) Text(caption, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 可折叠卡片：点标题行展开 / 收起。
 *
 * 这一页本来要滚三四屏，把「每次都要用」的区块留在展开状态、把偶尔才改一次的区块折起来，
 * 首屏就能看到曲线与当前参数。收起时用 [preview] 补一句摘要，避免折完什么信息都看不到。
 * 展开状态用 rememberSaveable 保存，横竖屏切换或从别的页面回来不会自动弹开。
 */
@Composable
fun FoldCard(
    title: String,
    caption: String? = null,
    tint: Color = Color.White,
    preview: String? = null,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = tint),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (caption != null) Text(caption, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    if (!expanded && preview != null) {
                        Text(preview, color = AccentBlue, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起「$title」" else "展开「$title」",
                    tint = MutedInk
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(tween(180)),
                exit = shrinkVertically() + fadeOut(tween(120))
            ) {
                Column(
                    Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    supporting: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun <T> ChoiceChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
fun NoticeBanner(text: String, warning: Boolean = false, success: Boolean = false) {
    Surface(
        color = when {
            warning -> Color(0xFFFFF1F2)
            success -> Color(0xFFE9F7EF)
            else -> SoftBlue
        },
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                contentDescription = null,
                tint = when {
                    warning -> Danger
                    success -> Color(0xFF2E7D5B)
                    else -> AccentBlue
                },
                modifier = Modifier.size(20.dp)
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    warning -> Danger
                    success -> Color(0xFF1F5C43)
                    else -> Ink
                }
            )
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = SoftLavender, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MutedInk)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun FormulaBlock(text: String) {
    Surface(color = SoftBlue, shape = RoundedCornerShape(16.dp)) {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = Ink
        )
    }
}

@Composable
fun DoseRow(dose: DoseEvent, onDelete: () -> Unit) {
    Surface(color = SoftPink, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${dose.drug.shortLabel} · ${dose.route.label} · ${trimNumber(dose.doseMg)} mg", fontWeight = FontWeight.SemiBold)
                Text(MainViewModel.formatDateTime(dose.takenAtMillis), color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除给药记录", tint = Danger)
            }
        }
    }
}

@Composable
fun CurveChart(series: CurveSeries, selectedHour: Double) {
    val animation = remember(series) { Animatable(0f) }
    LaunchedEffect(series) { animation.animateTo(1f, tween(900)) }
    val lineColor = when (series.analyte) {
        Analyte.E2 -> AccentPink
        Analyte.CPA -> AccentBlue
        Analyte.TESTOSTERONE -> Color(0xFF8B78B5)
    }
    val labelPaint = remember {
        Paint().apply {
            color = MutedInk.toArgb()
            textSize = 30f
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
    }
    val xPaint = remember {
        Paint().apply {
            color = MutedInk.toArgb()
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val unitPaint = remember {
        Paint().apply {
            color = AccentBlue.toArgb()
            textSize = 30f
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    Canvas(Modifier.fillMaxWidth().height(270.dp)) {
        val left = 60.dp.toPx()
        val right = 12.dp.toPx()
        val top = 30.dp.toPx()
        val bottom = 38.dp.toPx()
        val width = size.width - left - right
        val height = size.height - top - bottom
        val maxY = max(
            1.0,
            (series.points.maxOfOrNull { it.high } ?: 1.0).let { curveMax ->
                max(curveMax, series.labPoints.maxOfOrNull { it.value } ?: 0.0) * 1.08
            }
        )
        val minHour = series.points.minOfOrNull { it.hours } ?: 0.0
        val maxHour = series.points.maxOfOrNull { it.hours } ?: 1.0
        val hourSpan = (maxHour - minHour).coerceAtLeast(1.0)
        fun x(hours: Double) = left + ((hours - minHour) / hourSpan * width).toFloat()
        fun y(value: Double) = top + height - (value.coerceIn(0.0, maxY) / maxY * height).toFloat()

        // 纵轴单位：标在轴顶端，随曲线单位实时变化
        drawContext.canvas.nativeCanvas.drawText(
            series.unit,
            left - 10.dp.toPx(),
            top - 10.dp.toPx(),
            unitPaint
        )
        repeat(5) { index ->
            val ratio = index / 4f
            val py = top + height * ratio
            drawLine(Color(0xFFEDE4EA), Offset(left, py), Offset(left + width, py), strokeWidth = 1.dp.toPx())
            val value = maxY * (1f - ratio)
            drawContext.canvas.nativeCanvas.drawText(formatAxis(value, maxY), left - 10.dp.toPx(), py + 4.dp.toPx(), labelPaint)
        }
        repeat(5) { index ->
            val hours = minHour + hourSpan * index / 4.0
            drawContext.canvas.nativeCanvas.drawText(formatHourAxis(hours), x(hours), top + height + 27.dp.toPx(), xPaint)
        }
        drawLine(
            Color(0xFFAAB3C8),
            Offset(x(0.0), top + 2.dp.toPx()),
            Offset(x(0.0), top + height),
            strokeWidth = 1.dp.toPx()
        )

        val band = Path()
        series.points.forEachIndexed { index, point ->
            if (index == 0) band.moveTo(x(point.hours), y(point.low)) else band.lineTo(x(point.hours), y(point.low))
        }
        series.points.asReversed().forEach { point -> band.lineTo(x(point.hours), y(point.high)) }
        band.close()
        val line = Path()
        series.points.forEachIndexed { index, point ->
            if (index == 0) line.moveTo(x(point.hours), y(point.value)) else line.lineTo(x(point.hours), y(point.value))
        }
        clipRect(right = left + width * animation.value) {
            drawPath(band, lineColor.copy(alpha = 0.12f))
            drawPath(line, lineColor, style = Stroke(width = 3.dp.toPx()))
        }
        series.labPoints.forEach { point ->
            drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(x(point.hours), y(point.value)))
            drawCircle(Danger, radius = 4.dp.toPx(), center = Offset(x(point.hours), y(point.value)))
        }
        val cursorX = x(selectedHour.coerceIn(minHour, maxHour))
        drawLine(lineColor.copy(alpha = 0.5f), Offset(cursorX, top), Offset(cursorX, top + height), strokeWidth = 1.dp.toPx())
        val nearest = series.points.minByOrNull { kotlin.math.abs(it.hours - selectedHour) }
        if (nearest != null) drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset(cursorX, y(nearest.value)))
    }
    Text(
        "横轴：相对当前时间（左为历史，右为未来） · 竖灰线：现在 · 红点：实测值",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MutedInk,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
fun DisclaimerDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("先确认使用边界") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TransFlag(Modifier.width(72.dp).height(6.dp))
                Text("本应用是药代概念可视化与记录工具，不是医疗器械，不提供诊断或处方。")
                Text("体重无法可靠决定个体清除率或分布容积。舌下戊酸雌二醇尤其缺少直接人体参数；曲线和阴影不能用来判断疗效、安全性或是否“达标”。")
                Text("理论 Md 只有在输入医生已确认的讨论目标后才计算，它不是推荐剂量。请勿依据本应用开始、停用或调整任何药物。")
                Text("CPA 可能涉及脑膜瘤、肝毒性、情绪及泌乳素异常等风险。若出现胸痛、呼吸困难、黄疸、突发严重头痛、视听异常、抽搐、肢体无力或自伤风险，请立即就医。")
                Text("健康记录仅存于本机应用私有目录。AI 复核会将你确认的记录发送给自行配置的服务商；API Key 可选保存在本机加密存储（Android Keystore），也可仅用于本次会话。")
                Text("继续即表示你理解这些限制，并会由具备相关经验的医疗专业人员结合实测结果进行临床决策。", fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("我理解，仅作估算与记录") } },
        dismissButton = null,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    )
}

fun trimNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')
private fun formatAxis(value: Double, maxY: Double): String = when {
    maxY >= 100.0 -> "%.0f".format(value)
    maxY >= 10.0 -> "%.1f".format(value)
    maxY >= 1.0 -> "%.2f".format(value)
    else -> "%.3f".format(value)
}

private fun formatHourAxis(value: Double): String = when {
    value > -0.05 && value < 0.05 -> "现在"
    value >= 10.0 -> "+${value.toInt()}h"
    value <= -10.0 -> "${value.toInt()}h"
    else -> "${"%.0f".format(value)}h"
}

// ---------------------------------------------------------------------------
// 日期 / 时刻输入：一律点选
// 手打 yyyy-MM-dd HH:mm 在手机上是错误率最高的一步，而给药时间差一小时
// 就足以让整条曲线平移。这里把「快捷项 + 日历 + 整点/5 分钟档」摊开点。
// ---------------------------------------------------------------------------

private val PickerBorder = Color(0xFFE7D9E1)
private val PickerDateView: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 EEE", Locale.CHINA)
private val PickerClockView: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 只选日期（不含时刻）。 */
@Composable
fun DatePickerField(
    label: String,
    value: LocalDate,
    modifier: Modifier = Modifier,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
    supporting: String? = null,
    onChange: (LocalDate) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    PickerAnchor(label, value.format(PickerDateView), supporting, modifier) { open = true }
    if (open) {
        PickerDialog(
            title = label,
            initialDate = value,
            initialTime = null,
            minDate = minDate,
            maxDate = maxDate,
            onDismiss = { open = false },
            onConfirm = { date, _ ->
                open = false
                if (date != null) onChange(date)
            }
        )
    }
}

/** 只选时刻（24 小时制）。 */
@Composable
fun ClockField(
    label: String,
    value: LocalTime,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    onChange: (LocalTime) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    PickerAnchor(label, value.format(PickerClockView), supporting, modifier) { open = true }
    if (open) {
        PickerDialog(
            title = label,
            initialDate = null,
            initialTime = value,
            minDate = null,
            maxDate = null,
            onDismiss = { open = false },
            onConfirm = { _, time ->
                open = false
                if (time != null) onChange(time)
            }
        )
    }
}

/** 日期 + 时刻。 */
@Composable
fun DateTimeField(
    label: String,
    value: LocalDateTime,
    modifier: Modifier = Modifier,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
    supporting: String? = null,
    onChange: (LocalDateTime) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    PickerAnchor(
        label = label,
        valueText = value.format(PickerDateView) + "  " + value.format(PickerClockView),
        supporting = supporting,
        modifier = modifier
    ) { open = true }
    if (open) {
        PickerDialog(
            title = label,
            initialDate = value.toLocalDate(),
            initialTime = value.toLocalTime(),
            minDate = minDate,
            maxDate = maxDate,
            onDismiss = { open = false },
            onConfirm = { date, time ->
                open = false
                if (date != null && time != null) onChange(date.atTime(time))
            }
        )
    }
}

@Composable
private fun PickerAnchor(
    label: String,
    valueText: String,
    supporting: String?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MutedInk)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, PickerBorder)
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(valueText, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text("选择", style = MaterialTheme.typography.labelLarge, color = AccentPink)
            }
        }
        if (supporting != null) {
            Text(supporting, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerDialog(
    title: String,
    initialDate: LocalDate?,
    initialTime: LocalTime?,
    minDate: LocalDate?,
    maxDate: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate?, LocalTime?) -> Unit
) {
    var date by remember { mutableStateOf(initialDate) }
    var hour by remember { mutableIntStateOf(initialTime?.hour ?: 8) }
    var minute by remember { mutableIntStateOf(initialTime?.minute ?: 0) }
    var showCalendar by remember { mutableStateOf(false) }
    val withTime = initialTime != null
    val today = LocalDate.now()
    val nowMinutes = LocalDateTime.now().withSecond(0).withNano(0)

    fun clamp(target: LocalDate): LocalDate = when {
        maxDate != null && target.isAfter(maxDate) -> maxDate
        minDate != null && target.isBefore(minDate) -> minDate
        else -> target
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择$title", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (withTime) {
                    Text("快捷时刻", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0L to "此刻", 1L to "1 小时前", 3L to "3 小时前").forEach { (offset, text) ->
                            DialogChip(text, active = false) {
                                val target = nowMinutes.minusHours(offset)
                                if (date != null) date = clamp(target.toLocalDate())
                                hour = target.hour
                                minute = target.minute
                            }
                        }
                    }
                }

                if (date != null) {
                    HorizontalDivider(color = PickerBorder)
                    Text("快捷日期", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0L to "今天", 1L to "昨天", 3L to "3 天前").forEach { (days, text) ->
                            val target = clamp(today.minusDays(days))
                            DialogChip(text, active = date == target) {
                                date = target
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7L to "7 天前", 14L to "14 天前", 30L to "30 天前").forEach { (days, text) ->
                            val target = clamp(today.minusDays(days))
                            DialogChip(text, active = date == target) { date = target }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("日期", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                            Text(
                                date?.format(PickerDateView).orEmpty(),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        TextButton(onClick = { showCalendar = true }) { Text("打开日历") }
                    }
                }

                if (withTime) {
                    HorizontalDivider(color = PickerBorder)
                    Text("小时（24 小时制）", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (0..23).forEach { value ->
                            DialogChip("%02d".format(value), hour == value, weighted = false) { hour = value }
                        }
                    }
                    Text("分钟（5 分钟一档）", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (0..55 step 5).forEach { value ->
                            DialogChip("%02d".format(value), minute == value, weighted = false) { minute = value }
                        }
                    }
                }

                val preview = buildString {
                    date?.let { append(it.format(PickerDateView)) }
                    if (date != null && withTime) append("  ")
                    if (withTime) append("%02d:%02d".format(hour, minute))
                }
                Text("将记录为 $preview", color = AccentPink, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(date, if (withTime) LocalTime.of(hour, minute) else null)
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (showCalendar && date != null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date!!.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val target = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    if (minDate != null && target.isBefore(minDate)) return false
                    if (maxDate != null && target.isAfter(maxDate)) return false
                    return true
                }

                override fun isSelectableYear(year: Int): Boolean {
                    if (minDate != null && year < minDate.year) return false
                    if (maxDate != null && year > maxDate.year) return false
                    return true
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showCalendar = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCalendar = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun RowScope.DialogChip(
    label: String,
    active: Boolean,
    weighted: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = if (weighted) Modifier.weight(1f) else Modifier,
        onClick = onClick,
        color = if (active) AccentPink else Color.White,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (active) AccentPink else PickerBorder)
    ) {
        Box(
            Modifier.padding(horizontal = if (weighted) 0.dp else 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) Color.White else Ink,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}
