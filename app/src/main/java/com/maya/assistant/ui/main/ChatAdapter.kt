package com.maya.assistant.ui.main

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.maya.assistant.R

/**
 * Chat message data class.
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

/**
 * Simple chat adapter for conversation display.
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val layoutId = if (viewType == 1) R.layout.item_chat_user else R.layout.item_chat_bot
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount() = messages.size

    override fun getItemViewType(position: Int) = if (messages[position].isUser) 1 else 0

    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView? = itemView.findViewById(R.id.chatMessageText)

        fun bind(msg: ChatMessage) {
            textView?.text = msg.text
        }
    }
}
