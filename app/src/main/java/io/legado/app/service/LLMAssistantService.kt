package io.legado.app.service

import android.content.Intent
import android.util.Log
import com.iflytek.sparkchain.core.LLM
import com.iflytek.sparkchain.core.LLMCallbacks
import com.iflytek.sparkchain.core.LLMConfig
import com.iflytek.sparkchain.core.LLMError
import com.iflytek.sparkchain.core.LLMEvent
import com.iflytek.sparkchain.core.LLMFactory
import com.iflytek.sparkchain.core.LLMResult
import com.iflytek.sparkchain.core.Memory
import io.legado.app.base.BaseService
import io.legado.app.constant.IntentAction
import io.legado.app.model.LLMAssistant

class LLMAssistantService : BaseService(), LLMCallbacks {
    private val TAG = "LLM"

    private var userTag = 0

    private var sessionFinished = true

    private lateinit var llm: LLM

    override fun onCreate() {
        super.onCreate()
        initLLM()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.startLLMAssistant -> {
                    val msg = intent.getStringExtra("message")
                    runLLM(msg ?: "")
                }

                IntentAction.stopLLMAssistant -> {
                    stopLLM()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun initLLM() {
        //配置插件参数,关闭联网搜素
        val tools =
            "[{\"type\":\"web_search\",\"web_search\":{\"enable\":false,\"show_ref_label\":false,\"search_mode\":\"normal\"}}]"
        val llmConfig = LLMConfig.builder().domain("generalv3.5")
//            .tools(tools)
        val token_memory = Memory.tokenMemory(1024)
        llm = LLMFactory.textGeneration(llmConfig, token_memory)
//        llm.addSystemPrompt("你是一位专业的语文老师,回答不超过二十字");
        llm.registerLLMCallbacks(this)
    }

    private fun runLLM(msg: String) {
        if (msg.length < 1) {
            Log.e(TAG, "输入内容不能为空")
            return
        }
        if (!sessionFinished) {
            Log.e(TAG, "请等待当前对话完成")
            return
        }
        Log.d(
            TAG, "用户输入：" + msg
        )
        userTag++
        val ret = llm.arun(msg, userTag)
        if (ret != 0) {
            Log.e(
                TAG, "SparkChain failed:\n" + ret
            )
            return
        }
        sessionFinished = false
    }

    private fun stopLLM() {
        llm.stop()
    }

    override fun onLLMResult(llmResult: LLMResult, o: Any?) {
        if (userTag == o as Int) {
            //本次返回的结果是否跟请求的问题是否匹配，通过用户自定义标识判断。
            //解析获取的交互结果，示例展示所有结果获取，开发者可根据自身需要，选择获取。
            val content = llmResult.getContent() //获取交互结果
            val status = llmResult.getStatus() //返回结果状态
            val role = llmResult.getRole() //获取角色信息
            val sid = llmResult.getSid() //本次交互的sid
            val rawResult = llmResult.getRaw() //星火大模型原始输出结果。要求SDK1.1.5版本以后才能使用
            val completionTokens = llmResult.getCompletionTokens() //获取回答的Token大小
            val promptTokens = llmResult.getPromptTokens() //包含历史问题的总Tokens大小
            val totalTokens =
                llmResult.getTotalTokens() //promptTokens和completionTokens的和，也是本次交互计费的Tokens大小

            Log.d(TAG, "onLLMResult\n")
            Log.d(TAG, "onLLMResult sid:" + sid)
            Log.e(TAG, "onLLMResult:" + content)
            Log.e(TAG, "onLLMResultRaw:" + rawResult)

            if (status == 2) { //2表示大模型结果返回完成
                Log.e(
                    TAG,
                    "completionTokens:" + completionTokens + "promptTokens:" + promptTokens + "totalTokens:" + totalTokens
                )
                sessionFinished = true
            }
            LLMAssistant.onLLMResult(content, status)
        }
    }

    override fun onLLMEvent(event: LLMEvent, o: Any?) {
        Log.d(TAG, "onLLMEvent\n")
        val eventId = event.getEventID() //获取事件ID
        val eventMsg = event.getEventMsg() //获取事件信息
        val sid = event.getSid() //本次交互的sid
        Log.w(TAG, "onLLMEvent:" + " " + eventId + " " + eventMsg)
    }

    override fun onLLMError(error: LLMError, o: Any?) {
        Log.d(TAG, "onLLMError\n")
        val errCode: Int = error.getErrCode() //返回错误码
        val errMsg: String? = error.getErrMsg() //获取错误信息
        val sid: String? = error.getSid() //本次交互的sid

        Log.d(TAG, "onLLMError sid:" + sid)
        Log.e(TAG, "errCode:" + errCode + "errDesc:" + errMsg)
        sessionFinished = true
    }
}