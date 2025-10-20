package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import io.legado.app.service.VoiceAssistantService
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren

@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object VoiceAssistant : CoroutineScope by MainScope() {
    
    var isActive: Boolean = false
        private set
    
    var activityContext: Context? = null
        private set
    
    /**
     * 语音助手状态枚举
     */
    enum class Status {
        STOPPED,      // 已停止
        INITIALIZING, // 初始化中
        RECORDING,    // 正在录音
        ERROR         // 错误状态
    }
    
    var status: Status = Status.STOPPED
        private set
    
    /**
     * 语音助手回调接口
     */
    interface VoiceCallback {
        fun onEngineInitialized()
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onSpeechResult(text: String, isFinal: Boolean, requestId: String? = null)
        fun onVoiceCommand(command: VoiceAssistantService.VoiceCommand, originalText: String)
        fun onError(errorMessage: String)
    }
    
    var callback: VoiceCallback? = null
        private set
    
    /**
     * 注册Activity回调
     */
    fun register(context: Context) {
        activityContext = context
        callback = context as? VoiceCallback
        
        // 设置服务监听器（延迟设置，因为服务可能还没启动）
        VoiceAssistantService.getInstance()?.setListener(object : VoiceAssistantService.VoiceAssistantListener {
            override fun onEngineInitialized() {
                callback?.onEngineInitialized()
            }
            
            override fun onRecordingStarted() {
                status = Status.RECORDING
                isActive = true
                callback?.onRecordingStarted()
            }
            
            override fun onRecordingStopped() {
                status = Status.STOPPED
                isActive = false
                callback?.onRecordingStopped()
            }
            
            override fun onSpeechResult(text: String, isFinal: Boolean, requestId: String?) {
                callback?.onSpeechResult(text, isFinal, requestId)
            }
            
            override fun onVoiceCommand(command: VoiceAssistantService.VoiceCommand, originalText: String) {
                callback?.onVoiceCommand(command, originalText)
            }
            
            override fun onError(errorMessage: String) {
                status = Status.ERROR
                callback?.onError(errorMessage)
            }
        })
    }
    
    /**
     * 注销Activity回调
     */
    fun unregister(context: Context) {
        if (activityContext === context) {
            activityContext = null
            callback = null
            VoiceAssistantService.getInstance()?.setListener(null)
        }
    }

    /**
     * 切换语音助手状态
     */
    fun toggle(context: Context) {
        // 使用扩展函数发送action
        context.startService<VoiceAssistantService> {
            action = "action_toggle_voice_assistant"
        }
    }
    
    /**
     * 检查服务是否运行
     */
    fun isServiceRunning(): Boolean = VoiceAssistantService.isRunning()
    
    /**
     * 检查是否正在录音
     */
    fun isRecording(): Boolean = status == Status.RECORDING
    
    /**
     * 销毁协程
     */
    fun destroy() {
        coroutineContext.cancelChildren()
    }
}
