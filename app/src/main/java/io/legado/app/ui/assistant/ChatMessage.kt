package io.legado.app.ui.assistant

data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val status: Int,
    val timestamp: Long = System.currentTimeMillis()
)
