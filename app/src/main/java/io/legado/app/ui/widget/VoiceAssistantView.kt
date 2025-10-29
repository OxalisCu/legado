package io.legado.app.ui.widget

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import io.legado.app.constant.AppLog
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.legado.app.R
import io.legado.app.model.VoiceAssistant
import io.legado.app.constant.EventBus
import io.legado.app.utils.eventObservable

/**
 * 全局语音助手浮动窗口
 * 使用VoiceAssistant model对象统一管理状态
 */
class VoiceAssistantView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var windowManager: WindowManager? = null
    private var isShowing = false

    // 布局参数
    private val params = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        format = PixelFormat.TRANSLUCENT

        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.BOTTOM or Gravity.END

        x = (16 * context.resources.displayMetrics.density).toInt()
        y = (200 * context.resources.displayMetrics.density).toInt()
    }

    private val layoutInflater by lazy {
        LayoutInflater.from(context)
    }

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /**
     * 显示浮动窗口
     */
    fun show() {
        if (isShowing) return

        try {
//            VoiceAssistant.register(this)
            createFloatingView()
            windowManager?.addView(this, params)
            isShowing = true
            AppLog.put("语音助手浮动窗口已显示")
        } catch (e: Exception) {
            AppLog.put("显示浮动窗口失败", e)
        }
    }

    /**
     * 隐藏浮动窗口
     */
    fun hide() {
        if (!isShowing) return

        try {
//            VoiceAssistant.unregister(this)
            windowManager?.removeView(this)
            isShowing = false
            AppLog.put("语音助手浮动窗口已隐藏")
        } catch (e: Exception) {
            AppLog.put("隐藏浮动窗口失败", e)
        }
    }

    /**
     * 创建浮动视图
     */
    private fun createFloatingView() {
        // 将布局充入当前自定义 View 自身
        if (childCount == 0) {
            layoutInflater.inflate(R.layout.view_voice_assistant, this, true)
        }

        val voiceButton = findViewById<FloatingActionButton>(R.id.btn_voice_assistant)

        // 禁用 FAB 的默认点击行为，完全自定义触摸处理
        voiceButton.isClickable = false
        voiceButton.isFocusable = false

        // 点击切换：直接调用 VoiceAssistant 单例的切换方法
        setOnClickListener {
            VoiceAssistant.toggle(context)
        }

        // 订阅录音状态事件，实时更新UI
        (context as? AppCompatActivity)?.let { owner ->
            eventObservable<Boolean>(EventBus.VOICE_RECORDING_STATE).observe(owner) { isRecording ->
                updateVoiceButtonState(isRecording)
            }
        }

        // 初始状态 - 根据当前服务状态
        updateVoiceButtonState(VoiceAssistant.isRecording())
    }

    /**
     * 更新语音按钮状态 - 只有两个状态：停止/录音
     */
    fun updateVoiceButtonState(isRecording: Boolean) {
        val voiceButton = findViewById<FloatingActionButton>(R.id.btn_voice_assistant)

        // 设置选中状态来控制背景颜色
        voiceButton?.isSelected = isRecording

        // 始终使用同一个图标，通过 tint 控制颜色
        voiceButton?.setImageResource(R.drawable.ic_mic)

        if (isRecording) {
            // 录音状态：红色背景，白色图标
            voiceButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FF4444")
            )
            voiceButton?.imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.WHITE
            )
        } else {
            // 停止状态：黑色背景，白色图标
            voiceButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#80000000")
            )
            voiceButton?.imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.WHITE
            )
        }
    }
}
