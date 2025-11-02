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
import kotlinx.serialization.json.Json


@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object LLMAssistant : CoroutineScope by MainScope() {
    var activityContext: Context? = null
        private set

    var serviceContext: Context? = null
        private set

    private val context: Context get() = activityContext ?: serviceContext ?: appCtx

    private var answer = ""

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
        ToolCall.toolDefs.forEachIndexed { index, tool ->
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

    fun parseToolCall(toolCall: String): ToolItem {
        val tool = Json.decodeFromString<ToolItem>(toolCall)
        return tool
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
            ToolCall.executeToolCall(tool)
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