package io.legado.app.ui.assistant

import android.app.Application
import io.legado.app.base.BaseViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import android.content.Intent

class VoiceAssistantViewModel(application: Application) : BaseViewModel(application) {
    private val _messages = MutableLiveData<List<ChatMessage>>()
    val messages: LiveData<List<ChatMessage>> get() = _messages

    // load history from database
    fun initData(intent: Intent) {

    }

    fun addNewMessage(message: ChatMessage) {
        _messages.value = (_messages.value ?: emptyList()) + message
    }

    fun editLastMessage(message: ChatMessage) {
        _messages.value?.let { currentMessages ->
            if (currentMessages.isNotEmpty()) {
                val updatedMessages = currentMessages.dropLast(1) + message
                _messages.value = updatedMessages
            }
        }
    }

    fun getLastMessage(): ChatMessage? {
        return _messages.value?.lastOrNull()
    }
}