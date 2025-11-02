package io.legado.app.model

import android.content.Intent
import android.util.Log
import android.annotation.SuppressLint
import android.content.Context
import io.legado.app.constant.IntentAction
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.LLMAssistantService
import io.legado.app.service.TTSReadAloudService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import splitties.init.appCtx
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startService
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ToolCall(
    val name: String,
    @Serializable(with = ArgumentsAsObjectSerializer::class)
    val arguments: Map<String, String> = emptyMap()
)

object ArgumentsAsObjectSerializer : KSerializer<Map<String, String>> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ArgumentsAsObject")

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val input = decoder as? JsonDecoder ?: error("JSON only")
        val element = input.decodeJsonElement()

        return when (element) {
            is JsonObject -> element.mapValues { it.value.jsonPrimitive.content }
            is JsonNull -> emptyMap()
            is JsonPrimitive -> { // "" 或其他无效类型
                emptyMap()
            }

            else -> emptyMap()
        }
    }

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        val json = JsonObject(value.mapValues { JsonPrimitive(it.value) })
        encoder.encodeSerializableValue(JsonObject.serializer(), json)
    }
}

data class ToolActionConfig(
    val serviceClass: Class<*>? = null,
    val action: String,
    val configIntent: (Intent, Map<String, String>) -> Unit = { _, _ -> }
)

@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object LLMAssistant : CoroutineScope by MainScope() {
    var activityContext: Context? = null
        private set

    var serviceContext: Context? = null
        private set

    private val context: Context get() = activityContext ?: serviceContext ?: appCtx

    private var answer = ""

    private val toolActionMap = mapOf(
        "StartReadAloud" to ToolActionConfig(
            serviceClass = null, // 使用 ReadAloud 动态获取的服务类
            action = IntentAction.play,
            configIntent = { intent, args ->
                // 可以添加额外参数，例如：intent.putExtra("pageIndex", args["pageIndex"]?.toIntOrNull() ?: 0)
                intent.putExtra("play", true)
            }
        ),
        "StopReadAloud" to ToolActionConfig(
            serviceClass = null, // 使用 ReadAloud 动态获取的服务类
            action = IntentAction.stop,
            configIntent = { _, _ -> }
        )
    )

    val emptyParams = """{"type":"object","properties":{}}"""
    val toolDefs = listOf(
        mapOf(
            "name" to "StartReadAloud",
            "description" to "书籍朗读开始播放",
            "parameters" to emptyParams
        ),
        mapOf(
            "name" to "StopReadAloud",
            "description" to "书籍朗读停止播放",
            "parameters" to emptyParams
        )
    )

    fun register(context: Context) {
        activityContext = context
    }

    fun unregister(context: Context) {
        if (activityContext === context) {
            activityContext = null
        }
        coroutineContext.cancelChildren()
    }

    fun registerService(context: Context) {
        serviceContext = context
    }

    fun unregisterService(context: Context) {
        serviceContext = null
    }

    fun buildToolsJson(): String {
        val sb = StringBuilder("[")
        toolDefs.forEachIndexed { index, tool ->
            if (index > 0) sb.append(',')
            sb.append(
                """
                {
                    "name": "${tool["name"]}",
                    "description": "${tool["description"]}",
                    "parameters": ${tool["parameters"]}
                }
                """.trimIndent()
            )
        }
        sb.append("]")
        return sb.toString()
    }

    fun parseToolCall(toolCall: String): ToolCall {
        val tool = Json.decodeFromString<ToolCall>(toolCall)
        return tool
    }

    fun executeToolCall(tool: ToolCall) {
        val config = toolActionMap[tool.name]
        if (config == null) {
            Log.w("LLMAssistant", "Unknown tool: ${tool.name}")
            return
        }

        try {
            // 获取服务类：如果配置中指定了服务类则使用，否则动态获取
            val serviceClass = config.serviceClass ?: getServiceClassForTool(tool.name)

            if (serviceClass == null) {
                Log.e("LLMAssistant", "Cannot determine service class for tool: ${tool.name}")
                return
            }

            // 创建 Intent 并配置动作和参数
            val intent = Intent(context, serviceClass).apply {
                action = config.action
                config.configIntent(this, tool.arguments)
            }

            // 根据服务类型选择启动方式
            when {
                // 对于朗读服务（继承自 BaseReadAloudService），使用前台服务启动
                tool.name.contains("ReadAloud", ignoreCase = true) &&
                        BaseReadAloudService::class.java.isAssignableFrom(serviceClass) -> {
                    context.startForegroundServiceCompat(intent)
                }

                else -> {
                    // 普通服务启动
                    context.startService(intent)
                }
            }

            Log.d("LLMAssistant", "Executed tool: ${tool.name} with action: ${config.action}")
        } catch (e: Exception) {
            Log.e("LLMAssistant", "Error executing tool: ${tool.name}", e)
        }
    }


    private fun getServiceClassForTool(toolName: String): Class<*>? {
        return when {
            toolName.contains("ReadAloud", ignoreCase = true) -> {
                // 通过反射获取 ReadAloud 的 aloudClass 字段，这是当前最灵活的方式
                // 因为 ReadAloud 会根据配置动态选择 TTSReadAloudService 或 HttpReadAloudService
                try {
                    val field = ReadAloud::class.java.getDeclaredField("aloudClass")
                    field.isAccessible = true
                    field.get(null) as? Class<*>
                } catch (e: Exception) {
                    Log.w("LLMAssistant", "Failed to get ReadAloud service class, using default", e)
                    // 如果反射失败，使用默认的 TTS 服务
                    TTSReadAloudService::class.java
                }
            }
            // 可以在这里添加其他工具的服务类获取逻辑
            else -> null
        }
    }

    fun startChat(msg: String) {
        context.startService<LLMAssistantService> {
            action = IntentAction.startLLMAssistant
            putExtra("message", msg)
        }
    }

    fun onLLMResult(result: String, toolCall: String, status: Int) {
        if (toolCall.isEmpty()) {
            when (status) {
                0 -> answer = result
                else -> answer += result
            }
        } else {
            val tool = parseToolCall(toolCall)
            Log.d("LLMAssistant", "Tool Call: $tool")
            // 执行工具调用
            executeToolCall(tool)
            answer = "工具调用：${tool.name}，参数：${tool.arguments}"
        }
        activityContext?.let {
            if (it is Callback) {
                it.onLLMResult(answer, status)
            }
        }
    }

    interface Callback {
        fun onLLMResult(result: String, status: Int)
    }
}