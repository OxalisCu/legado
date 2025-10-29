package io.legado.app.help.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import io.legado.app.R
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.*
import splitties.init.appCtx
import java.util.concurrent.atomic.AtomicBoolean

object AudioRecorder : CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {
    private val TAG = "AudioRecorder"
    
    private val sampleRateInHz = 16000
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val channels = AudioFormat.CHANNEL_IN_MONO
    private val bufferSize: Int = AudioRecord.getMinBufferSize(sampleRateInHz, channels, audioFormat)
    
    private var mRecorder: AudioRecord? = null
    private val isStart = AtomicBoolean(false)
    private var recordJob: Job? = null
    private var callback: AudioDataCallback? = null

    fun registerCallback(callback: AudioDataCallback) {
        this.callback = callback
    }

    /**
     * 录音协程
     */
    private suspend fun recordCoroutine() {
        try {
            mRecorder?.let { recorder ->
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val tempBuffer = ByteArray(bufferSize)

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    stopRecord()
                    return
                }

                recorder.startRecording()

                while (isStart.get()) {
                    try {
                        val bytesRecord = recorder.read(tempBuffer, 0, bufferSize)

                        when {
                            bytesRecord == AudioRecord.ERROR_INVALID_OPERATION || 
                            bytesRecord == AudioRecord.ERROR_BAD_VALUE -> {
                                yield() // 让出协程执行权
                                continue
                            }

                            bytesRecord > 0 && isStart.get() -> {
                                // 使用RMS方法计算音量
                                var sumSquares = 0.0
                                val sampleCount = bytesRecord / 2  // 每个样本16位(2字节)

                                for (i in 0 until bytesRecord step 2) {
                                    // 将两个字节转换为一个16位短整型
                                    val sample =
                                        (tempBuffer[i].toInt() and 0xFF) or ((tempBuffer[i + 1].toInt() and 0xFF) shl 8)
                                    // 计算平方和
                                    sumSquares += sample * sample
                                }

                                // 计算RMS (均方根)
                                val rms = kotlin.math.sqrt(sumSquares / sampleCount)

                                // 转换为分贝值 (防止除以0)
                                var db = -120.0 // 默认极低值
                                if (rms > 1e-10) {  // 避免log(0)
                                    db = 20 * kotlin.math.log10(rms / 32767.0)
                                }

                                // 映射到0-9音量等级
                                val volume = if (db > -60) {
                                    // 更符合人耳感知的映射：-60dB(0级)到-20dB(9级)
                                    (kotlin.math.min(
                                        9.0, kotlin.math.max(0.0, (db + 60) * 9 / 40.0)
                                    )).toInt()
                                } else {
                                    0
                                }

                                callback?.onAudioVolume(db, volume)
                                // 我们这里直接将pcm音频原数据写入文件 这里可以直接发送至服务器 对方采用AudioTrack进行播放原数据
                                callback?.onAudioData(
                                    tempBuffer.copyOf(bytesRecord), bytesRecord
                                )
                                
                                yield() // 让出协程执行权，避免占用过多CPU
                            }

                            else -> {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "读取音频数据异常: ${e.message}")
                        if (isStart.get()) {
                            yield()
                        } else {
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "录音异常: ${e.message}")
            e.printStackTrace()
        } finally {
            mRecorder?.let {
                try {
                    if (it.state == AudioRecord.STATE_INITIALIZED) {
                        it.stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "停止录音异常: ${e.message}")
                }
                it.release()
            }
            mRecorder = null
        }
    }

    /**
     * 启动录音（带权限检查和请求）
     */
//    @RequiresPermission("MissingPermission")
    fun startRecord() {
        withRecordAudioPermission {
            startRecordInternal()
        }
    }

    /**
     * 启动录音内部方法（需要权限）
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecordInternal() {
        try {
            // 取消之前的协程
            recordJob?.cancel()
            
            // 初始化 AudioRecord
            if (mRecorder == null) {
                mRecorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRateInHz,
                    channels,
                    audioFormat,
                    bufferSize
                )
            }
            
            isStart.set(true)
            recordJob = launch {
                recordCoroutine()
            }
            Log.i(TAG, "启动录音协程")
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 带录音权限检查和请求的包装方法
     */
    private fun withRecordAudioPermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            LogUtils.d(TAG, "缺少录音权限，请求权限")
            PermissionsCompat.Builder()
                .addPermissions(*Permissions.Group.MICROPHONE)
                .rationale(R.string.voice_assistant_record_audio_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                        Log.d(TAG, "录音权限已授予，开始录音")
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
     * 停止录音
     */
    fun stopRecord() {
        try {
            isStart.set(false)
            
            // 取消协程
            recordJob?.cancel()
            recordJob = null
            
            // 停止并释放 AudioRecord
            mRecorder?.let {
                try {
                    if (it.state == AudioRecord.STATE_INITIALIZED) {
                        it.stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "停止录音异常: ${e.message}")
                }
                it.release()
            }
            mRecorder = null
            callback = null
            
            Log.i(TAG, "停止录音")
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 清理资源
     */
    fun destroy() {
        stopRecord()
        cancel()
    }

    interface AudioDataCallback {
        fun onAudioData(data: ByteArray, size: Int)
        fun onAudioVolume(db: Double, volume: Int)
    }
}
