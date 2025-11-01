package io.legado.app.model

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction

import io.legado.app.utils.startService
import io.legado.app.service.VoiceAssistantService
import io.legado.app.ui.assistant.VoiceAssistantActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.postEvent
import io.legado.app.utils.startActivity
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
//            if (context is VoiceAssistantActivity) {
//                (context as VoiceAssistantActivity).moveTaskToBack(true)
//            }
            recording = false
        } else {
            context.startService<VoiceAssistantService> {
                action = IntentAction.startAssistant
            }
            context.startActivity<VoiceAssistantActivity>()
            recording = true
        }
        postEvent(EventBus.VOICE_RECORDING_STATE, recording)
    }

    fun isRecording(): Boolean {
        return recording
    }

    fun onResult(result: String, status: Int) {
        activityContext?.let {
            if (it is Callback) {
                it.onResult(result, status)
            }
        }
    }

    // implemented by voice assistant activity to display text
    interface Callback {
        fun onResult(result: String, status: Int)
    }
}