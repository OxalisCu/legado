package io.legado.app.ui.assistant

import android.os.Bundle
import android.os.Message
import android.util.Log
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.constant.EventBus
import io.legado.app.base.VMBaseActivity
import io.legado.app.model.ASRAssistant
import io.legado.app.utils.observeEventSticky
import io.legado.app.databinding.ActivityVoiceAssistantBinding

class VoiceAssistantActivity :
    VMBaseActivity<ActivityVoiceAssistantBinding, VoiceAssistantViewModel>(),
    ASRAssistant.Callback {

    override val binding by viewBinding(ActivityVoiceAssistantBinding::inflate)
    override val viewModel by viewModels<VoiceAssistantViewModel>()

    private val adapter by lazy {
        ChatAdapter(this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        ASRAssistant.register(this)
        viewModel.messages.observe(this) { messageList ->
            Log.d("VoiceAssistantActivity", "Messages updated: $messageList")
            adapter.setItems(messageList)
            if (adapter.itemCount > 0) {
                binding.recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        }
        viewModel.initData(intent)
        initView()
    }

    private fun initView() {
        // 设置 RecyclerView
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        adapter.bindToRecyclerView(binding.recyclerView)

        // support user text input
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString()
            if (text.isNotBlank()) {
                val message = ChatMessage(text, true)
                viewModel.addNewMessage(message)
                binding.etInput.setText("")
            }
        }
    }

    override fun observeLiveBus() {
//        observeEventSticky<Boolean>(EventBus.VOICE_RECORDING_STATE) {
//            binding.btnSend.isEnabled = !it
//        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ASRAssistant.unregister(this)
    }

    override fun onResult(result: String, status: Int) {
        // support user stt
        runOnUiThread {
            val message = ChatMessage(result, true)
            when (status) {
                0 -> viewModel.addNewMessage(message)
                else -> viewModel.editLastMessage(message)
            }
        }
    }
}