package io.legado.app.service

import android.content.Intent
import android.util.Log
import com.iflytek.sparkchain.core.LogLvl
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks
import io.legado.app.base.BaseService
import io.legado.app.constant.IntentAction
import io.legado.app.help.assistant.AudioRecorder
import io.legado.app.model.VoiceAssistant
import java.util.concurrent.atomic.AtomicBoolean

class VoiceAssistantService : BaseService(), AsrCallbacks, AudioRecorder.AudioDataCallback {

    private val TAG = "ASR"

    private val asr: ASR by lazy {
        ASR()
    }

    private var isauth = false

    private var isrun = false

    private var count = 0

    private val iswrite = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        initSDK()
        initASR()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.startAssistant -> {
                    runASR()
                }

                IntentAction.stopAssistant -> {
                    stopASR()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        SparkChain.getInst().unInit()
    }

    private fun initSDK() {
        val sparkChainConfig = SparkChainConfig.builder()
        sparkChainConfig.appID("").apiKey("").apiSecret("").logLevel(LogLvl.VERBOSE.getValue())
        val ret = SparkChain.getInst().init(getApplicationContext(), sparkChainConfig)
        var res: String
        if (ret == 0) {
            res = "SDK初始化成功"
            isauth = true
        } else {
            res = "SDK初始化失败，错误码:" + ret
            isauth = false
        }
        Log.d(TAG, res)
    }

    private fun initASR() {
        asr.registerCallbacks(this)
    }

    private fun runASR() {
        if (isrun) {
            Log.d(TAG, "正在识别中，请勿重复开启\n")
            return
        }
        asr.language("zh_cn")
        asr.domain("iat")
        asr.accent("mandarin")
        asr.vinfo(true)
        asr.dwa("wpgs")
        count++
        val ret = asr.start(count.toString())
        if (ret != 0) {
            isrun = false
            Log.e(TAG, "识别开启失败，错误码:" + ret + "\n")
        } else {
            isrun = true
            iswrite.set(true)
            AudioRecorder.registerCallback(this)
            // 直接调用，权限处理已在 AudioRecorder 内部完成
            AudioRecorder.startRecord()
        }
    }

    private fun stopASR() {
        if (isrun) {
            AudioRecorder.stopRecord()
            asr.stop(false)
            Log.d(TAG, "已停止录音\n")
            isrun = false
        }
    }

    override fun onResult(asrResult: ASR.ASRResult, o: Any) {
        Log.e(TAG, "result:" + asrResult.getStatus())

        //以下信息需要开发者根据自身需求，如无必要，可不需要解析执行。
        val begin = asrResult.getBegin() //识别结果所处音频的起始点
        val end = asrResult.getEnd() //识别结果所处音频的结束点
        val status = asrResult.getStatus() //结果数据状态，0：识别的第一块结果,1：识别中间结果,2：识别最后一块结果
        val result = asrResult.getBestMatchText() //识别结果
        val sid = asrResult.getSid() //sid

        val vads = asrResult.getVads()
        val transcriptions = asrResult.getTranscriptions()
        var vad_begin = -1
        var vad_end = -1
        var word: String? = null
        for (vad in vads) {
            vad_begin = vad.getBegin()
            vad_end = vad.getEnd() //VAD结果
            Log.d(TAG, "vad={begin:" + vad_begin + ",end:" + vad_end + "}")
        }
        for (transcription in transcriptions) {
            val segments = transcription.getSegments()
            for (segment in segments) {
                word = segment.text //分词结果
                Log.d(TAG, "word={word:" + word + "}");
            }
        }
        val info =
            "result={begin:" + begin + ",end:" + end + ",status:" + status + ",result:" + result + ",sid:" + sid + "}"
        Log.d(TAG, info)

        VoiceAssistant.onResult(result, status)
    }

    override fun onError(asrError: ASR.ASRError, o: Any) {
        val code: Int = asrError.code
        val msg: String? = asrError.getErrMsg()
        val sid: String? = asrError.getSid()
        val info = "error={code:" + code + ",msg:" + msg + ",sid:" + sid + "}"
        Log.d(TAG, info)
        isrun = false
    }

    override fun onAudioData(data: ByteArray, size: Int) {
        // 发送音频数据到ASR
        if (isrun && iswrite.get()) {
            val ret = asr.write(data)
            if (ret != 0) {
                iswrite.set(false)
            }
        }
    }

    override fun onAudioVolume(db: Double, volume: Int) {
        // 音量回调，可用于UI显示
        Log.d(TAG, "音量: db=$db, level=$volume")
    }
}