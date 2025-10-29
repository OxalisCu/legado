package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import io.legado.app.constant.IntentAction

import io.legado.app.utils.startService
import io.legado.app.service.VoiceAssistantService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import splitties.init.appCtx

@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object VoiceAssistant : CoroutineScope by MainScope() {

    var activityContext: Context? = null
        private set

    var serviceContext: Context? = null
        private set

    private val context: Context get() = activityContext ?: serviceContext ?: appCtx

    var recording = false

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

    fun toggle(context: Context) {
        if (recording) {
            context.startService<VoiceAssistantService> {
                action = IntentAction.stopAssistant
            }
            recording = false
        } else {
            context.startService<VoiceAssistantService> {
                action = IntentAction.startAssistant
            }
            recording = true
        }
    }

    fun isRecording(): Boolean {
        return recording
    }

    // implemented by voice assistant activity to display text
    interface Callback {
        fun onResult(result: String)
    }
}