package cn.hrtcurve.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiClient {
    /**
     * 各服务商关闭“思考/推理”的字段并不统一：
     * - DeepSeek、Kimi、GLM、通义千问（百炼兼容模式）等：`enable_thinking: false` / `thinking: {type: disabled}`
     * - OpenAI o 系列、GPT-5、OpenRouter 等：`reasoning_effort` / `reasoning.enabled`
     * 严格网关（例如 OpenAI 官方）遇到不认识的字段会直接 400，所以按顺序降级重试，
     * 并记住这家服务商接受的写法，后续请求不再先撞一次 400。
     */
    private enum class ThinkingMode(val label: String) {
        PROVIDER_SWITCH("已请求关闭思考（enable_thinking / thinking）"),
        REASONING_EFFORT("已请求关闭思考（reasoning_effort / reasoning）"),
        UNCHANGED("未发送关思考参数（该服务商不接受，或模型本身不推理）")
    }

    private class HttpStatusException(val status: Int, message: String) : IllegalStateException(message)

    private val acceptedMode = HashMap<String, ThinkingMode>()

    /** 最近一次成功请求实际使用的关思考方式，供「测试连接」回显。 */
    var lastThinkingModeLabel: String = ""
        private set

    suspend fun generateReview(
        baseUrl: String,
        apiKey: String,
        model: String,
        recordsText: String
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先输入 API Key，或在设置中保存到本机" }
        require(model.isNotBlank()) { "请输入模型名称" }
        val body = JSONObject().apply {
            put("model", model.trim())
            // 即使已请求关闭思考，仍保留足够预算：部分模型会忽略关思考参数。
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SAFETY_SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "请复核以下 HRT 记录。曲线为未验证的模型估算，不是实测：\n\n$recordsText")
                })
            })
        }
        val raw = postWithThinkingOff(baseUrl, apiKey, model, body, readTimeoutMs = 90_000)
        extractContent(raw, model.trim())
    }

    /**
     * 只发一条固定短语，用来确认地址、Key、模型名是否可用，不发送任何健康记录。
     * 接口通但正文为空时会如实说明，便于区分「配置错」和「模型输出被截断」。
     */
    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先输入 API Key，或先保存到本机" }
        require(model.isNotBlank()) { "请输入模型名称" }
        val started = System.currentTimeMillis()
        val body = JSONObject().apply {
            put("model", model.trim())
            put("max_tokens", 32)
            put("stream", false)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "只回复 OK 两个字母，不要其他内容。")
            }))
        }
        val raw = postWithThinkingOff(baseUrl, apiKey, model, body, readTimeoutMs = 30_000)
        val elapsed = System.currentTimeMillis() - started
        val json = parseJson(raw)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException("接口有响应但没有 choices 字段，可能不是 OpenAI 兼容的 /chat/completions 接口。响应片段：${raw.take(180)}")
        val reply = textOf(choice.optJSONObject("message")?.opt("content"))
            ?: textOf(choice.opt("text"))
            ?: ""
        val finish = choice.optString("finish_reason").ifBlank { "未知" }
        val summary = if (reply.isBlank()) {
            "接口可用，但模型没有返回正文（finish_reason=$finish）。多为模型名不受支持、额度不足，或推理模型把 token 预算耗尽。"
        } else {
            "连接成功，模型回复「${reply.trim().take(24)}」。"
        }
        "$summary\n耗时 ${elapsed} ms · 模型 ${model.trim()} · finish_reason=$finish\n$lastThinkingModeLabel"
    }

    /**
     * 依次尝试「关思考」的不同写法；只有 400/422（参数不被认识）才降级，
     * 其它错误（401/404/429/5xx）立即抛出，避免把真正的配置错误掩盖成“参数问题”。
     */
    private fun postWithThinkingOff(
        baseUrl: String,
        apiKey: String,
        model: String,
        baseBody: JSONObject,
        readTimeoutMs: Int
    ): String {
        val endpoint = endpointOf(baseUrl)
        val cacheKey = "${baseUrl.trim()}|${model.trim()}"
        val order = buildList {
            acceptedMode[cacheKey]?.let { add(it) }
            ThinkingMode.entries.forEach { mode -> if (mode !in this) add(mode) }
        }
        var lastError: Exception? = null
        order.forEach { mode ->
            val body = JSONObject(baseBody.toString())
            when (mode) {
                ThinkingMode.PROVIDER_SWITCH -> {
                    body.put("enable_thinking", false)
                    body.put("thinking", JSONObject().put("type", "disabled"))
                }
                ThinkingMode.REASONING_EFFORT -> {
                    body.put("reasoning_effort", "minimal")
                    body.put("reasoning", JSONObject().put("enabled", false))
                }
                ThinkingMode.UNCHANGED -> Unit
            }
            try {
                val raw = postJson(endpoint, apiKey, body, readTimeoutMs)
                acceptedMode[cacheKey] = mode
                lastThinkingModeLabel = mode.label
                return raw
            } catch (error: HttpStatusException) {
                lastError = error
                if (error.status != 400 && error.status != 422) throw error
            }
        }
        throw lastError ?: IllegalStateException("请求失败")
    }

    private fun endpointOf(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.startsWith("https://")) { "请使用 HTTPS 接口地址" }
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    private fun postJson(
        endpoint: String,
        apiKey: String,
        body: JSONObject,
        readTimeoutMs: Int
    ): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            doOutput = true
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw HttpStatusException(status, "接口返回 HTTP $status${errorDetail(response)}")
            }
            if (response.isBlank()) {
                throw IllegalStateException(
                    "接口返回了空响应（HTTP $status）。请确认地址是 OpenAI 兼容的 /chat/completions，" +
                        "并且没有经过会丢弃响应体的代理。"
                )
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun errorDetail(raw: String): String {
        if (raw.isBlank()) return ""
        val message = runCatching {
            val json = JSONObject(raw)
            when {
                json.optJSONObject("error") != null -> json.getJSONObject("error").optString("message")
                json.has("message") -> json.optString("message")
                json.has("detail") -> json.optString("detail")
                else -> ""
            }
        }.getOrDefault("")
        val text = message.ifBlank { raw }
        return "：" + text.replace(Regex("\\s+"), " ").trim().take(240)
    }

    private fun parseJson(raw: String): JSONObject = runCatching { JSONObject(raw) }.getOrElse {
        throw IllegalStateException("接口返回的不是 JSON，可能被登录页或代理拦截。响应片段：${raw.take(180)}")
    }

    private fun extractContent(raw: String, model: String): String {
        val json = parseJson(raw)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            // 少数网关把结果直接放在顶层 message 里，能读就先用
            val direct = textOf(json.opt("message"))
            if (!direct.isNullOrBlank()) return direct
            throw IllegalStateException("响应里没有 choices 字段，可能不是 OpenAI 兼容接口。响应片段：${raw.take(200)}")
        }
        val choice = choices.getJSONObject(0)
        val message = choice.optJSONObject("message")
        val content = textOf(message?.opt("content")) ?: textOf(choice.opt("text"))
        if (!content.isNullOrBlank()) return content

        val finish = choice.optString("finish_reason").ifBlank { "未知" }
        val usage = json.optJSONObject("usage")
        val completionTokens = usage?.optInt("completion_tokens", -1) ?: -1
        val reasoning = textOf(message?.opt("reasoning_content")).orEmpty()
        val hint = when {
            finish == "length" ->
                "输出被 token 上限截断且没有留下正文。推理型模型会先把预算用于内部推理；本应用已尝试发送关闭思考的参数，若仍为空，请换用非推理模型。"
            finish == "content_filter" ->
                "内容被服务商的安全策略过滤，可换一个服务商或模型再试。"
            reasoning.isNotBlank() ->
                "模型只返回了推理过程（reasoning_content），没有最终答复；说明该服务商没有接受关思考参数，请换用非推理模型。"
            finish == "stop" ->
                "模型正常结束但没有输出正文，可能是该模型名实际指向了不支持的端点，或网关吞掉了内容。"
            else ->
                "HTTP 200 但正文为空：请核对模型名是否存在于该服务商、账户额度是否用尽，以及是否误填了中转网关地址。"
        }
        throw IllegalStateException(
            "模型返回空内容（model=$model，finish_reason=$finish" +
                (if (completionTokens >= 0) "，completion_tokens=$completionTokens" else "") +
                "）。$hint"
        )
    }

    private fun textOf(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("") { index ->
            val part = value.opt(index)
            if (part is JSONObject) part.optString("text") else part?.toString().orEmpty()
        }.ifBlank { null }
        else -> value.toString()
    }

    companion object {
        /** 兼顾推理模型与常见上下文限制；内容为空时会在错误里说明被截断。 */
        private const val MAX_OUTPUT_TOKENS = 4096

        const val SAFETY_SYSTEM_PROMPT = """
你是 HRT 用药记录复核助手，不是医生，也不能替代内分泌专科诊疗。
输入中的曲线和 Cl、Vd、Cp、Md、个体 PK/PD 拟合都未经过临床验证；个体拟合只使用当前记录，训练内残差不代表预测准确性，均不是实测结果。
严禁建议开始、停用、增加或减少任何药物；严禁给出新的具体剂量、给药间隔、给药途径、目标血药浓度或调量公式。
你可以：总结给药与化验记录；指出采样时间和记录缺失；提示需要医生核实的不确定性；给出一般性的复诊、规范采血和不良反应监测建议。
CPA 与脑膜瘤、肝毒性、情绪及泌乳素异常相关。发现风险时只建议尽快就医复核，不提供自行减量或停药方案。
若有胸痛、呼吸困难、晕厥、黄疸、严重或突发头痛、视觉/听觉异常、抽搐、明显肢体无力，或有自伤风险，应建议立即急诊或联系当地紧急服务。

输入格式说明（务必按此理解，不要误判为数据缺失）：
1. 「用药方案时间线」用「起始日 ~ 结束日（N 天）」的形式把方案相同的连续日期合并成一行，它覆盖从第一条到最后一条给药记录的全部历史，合并区间内的每一天都按该行方案给药。不要把区间压缩当成「只记录了一部分日期」。
2. 「最近 21 天逐条记录」只是方便核对依从性的明细，不是全部数据；早期记录在时间线里，而不是缺失。
3. 「累计用量」「实测记录」「模型当前输出」「个体拟合」分别给出总量、化验点、估算浓度和拟合层级。若某段注明「已省略」或「无记录」，才表示该处确实没有数据。
4. 只有确实标注为缺失或无记录时，才可以提示补充；不确定时先按现有摘要归纳，不要声称「无法读取较早数据」。

输出使用简体中文，分为「记录摘要」「需要尽快核实」「监测与就医讨论」「安全边界」四节。不要迎合，不要假装确定，不要重复输入中没有的数值。
直接给出最终答复，不要输出推理过程、思考步骤或自我提问。
"""
    }
}
