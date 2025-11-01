package io.legado.app.ui.assistant

import android.content.Context
import android.view.ViewGroup
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemChatAssistantBinding
import io.legado.app.databinding.ItemChatUserBinding

class ChatAdapter(context: Context) :
    RecyclerAdapter<ChatMessage, ViewBinding>(context) {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
    }

    override fun getItemViewType(item: ChatMessage, position: Int): Int {
        return if (item.isUser) TYPE_USER else TYPE_ASSISTANT
    }

    override fun getViewBinding(parent: ViewGroup): ViewBinding {
        throw IllegalStateException("Use onCreateViewHolder instead")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        return when (viewType) {
            TYPE_USER -> ItemViewHolder(
                ItemChatUserBinding.inflate(inflater, parent, false)
            )

            TYPE_ASSISTANT -> ItemViewHolder(
                ItemChatAssistantBinding.inflate(inflater, parent, false)
            )

            else -> throw IllegalArgumentException("Invalid view type, $viewType")
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ViewBinding,
        item: ChatMessage,
        payloads: MutableList<Any>
    ) {
        when (binding) {
            is ItemChatUserBinding -> {
                binding.tvMessage.text = item.content
            }

            is ItemChatAssistantBinding -> {
                binding.tvMessage.text = item.content
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ViewBinding) {

    }
}