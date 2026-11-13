package cn.hrtcurve.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AiScreen(state: AppState, viewModel: MainViewModel) {
    var baseUrl by remember(state.apiBaseUrl) { mutableStateOf(state.apiBaseUrl) }
    var model by remember(state.apiModel) { mutableStateOf(state.apiModel) }
    var showKey by remember { mutableStateOf(false) }
    var persistKey by remember { mutableStateOf(true) }
    var keyMessage by remember { mutableStateOf("") }
    var confirmSend by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppHeader("AI 给药复核", "整理记录、发现缺漏、准备就医讨论；不自动调量")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FoldCard(
                title = "复核边界",
                caption = "系统提示词已预设，不让模型把估算曲线当成实测",
                tint = SoftBlue,
                preview = "可输出：依从性复核、信息缺漏、需要问医生的问题；不输出任何剂量方案",
                initiallyExpanded = false
            ) {
                Text("可输出：依从性与采样时间复核、需要补充的信息、一般监测项目、需要向医生确认的问题。")
                Text("不输出：新的具体剂量、间隔、途径、目标浓度，或自行开始/停药/增减量方案。", color = Danger)
            }

            FoldCard(
                title = "OpenAI 兼容接口",
                caption = "base URL 需包含 /v1；最终请求路径为 /chat/completions",
                preview = "$model · ${if (viewModel.apiKeyStored) "Key 已加密保存在本机" else "Key 未保存"}",
                initiallyExpanded = true
            ) {
                AppTextField(baseUrl, { baseUrl = it }, "API Base URL")
                AppTextField(model, { model = it }, "模型名称")
                OutlinedTextField(
                    value = viewModel.apiKey,
                    onValueChange = viewModel::updateApiKey,
                    label = { Text("API Key（可保存在本机）") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = "显示或隐藏密钥")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = persistKey, onCheckedChange = { persistKey = it })
                    Text("保存在本机（AES-GCM 加密）", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "请求默认关闭思考模式：依次尝试 enable_thinking / thinking 关闭开关，再尝试 reasoning_effort / reasoning；" +
                        "若服务商不认识这些参数（HTTP 400）会自动降级并记住结果。推理型模型被关掉思考后通常更快，也更少出现空回复。",
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (viewModel.apiKeyStored && viewModel.storedApiKeyMask.isNotBlank()) {
                    Text(
                        "本机已保存：${viewModel.storedApiKeyMask}",
                        color = AccentBlue,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "Key 由 Android Keystore 生成不可导出密钥后加密，密文只写在应用私有目录；不进入日志、不参与云备份、不写入导出的记录 JSON。卸载应用即一并删除。未勾选时仅在本次会话内存中使用。",
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { keyMessage = viewModel.saveApiConfig(baseUrl, model, persistKey) },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存设置与 Key") }
                    OutlinedButton(
                        onClick = { keyMessage = viewModel.clearStoredApiKey() },
                        modifier = Modifier.weight(1f)
                    ) { Text("清除本机 Key") }
                }
                OutlinedButton(
                    enabled = !viewModel.aiTesting,
                    onClick = { viewModel.testAiConnection(baseUrl, model) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (viewModel.aiTesting) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                    }
                    Text(if (viewModel.aiTesting) "正在测试…" else "测试连接（只发一句固定短语）")
                }
                val testText = viewModel.aiTestResult
                if (testText.isNotBlank()) {
                    val failed = testText.startsWith("连接失败") || testText.contains("没有返回正文")
                    NoticeBanner(testText, warning = failed, success = !failed)
                }
                if (keyMessage.isNotBlank()) {
                    Text(keyMessage, color = AccentBlue, style = MaterialTheme.typography.bodyMedium)
                }
            }

            FoldCard(
                title = "生成复核",
                caption = "将使用 ${state.doses.size} 条给药记录与 ${state.labs.size} 条实测记录；全量发送，不只最近几条",
                tint = SoftPink,
                preview = "发送前会列出数据范围，并可先查看摘要全文",
                initiallyExpanded = true
            ) {
                NoticeBanner("发送前会再次列出数据范围。请勿在记录中加入姓名、身份证号、住址等直接身份信息。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !viewModel.aiLoading,
                        onClick = {
                            viewModel.updateApiConfig(baseUrl, model)
                            confirmSend = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        if (viewModel.aiLoading) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                        }
                        Text(if (viewModel.aiLoading) "正在复核…" else "确认数据并生成复核")
                    }
                    OutlinedButton(
                        onClick = { showPreview = !showPreview },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (showPreview) "隐藏摘要" else "查看摘要") }
                }
                if (showPreview) {
                    // 摘要含三条曲线计算，放到后台线程，避免展开时掉帧。
                    var payload by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(state.doses, state.labs, state.profile) {
                        payload = withContext(Dispatchers.Default) { AiPayload.build(state) }
                    }
                    NoticeBanner("以下就是实际发送的全文，覆盖全部历史；较早记录被合并成方案区间，不是缺失。")
                    val current = payload
                    if (current == null) {
                        Text("正在生成摘要…", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        SelectionContainer { Text(current, style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            "共 ${current.length} 字符 · ${state.doses.size} 条给药 · ${state.labs.size} 条实测",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (viewModel.aiError.isNotBlank()) NoticeBanner(viewModel.aiError, warning = true)
            }

            if (viewModel.aiReport.isNotBlank()) {
                SoftCard {
                    Row {
                        SectionTitle("复核结果", "模型文本仍需人工核实")
                        TextButton(onClick = viewModel::clearAiReport) { Text("清空") }
                    }
                    SelectionContainer { Text(viewModel.aiReport, style = MaterialTheme.typography.bodyLarge) }
                    NoticeBanner("此结果由外部模型生成，不是处方。任何药物变化都应由医疗专业人员评估。", warning = true)
                }
            }
        }
    }

    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("确认发送健康记录？") },
            text = {
                Text("本次将向你配置的接口服务商发送：体重、HRT 起点（按最早给药记录自动回推）、可选基线 T、全部给药记录（较早日期按相同方案合并成区间）、最近 21 天逐条记录、全部化验点，以及个体拟合层级与训练内残差。\n\n发送内容不含姓名等身份信息；可先点「查看摘要」核对全文。API Key 从本机加密存储读取（或本次输入），只用于 Authorization 请求头，不会随记录一起发送。请先确认该服务商的隐私政策与数据处理地区。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSend = false
                    viewModel.generateAiReview()
                }) { Text("同意并发送") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("取消") } }
        )
    }
}
