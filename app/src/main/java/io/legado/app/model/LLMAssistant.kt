package io.legado.app.model

import android.content.Context
import io.legado.app.constant.IntentAction
import io.legado.app.service.LLMAssistantService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import splitties.init.appCtx
import io.legado.app.utils.startService

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

    fun startChat(msg: String) {
        context.startService<LLMAssistantService> {
            action = IntentAction.startLLMAssistant
            putExtra("message", msg)
        }
    }

    fun onLLMResult(result: String, status: Int) {
        when (status) {
            0 -> answer = result
            else -> answer += result
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