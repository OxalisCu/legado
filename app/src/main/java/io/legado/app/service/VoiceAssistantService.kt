package io.legado.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.ActivityCompat
import io.legado.app.constant.AppLog
import androidx.lifecycle.LifecycleObserver
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import io.legado.app.constant.EventBus
import io.legado.app.utils.postEvent

/**
 * 全局语音助手服务
 * 集成了语音识别引擎、音频录制和指令处理
 */
class VoiceAssistantService : Service(), LifecycleObserver, SpeechEngine.SpeechListener {

    companion object {
        private const val ACTION_TOGGLE_VOICE_ASSISTANT = "action_toggle_voice_assistant"

        private var instance: VoiceAssistantService? = null

        fun isRunning(): Boolean = instance != null
        
        fun isVoiceAssistantActive(): Boolean = instance?.isEngineStarted ?: false
        
        fun getInstance(): VoiceAssistantService? = instance

        // 豆包STT配置参数
        private const val APP_ID = "YOUR_APP_ID"
        private const val APP_TOKEN = "YOUR_APP_TOKEN"
        private const val ASR_CLUSTER = "volcengine_streaming_common"
        private const val UID = "legado_user"
        private const val ASR_URI = "/SpeechService"
        private const val ASR_ADDRESS = "openspeech.bytedance.com"

        // 音频参数配置
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_NUM = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_SIZE_IN_SECONDS = 0.08f
        private const val DEFAULT_PACKAGE_DURATION = 100
    }

    // 语音识别引擎
    private var speechEngine: SpeechEngine? = null
    private var isEngineStarted = false
    private var isInitialized = false

    // 音频录制
    private var audioRecord: AudioRecord? = null
    private var recorderThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private var bufferSize = 0

    // 服务管理
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listener: VoiceAssistantListener? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.put("全局语音助手服务启动")

        // 显式加载 speechengine 本地库，避免因链接顺序问题导致初始化失败
        tryLoadSpeechEngineLib()

        // 如果有权限自动初始化引擎
        if (hasRecordPermission()) {
            serviceScope.launch {
                initVoiceEngine()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_VOICE_ASSISTANT -> toggleVoiceAssistant()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        destroyEngine()
        instance = null
        AppLog.put("全局语音助手服务销毁")
    }

    // ================ 语音引擎管理 ================

    private suspend fun initVoiceEngine() {
        try {
            if (speechEngine != null) {
                AppLog.put("引擎已初始化")
                return
            }

            AppLog.put("开始初始化语音引擎")
            speechEngine = SpeechEngineGenerator.getInstance().apply {
                createEngine()
                setContext(applicationContext)
            }

            AppLog.put("SDK 版本号: ${speechEngine?.getVersion()}")

            configInitParams()
            speechEngine?.setListener(this)

            val ret = speechEngine?.initEngine()
            if (ret != SpeechEngineDefines.ERR_NO_ERROR) {
                AppLog.put("引擎初始化失败，返回值: $ret", null)
                listener?.onError("引擎初始化失败，错误码: $ret")
                postEvent(EventBus.VOICE_ERROR, "引擎初始化失败: $ret")
            } else {
                AppLog.put("语音引擎初始化成功!")
                isInitialized = true
                listener?.onEngineInitialized()
            }
        } catch (t: Throwable) {
            AppLog.put("引擎初始化异常", t)
            listener?.onError("引擎初始化异常: ${t.message}")
            postEvent(EventBus.VOICE_ERROR, "引擎初始化异常: ${t.message}")
        }
    }

    /**
     * 显式尝试加载 speechengine 本地库；若缺失仅记录并提示。
     */
    private fun tryLoadSpeechEngineLib() {
        try {
            System.loadLibrary("speechengine")
            AppLog.put("speechengine 本地库加载成功")
        } catch (t: Throwable) {
            AppLog.put("speechengine 本地库加载失败: ${t.message}")
            postEvent(EventBus.VOICE_ERROR, "缺少 speechengine 本地库: ${t.message}")
        }
    }

    

    private fun configInitParams() {
        speechEngine?.apply {
            setOptionString(
                SpeechEngineDefines.PARAMS_KEY_ENGINE_NAME_STRING,
                SpeechEngineDefines.ASR_ENGINE
            )
            setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_ID_STRING, APP_ID)
            setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_TOKEN_STRING, APP_TOKEN)
            setOptionString(SpeechEngineDefines.PARAMS_KEY_ASR_CLUSTER_STRING, ASR_CLUSTER)
            setOptionString(SpeechEngineDefines.PARAMS_KEY_ASR_ADDRESS_STRING, ASR_ADDRESS)
            setOptionString(SpeechEngineDefines.PARAMS_KEY_ASR_URI_STRING, ASR_URI)
            setOptionString(SpeechEngineDefines.PARAMS_KEY_UID_STRING, UID)
            setOptionString(
                SpeechEngineDefines.PARAMS_KEY_RECORDER_TYPE_STRING,
                SpeechEngineDefines.RECORDER_TYPE_RECORDER
            )
            setOptionInt(SpeechEngineDefines.PARAMS_KEY_SAMPLE_RATE_INT, SAMPLE_RATE)
            setOptionInt(SpeechEngineDefines.PARAMS_KEY_CHANNEL_NUM_INT, CHANNEL_NUM)
            setOptionInt(SpeechEngineDefines.PARAMS_KEY_UP_CHANNEL_NUM_INT, CHANNEL_NUM)
            setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_ENABLE_GET_VOLUME_BOOL, true)
            setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_ASR_AUTO_STOP_BOOL, false)
            setOptionString(
                SpeechEngineDefines.PARAMS_KEY_LOG_LEVEL_STRING,
                SpeechEngineDefines.LOG_LEVEL_DEBUG
            )
        }
    }

    fun startVoiceAssistant(): Boolean {
        if (isEngineStarted) {
            AppLog.put("引擎正在运行中", null)
            return true
        }

        return try {
            AppLog.put("开始录音")
            val ret =
                speechEngine?.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE, "")

            if (ret != SpeechEngineDefines.ERR_NO_ERROR) {
                AppLog.put("send directive syncstop failed, $ret", null)
                listener?.onError("启动失败，错误码: $ret")
                postEvent(EventBus.VOICE_ERROR, "启动失败: $ret")
                false
            } else {
                val startRet =
                    speechEngine?.sendDirective(SpeechEngineDefines.DIRECTIVE_START_ENGINE, "")
                if (startRet == SpeechEngineDefines.ERR_REC_CHECK_ENVIRONMENT_FAILED) {
                    AppLog.put("录音环境检查失败", null)
                    listener?.onError("请检查麦克风权限")
                    postEvent(EventBus.VOICE_ERROR, "请检查麦克风权限")
                    false
                } else if (startRet != SpeechEngineDefines.ERR_NO_ERROR) {
                    AppLog.put("send directive start failed, $startRet", null)
                    listener?.onError("启动失败，错误码: $startRet")
                    postEvent(EventBus.VOICE_ERROR, "启动失败: $startRet")
                    false
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            AppLog.put("开始录音异常", e)
            listener?.onError("开始录音异常: ${e.message}")
            postEvent(EventBus.VOICE_ERROR, "开始录音异常: ${e.message}")
            false
        }
    }

    fun stopVoiceAssistant() {
        if (!isEngineStarted) {
            AppLog.put("引擎未启动", null)
            return
        }

        try {
            AppLog.put("停止录音")
            speechEngine?.sendDirective(SpeechEngineDefines.DIRECTIVE_FINISH_TALKING, "")
            stopRecording()
        } catch (e: Exception) {
            AppLog.put("停止录音异常", e)
            listener?.onError("停止录音异常: ${e.message}")
            postEvent(EventBus.VOICE_ERROR, "停止录音异常: ${e.message}")
        }
    }

    fun toggleVoiceAssistant(): Boolean {
        if (!isInitialized) {
            listener?.onError("引擎未初始化")
            postEvent(EventBus.VOICE_ERROR, "引擎未初始化")
            postEvent(EventBus.VOICE_RECORDING_STATE, false)
            return false
        }
        return if (isEngineStarted) {
            stopVoiceAssistant()
            false
        } else {
            startVoiceAssistant()
        }
    }

    fun setListener(listener: VoiceAssistantListener?) {
        this.listener = listener
    }

    fun destroyEngine() {
        try {
            stopRecording()
            speechEngine?.destroyEngine()
            speechEngine = null
            isEngineStarted = false
            isInitialized = false
            AppLog.put("引擎销毁完成!")
        } catch (e: Exception) {
            AppLog.put("销毁引擎异常", e)
        }
    }

    // ================ 音频录制管理 ================

    private fun initAudioRecorder(): Boolean {
        if (audioRecord != null) return true

        // 显式检查录音权限
        if (!hasRecordPermission()) {
            throw SecurityException("录音权限未授权")
        }

        bufferSize = (SAMPLE_RATE * BUFFER_SIZE_IN_SECONDS * BYTES_PER_SAMPLE * CHANNEL_NUM).toInt()
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            if (CHANNEL_NUM == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val actualBufferSize = Math.max(minBufferSize, bufferSize)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                if (CHANNEL_NUM == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, actualBufferSize * 10
            )
        } catch (e: SecurityException) {
            AppLog.put("录音权限被拒绝", e)
            return false
        }

        return if (audioRecord?.state == AudioRecord.STATE_UNINITIALIZED) {
            AppLog.put("初始化录音器失败", null)
            audioRecord?.release()
            audioRecord = null
            false
        } else {
            true
        }
    }

    private fun startRecording(): Boolean {
        if (!initAudioRecorder()) {
            AppLog.put("音频录制器初始化失败", null)
            return false
        }

        if (recorderThread?.isAlive == true) {
            AppLog.put("音频录制器已在运行", null)
            return true
        }

        isRecording.set(true)
        recorderThread = RecorderThread().apply { start() }
        AppLog.put("音频录制器启动成功")
        // 通知开始录音
        postEvent(EventBus.VOICE_RECORDING_STATE, true)
        return true
    }

    private fun stopRecording() {
        if (recorderThread?.isAlive != true) {
            AppLog.put("音频录制器未启动", null)
            return
        }

        isRecording.set(false)
        recorderThread?.interrupt()

        try {
            recorderThread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        recorderThread = null
        audioRecord?.stop()
        AppLog.put("音频录制器停止")
        // 通知停止录音
        postEvent(EventBus.VOICE_RECORDING_STATE, false)
    }

    private inner class RecorderThread : Thread() {
        override fun run() {
            val audioRecord = audioRecord ?: return

            try {
                // 再次确认权限（虽然initAudioRecorder已检查，但这里作为最后防线）
                if (!hasRecordPermission()) {
                    AppLog.put("录音权限在启动时被拒绝", null)
                    return
                }
                
                audioRecord.startRecording()
                AppLog.put("开始录制音频")

                val outputStream = ByteArrayOutputStream()
                val totalPackageSize =
                    (SAMPLE_RATE * CHANNEL_NUM * BYTES_PER_SAMPLE * DEFAULT_PACKAGE_DURATION / 1000).toLong()

                while (isRecording.get() && !isInterrupted) {
                    val buffer = ByteArray(bufferSize)
                    outputStream.reset()
                    var currentPackageSize = 0L

                    while (isRecording.get() && !isInterrupted && currentPackageSize < totalPackageSize) {
                        val bytesRead = audioRecord.read(buffer, 0, bufferSize)

                        if (bytesRead > 0) {
                            outputStream.write(buffer, 0, bytesRead)
                            currentPackageSize += bytesRead
                        } else if (bytesRead < 0) {
                            AppLog.put("录音读取错误，错误码: $bytesRead", null)
                            break
                        }
                    }

                    val audioData = outputStream.toByteArray()
                    if (audioData.isNotEmpty()) {
                        val ret = speechEngine?.feedAudio(audioData, audioData.size)
                        if (ret != 0) {
                            AppLog.put("投递音频数据失败，错误码: $ret", null)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.put("录音线程异常", e)
            } finally {
                try {
                    audioRecord.stop()
                } catch (e: Exception) {
                    AppLog.put("停止录音异常", e)
                }
            }
        }
    }

    // ================ SpeechEngine.SpeechListener 实现 ================

    override fun onSpeechMessage(type: Int, data: ByteArray, len: Int) {
        val messageData = String(data)

        when (type) {
            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_START -> {
                AppLog.put("引擎启动成功: $messageData")
                isEngineStarted = true
                startRecording()
                listener?.onRecordingStarted()
                postEvent(EventBus.VOICE_RECORDING_STATE, true)
            }

            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_STOP -> {
                AppLog.put("引擎关闭: $messageData")
                isEngineStarted = false
                listener?.onRecordingStopped()
                postEvent(EventBus.VOICE_RECORDING_STATE, false)
            }

            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_ERROR -> {
                AppLog.put("错误信息: $messageData", null)
                try {
                    val reader = JSONObject(messageData)
                    val errorMsg =
                        if (reader.has("err_msg")) reader.getString("err_msg") else messageData
                    listener?.onError("语音识别错误: $errorMsg")
                    postEvent(EventBus.VOICE_ERROR, errorMsg)
                } catch (e: JSONException) {
                    listener?.onError("语音识别错误: $messageData")
                    postEvent(EventBus.VOICE_ERROR, messageData)
                }
            }

            SpeechEngineDefines.MESSAGE_TYPE_PARTIAL_RESULT -> {
                AppLog.put("部分结果: $messageData")
                postEvent(EventBus.VOICE_PARTIAL_RESULT, messageData)
                parseSpeechResult(messageData, false)
            }

            SpeechEngineDefines.MESSAGE_TYPE_FINAL_RESULT -> {
                AppLog.put("最终结果: $messageData")
                postEvent(EventBus.VOICE_FINAL_RESULT, messageData)
                parseSpeechResult(messageData, true)
            }

            SpeechEngineDefines.MESSAGE_TYPE_VOLUME_LEVEL -> {
                AppLog.put("录音音量: $messageData")
            }
        }
    }

    private fun parseSpeechResult(data: String, isFinal: Boolean) {
        try {
            val reader = JSONObject(data)
            if (!reader.has("result")) return

            val resultArray = reader.getJSONArray("result")
            if (resultArray.length() == 0) return

            val text = resultArray.getJSONObject(0).getString("text")
            if (text.isEmpty()) return

            val requestId = if (isFinal && reader.has("reqid")) reader.getString("reqid") else null
            listener?.onSpeechResult(text, isFinal, requestId)

            // 解析语音指令
            if (isFinal) {
                parseAndDispatchVoiceCommand(text)
            }

        } catch (e: JSONException) {
            AppLog.put("解析语音识别结果失败", e)
        }
    }

    // ================ 语音指令处理 ================

    private fun parseAndDispatchVoiceCommand(text: String) {
        val command = text.lowercase().trim()

        val voiceCommand = when {
            command.contains("播放") || command.contains("开始") -> VoiceCommand.PLAY_AUDIO
            command.contains("暂停") || command.contains("停止") -> VoiceCommand.PAUSE_AUDIO
            command.contains("下一章") || command.contains("下一节") -> VoiceCommand.NEXT_CHAPTER
            command.contains("上一章") || command.contains("上一节") -> VoiceCommand.PREVIOUS_CHAPTER
            command.contains("加速") || command.contains("快一点") -> VoiceCommand.SPEED_UP
            command.contains("减速") || command.contains("慢一点") -> VoiceCommand.SPEED_DOWN
            command.contains("音量") && command.contains("大") -> VoiceCommand.VOLUME_UP
            command.contains("音量") && command.contains("小") -> VoiceCommand.VOLUME_DOWN
            command.contains("书签") -> VoiceCommand.ADD_BOOKMARK
            command.contains("设置") -> VoiceCommand.OPEN_SETTINGS
            command.contains("搜索") -> VoiceCommand.SEARCH
            else -> VoiceCommand.TEXT_INPUT
        }

        listener?.onVoiceCommand(voiceCommand, command)
        // 业务层自行通过EventBus订阅 VOICE_FINAL_RESULT 文本或自定义的命令事件处理
        // 这里暂不额外发命令事件，避免事件风暴
        handleVoiceCommand(voiceCommand, command)
    }

    private fun handleVoiceCommand(command: VoiceCommand, originalText: String) {
        AppLog.put("处理语音指令: $command - $originalText")
        // TODO: 分发到具体模块（保留原注释）
    }

    // ================ 工具方法 ================

    fun hasRecordPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ================ 监听器接口 ================

    interface VoiceAssistantListener {
        fun onEngineInitialized()
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onSpeechResult(text: String, isFinal: Boolean, requestId: String?)
        fun onVoiceCommand(command: VoiceCommand, originalText: String)
        fun onError(errorMessage: String)
    }

    enum class VoiceCommand {
        PLAY_AUDIO, PAUSE_AUDIO, NEXT_CHAPTER, PREVIOUS_CHAPTER,
        SPEED_UP, SPEED_DOWN, VOLUME_UP, VOLUME_DOWN,
        ADD_BOOKMARK, OPEN_SETTINGS, SEARCH, TEXT_INPUT
    }
}