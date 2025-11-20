package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.service.ASRAssistantService
import io.legado.app.service.IVWAssistantService
import io.legado.app.ui.assistant.VoiceAssistantActivity
import io.legado.app.utils.postEvent
import io.legado.app.utils.startActivity
import io.legado.app.utils.startService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import splitties.init.appCtx
import android.util.Log

@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object IVWAssistant : CoroutineScope by MainScope() {

    var activityContext: Context? = null
        private set

    var serviceContext: Context? = null
        private set

    private val context: Context get() = activityContext ?: serviceContext ?: appCtx

    var recording = false

    fun register(context: Context) {
        activityContext = context
        Log.d("IVWAssistant", "register context")
    }

    fun unregister(context: Context) {
        if (activityContext === context) {
            activityContext = null
        }
        Log.d("IVWAssistant", "unregister context")
        coroutineContext.cancelChildren()
    }

    fun registerService(context: Context) {
        serviceContext = context
    }

    fun unregisterService(context: Context) {
        serviceContext = null
    }

    fun start() {
        if (recording) {
            Log.d("IVWAssistant", "正在识别中，请勿重复开启\n")
            return
        }
        context.startService<IVWAssistantService> {
            action = IntentAction.startIVWAssistant
        }
        context.startActivity<VoiceAssistantActivity>()
        recording = true
        postEvent(EventBus.VOICE_RECORDING_STATE, recording)
    }

    fun stop() {
        if (!recording) {
            Log.d("IVWAssistant", "未在识别中，无需停止\n")
            return
        }
        context.startService<IVWAssistantService> {
            action = IntentAction.stopIVWAssistant
        }
        recording = false
        postEvent(EventBus.VOICE_RECORDING_STATE, recording)
    }

    fun isRecording(): Boolean {
        return recording
    }

    fun onIVWResult(key: String, result: String) {
        if (!key.isEmpty() && !result.isEmpty()) {
            Log.d("onIVWResult222", "唤醒结果: key=$key, result=$result")
            Log.d("onIVWResult222", "${activityContext == null}")
            activityContext?.let {
                if (it is IVWAssistant.Callback) {
                    Log.d("onIVWResult222", "2222")
                    it.onIVWResult(key, result)
                }
            }
        }
    }

    interface Callback {
        fun onIVWResult(key: String, result: String)
    }
}