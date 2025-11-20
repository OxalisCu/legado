package io.legado.app.service

import android.Manifest
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.annotation.RequiresPermission
import io.legado.app.R
import com.iflytek.aikit.core.AiAudio
import com.iflytek.aikit.core.AiHandle
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.AiListener
import com.iflytek.aikit.core.AiRequest
import com.iflytek.aikit.core.AiResponse
import com.iflytek.aikit.core.AiStatus
import io.legado.app.base.BaseService
import io.legado.app.constant.IntentAction
import io.legado.app.model.IVWAssistant
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离线唤醒IVW70 e867a88f2
 * 调用流程：SDKinit-registerListener-start-write-end-engineUninit-SDKUninit
 * 注意:engineuninit仅当最终退出不使用唤醒的时候才调用，调用engineuninit后不能再重新start唤醒能力，否则引擎会报错崩溃
 */
class IVWAssistantService : BaseService(), AiListener {

    private val TAG = "IVWAssistant"

    private val ABILITYID: String = "e867a88f2"

    private lateinit var resDir: File

    // 录音缓冲区大小
    private val BUFFER_SIZE = 1280

    private var aiHandle: AiHandle? = null

    private val isEnd = AtomicBoolean(true)

    private val isRecording = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null

    private var mHandler: Handler? = null

    // 消息类型常量
    companion object {
        private const val START = 0x0001
        private const val WRITE_BY_RECORDING = 0x0002
        private const val END = 0x0004
    }

    override fun onCreate() {
        super.onCreate()
        resDir = this.getExternalFilesDir("ivw") ?: this.filesDir
        Log.d(TAG, "onCreate: resDir=${resDir.absolutePath}")
        initIVW()
    }

    private fun initIVW() {
        Log.d(TAG, "initIVW: 初始化IVW服务")
        AiHelper.getInst().registerListener(ABILITYID, this)
        // 确保RES_DIR目录存在
        val resDirFile = File(resDir.absolutePath)
        if (!resDirFile.exists()) {
            resDirFile.mkdirs()
        }
        // 启动工作线程
        mThread.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.startIVWAssistant -> {
                    runIVW()
                }

                IntentAction.stopIVWAssistant -> {
                    stopIVW()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 工作线程，处理异步消息
     */
    private val mThread = Thread {
        Looper.prepare()
        mHandler = object : Handler(Looper.myLooper()!!) {
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)
                when (msg.what) {
                    START -> {
                        Log.d(TAG, "handleMessage: START")
                        val ret = start()
                        if (ret == 0) {
                            createAudioRecord()
                            isRecording.set(true)
                            audioRecord?.startRecording()
                            Log.d(TAG, "handleMessage: 开始录音")
                            mHandler?.removeCallbacksAndMessages(null)
                            val writeMsg = Message.obtain()
                            writeMsg.what = WRITE_BY_RECORDING
                            writeMsg.obj = AiStatus.BEGIN
                            mHandler?.sendMessage(writeMsg)
                        }
                    }

                    WRITE_BY_RECORDING -> {
                        Log.d(TAG, "handleMessage: WRITE_BY_RECORDING")
                        val status = msg.obj as AiStatus
                        val data = ByteArray(BUFFER_SIZE)
                        val read = audioRecord?.read(data, 0, BUFFER_SIZE) ?: -1

                        // 计算音量
                        val volume = calculateVolume(data)
                        Log.d(TAG, "当前分贝: ${Math.abs(volume)}")

                        if (read != AudioRecord.ERROR_INVALID_OPERATION) {
                            write(data, status)
                        }

                        if (status == AiStatus.END) {
                            audioRecord?.stop()
                            isRecording.set(false)
                            Log.d(TAG, "handleMessage: 停止录音")
                            mHandler?.sendEmptyMessage(END)
                        } else {
                            if (isRecording.get()) {
                                val writeMsg = Message.obtain()
                                writeMsg.what = WRITE_BY_RECORDING
                                writeMsg.obj = AiStatus.CONTINUE
                                mHandler?.sendMessage(writeMsg)
                            }
                        }
                    }

                    END -> {
                        Log.d(TAG, "handleMessage: END")
                        end()
                    }
                }
            }
        }
        Looper.loop()
    }

    /**
     * 开始唤醒（录音方式）
     */
    fun runIVW() {
        Log.d(TAG, "runIVW: 启动唤醒（录音方式）")
        if (!isEnd.get()) {
            Log.d(TAG, "runIVW: 等待结果中，请稍等")
            return
        }
        val msg = Message.obtain()
        msg.what = START
        mHandler?.sendMessage(msg)
    }

    /**
     * 停止唤醒
     */
    fun stopIVW() {
        Log.d(TAG, "stopIVW: 停止唤醒")
        if (isRecording.get()) {
            val msg = Message.obtain()
            msg.what = WRITE_BY_RECORDING
            msg.obj = AiStatus.END
            mHandler?.sendMessage(msg)
        }
    }

    /**
     * 开始会话
     */
    private fun start(): Int {
        if (!keyword2File()) {
            Log.e(TAG, "start: 唤醒词文件写入失败，请检查是否有读写权限")
            return -1
        }

        val customBuilder = AiRequest.builder()
        customBuilder.customText("key_word", "${resDir.absolutePath}/keyword.txt", 0)
        Log.d(TAG, "ddd: ${resDir.absolutePath}")
        val buildRet= customBuilder.build()
        Log.d(TAG, "start: open ivw loadData 开始 buildRet=$buildRet")
        var ret = AiHelper.getInst().loadData(ABILITYID, buildRet)
        if (ret != 0) {
            Log.e(TAG, "start: open ivw loadData 失败：$ret")
            return ret
        }
        Log.d(TAG, "start: open ivw loadData success：$ret")

        val indexs = intArrayOf(0)
        ret = AiHelper.getInst().specifyDataSet(ABILITYID, "key_word", indexs)
        if (ret != 0) {
            Log.e(TAG, "start: open ivw specifyDataSet 失败：$ret")
            return ret
        }
        Log.d(TAG, "start: open ivw specifyDataSet success：$ret")

        val paramBuilder = AiRequest.builder()
        paramBuilder.param("wdec_param_nCmThreshold", "0 0:800")
        paramBuilder.param("gramLoad", true)

        isEnd.set(false)
        aiHandle = AiHelper.getInst().start(ABILITYID, paramBuilder.build(), null)
        if (aiHandle?.code != 0) {
            Log.e(TAG, "start: open ivw start失败：${aiHandle?.code}")
            return aiHandle?.code ?: -1
        }

        Log.d(TAG, "start: 启动成功")
        return 0
    }

    /**
     * 写入数据
     */
    private fun write(part: ByteArray, status: AiStatus) {
        if (isEnd.get()) {
            return
        }

        /**
         * 送入音频需要标识音频的状态，第一帧为起始帧，status要传AiStatus.BEGIN,最后一帧为结束帧，status要传AiStatus.END,其他为中间帧，status要传AiStatus.CONTINUE
         * 音频要求16bit，16K，单声道的pcm音频。
         * 建议每次发送音频间隔40ms，每次发送音频字节数为一帧音频大小的整数倍。
         */
        val aiAudio = AiAudio.get("wav").data(part).status(status).valid()
        val dataBuilder = AiRequest.builder()
        dataBuilder.payload(aiAudio)

        val ret = AiHelper.getInst().write(dataBuilder.build(), aiHandle)
        if (ret != 0) {
            Log.e(TAG, "write: write失败：$ret")
        }
    }

    /**
     * 结束会话
     */
    private fun end() {
        if (!isEnd.get()) {
            val ret = AiHelper.getInst().end(aiHandle)
            if (ret == 0) {
                isEnd.set(true)
                aiHandle = null
                Log.d(TAG, "end: 唤醒完成，end：$ret")
            } else {
                Log.e(TAG, "end: 唤醒完成，end失败：$ret")
            }
        }
    }

    /**
     * 创建录音器
     */
    private fun createAudioRecord() {
        withRecordAudioPermission {
            createAudioRecordInternal()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecordInternal() {
        if (isRecording.get()) {
            return
        }

        if (audioRecord == null) {
            Log.d(TAG, "createAudioRecord: 创建录音器")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                BUFFER_SIZE
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "createAudioRecord: AudioRecord 初始化失败 state=${audioRecord?.state}")
                audioRecord?.release()
                audioRecord = null
            }
        }
    }

    private fun withRecordAudioPermission(block: () -> Unit) {
        try {
            block()
        } catch (_: SecurityException) {
            LogUtils.d(TAG, "缺少录音权限，请求权限")
            PermissionsCompat.Builder()
                .addPermissions(*Permissions.Group.MICROPHONE)
                .rationale(R.string.voice_assistant_record_audio_permission_rationale)
                .onGranted {
                    try {
                        block()
                        Log.d(TAG, "录音权限已授予，创建 AudioRecord")
                    } catch (_: SecurityException) {
                        LogUtils.e(TAG, "授予录音权限后仍然失败")
                    }
                }
                .onDenied {
                    LogUtils.e(TAG, "用户拒绝了录音权限")
                }
                .request()
        }
    }

    /**
     * 将关键词写入文件
     * 默认关键词为"你好小迪"，可以通过方法参数自定义
     */
    private fun keyword2File(keywords: String = "你好小迪"): Boolean {
        return try {
            val keywordFile = File("${resDir.absolutePath}/keyword.txt")
            if (keywordFile.exists()) {
                keywordFile.delete()
            }
            Log.d("keyword2File", "keyword2File: 写入关键词文件，路径：${resDir.absolutePath}/keyword.txt")

            val binFile = File("${resDir.absolutePath}/keyword.bin")
            if (binFile.exists()) {
                binFile.delete()
            }

            val str = keywords.replace("，", ",")
            val keywordArray = str.split(",")

            if (!keywordFile.exists()) {
                keywordFile.createNewFile()
            }

            val writer = OutputStreamWriter(FileOutputStream(keywordFile), "UTF-8")
            val bufferedWriter = BufferedWriter(writer)

            for (keyword in keywordArray) {
                bufferedWriter.write(keyword)
                bufferedWriter.write(";")
                bufferedWriter.newLine()
            }

            bufferedWriter.close()
            Log.d(TAG, "keyword2File: 关键词文件写入成功，关键词：$keywords")
            true
        } catch (e: IOException) {
            Log.e(TAG, "keyword2File: 关键词文件写入失败", e)
            false
        }
    }

    /**
     * 根据录音数据计算音量
     */
    private fun calculateVolume(buffer: ByteArray): Int {
        var sumVolume = 0.0
        var avgVolume: Double
        var volume = 0

        for (i in buffer.indices step 2) {
            if (i + 1 >= buffer.size) break
            val v1 = buffer[i].toInt() and 0xFF
            val v2 = buffer[i + 1].toInt() and 0xFF
            var temp = v1 + (v2 shl 8) // 小端
            if (temp >= 0x8000) {
                temp = 0xFFFF - temp
            }
            sumVolume += Math.abs(temp)
        }

        avgVolume = sumVolume / buffer.size / 2
        volume = (Math.log10(1 + avgVolume) * 10).toInt()
        return volume
    }

    override fun onResult(handleID: Int, outputData: List<AiResponse>, usrContext: Any?) {
        if (outputData.isNotEmpty()) {
            Log.i(
                TAG, "onResult:handleID:$handleID:${outputData.size}," +
                        "usrContext:$usrContext"
            )
            for (response in outputData) {
                Log.d(TAG, "onResult:handleID:$handleID:${response.key}")
                val key = response.key //引擎结果的key
                val bytes = response.value //识别结果
                val result = String(bytes)
                Log.d(TAG, "key=$key")
                Log.d(TAG, "value=$result")
                Log.d(TAG, "status=${response.status}")
                if (key == "func_wake_up" || key == "func_pre_wakeup") {
                    Log.d("onIVWResult111", "唤醒结果: key=$key, result=$result")
                    IVWAssistant.onIVWResult(key, result)
                }
            }
        }
    }

    override fun onEvent(i: Int, i1: Int, list: List<AiResponse>, o: Any) {
        Log.i(TAG, "事件通知，能力执行中,onEvent $i EVENT: $i1")
    }

    override fun onError(i: Int, i1: Int, s: String, o: Any) {
        Log.e(
            TAG,
            "错误通知，能力执行终止,Ability $i ERROR: $s,err code: $i1"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (audioRecord != null && isRecording.get()) {
            audioRecord?.stop()
            isRecording.set(false)
            Log.d(TAG, "onDestroy: 停止录音")
        }
        end()
        audioRecord = null
        mHandler?.removeCallbacksAndMessages(null)
        mHandler?.looper?.quit()
        Log.d(TAG, "onDestroy: 服务销毁")
    }
}