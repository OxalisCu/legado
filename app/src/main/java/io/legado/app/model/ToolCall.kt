package io.legado.app.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.collections.mapValues
import splitties.init.appCtx

object ToolName {
    const val READ_ALOUD_START = "ReadAloudStart"
    const val READ_ALOUD_PAUSE = "ReadAloudPause"
    const val READ_ALOUD_RESUME = "ReadAloudResume"
    const val READ_ALOUD_STOP = "ReadAloudStop"
    const val READ_ALOUD_PREV_CHAPTER = "ReadAloudPrevChapter"
    const val READ_ALOUD_NEXT_CHAPTER = "ReadAloudNextChapter"
    const val READ_ALOUD_PREV_PARAGRAPH = "ReadAloudPrevParagraph"
    const val READ_ALOUD_NEXT_PARAGRAPH = "ReadAloudNextParagraph"
}

@Serializable
data class ToolItem(
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

object ToolCall {
    private val emptyParams = """{"type":"object","properties":{}}"""
    val toolDefs = listOf(
        mapOf(
            "name" to ToolName.READ_ALOUD_START,
            "description" to "书籍开始朗读",
            "parameters" to emptyParams
        ),
        mapOf(
            "name" to ToolName.READ_ALOUD_PAUSE,
            "description" to "书籍暂停朗读",
            "parameters" to emptyParams
        ),
        mapOf(
            "name" to ToolName.READ_ALOUD_RESUME,
            "description" to "书籍恢复朗读",
            "parameters" to emptyParams
        ),
        mapOf(
            "name" to ToolName.READ_ALOUD_STOP,
            "description" to "书籍停止朗读",
            "parameters" to emptyParams
        ),
        mapOf(
            "name" to ToolName.READ_ALOUD_PREV_CHAPTER,
            "description" to "书籍朗读上一章节",
            "parameters" to emptyParams
        ),
        mapOf(
            "name" to ToolName.READ_ALOUD_NEXT_CHAPTER,
            "description" to "书籍朗读下一章节",
            "parameters" to emptyParams
        ),
        mapOf(
            "name" to ToolName.READ_ALOUD_PREV_PARAGRAPH,
            "description" to "书籍朗读上一个段落",
            "parameters" to emptyParams
        ),
        mapOf(
            "name" to ToolName.READ_ALOUD_NEXT_PARAGRAPH,
            "description" to "书籍朗读下一个段落",
            "parameters" to emptyParams
        )
    )

    fun executeToolCall(toolCall: ToolItem) {
        when (toolCall.name) {
            ToolName.READ_ALOUD_START -> {
                ReadAloud.play(appCtx)
            }

            ToolName.READ_ALOUD_PAUSE -> {
                ReadAloud.pause(appCtx)
            }

            ToolName.READ_ALOUD_RESUME -> {
                ReadAloud.resume(appCtx)
            }

            ToolName.READ_ALOUD_STOP -> {
                ReadAloud.stop(appCtx)
            }

            ToolName.READ_ALOUD_PREV_PARAGRAPH -> {
                ReadAloud.prevParagraph(appCtx)
            }

            ToolName.READ_ALOUD_NEXT_PARAGRAPH -> {
                ReadAloud.nextParagraph(appCtx)
            }

            ToolName.READ_ALOUD_PREV_CHAPTER -> {
                // seems not working well
                ReadBook.moveToPrevChapter(true, false)
            }

            ToolName.READ_ALOUD_NEXT_CHAPTER -> {
                ReadBook.moveToNextChapter(true)
            }

            else -> {
                // unknown tool
            }
        }
    }
}