package com.voiceid.app.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceid.app.data.model.Message
import com.voiceid.app.data.remote.SupabaseModule
import com.voiceid.app.di.AppContainer
import com.voiceid.app.media.MediaCache
import com.voiceid.app.notifications.NotificationsCenter
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel(context: Context) : ViewModel() {

    private val messageRepository = AppContainer.messageRepository
    private val notificationRepository = AppContainer.notificationRepository
    private val mediaCache = MediaCache(context)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var conversationId: String? = null

    val selfUserId: String? get() = SupabaseModule.currentUserId()

    fun open(conversationId: String) {
        this.conversationId = conversationId
        // Mirrors Web's ChatPage calling setActiveConversationId(conversationId) — lets
        // NotificationsCenter mark a "message" notification for THIS conversation as read
        // immediately if it arrives while the user is already looking at it, instead of
        // showing an unread badge for something they can already see on screen.
        NotificationsCenter.activeConversationId = conversationId
        viewModelScope.launch {
            _messages.value = messageRepository.history(conversationId)
            notificationRepository.markConversationRead(conversationId)
        }
        // Subscribe to messages:{conversationId} — API_REFERENCE.md §4.
        viewModelScope.launch {
            messageRepository.realtimeMessages(conversationId).collect { action ->
                when (action) {
                    is PostgresAction.Insert -> {
                        val msg = action.decodeRecord<Message>()
                        if (_messages.value.none { it.id == msg.id }) {
                            _messages.value = _messages.value + msg
                        }
                    }
                    is PostgresAction.Update -> {
                        val msg = action.decodeRecord<Message>()
                        _messages.value = _messages.value.map { if (it.id == msg.id) msg else it }
                    }
                    is PostgresAction.Delete -> {
                        val oldRecord = action.oldRecord
                        val id = oldRecord["id"]?.toString()?.trim('"')
                        if (id != null) _messages.value = _messages.value.filterNot { it.id == id }
                    }
                    else -> {}
                }
            }
        }
    }

    fun sendText(conversationId: String, body: String) {
        if (body.isBlank()) return
        viewModelScope.launch {
            _isSending.value = true
            try {
                messageRepository.sendText(conversationId, body.trim())
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isSending.value = false
            }
        }
    }

    fun sendVoice(conversationId: String, file: File, durationSeconds: Int, mimeType: String = "audio/mp4") {
        viewModelScope.launch {
            _isSending.value = true
            try {
                val messageId = messageRepository.sendVoice(conversationId, file, durationSeconds, mimeType)
                // Cache immediately: this is the exact file we just uploaded, so there's no
                // need to ever re-download our own sent voice message from B2 to play it back.
                mediaCache.putFile(messageId, file)
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isSending.value = false
            }
        }
    }

    fun sendImage(conversationId: String, file: File, mimeType: String = "image/jpeg") {
        viewModelScope.launch {
            _isSending.value = true
            try {
                val messageId = messageRepository.sendImage(conversationId, file, mimeType)
                mediaCache.putFile(messageId, file)
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isSending.value = false
            }
        }
    }

    /** Recipient-side download-and-cache, mirroring mediaDownload.ts::fetchAndCacheMedia (§7.3). */
    fun mediaFileFor(message: Message, onReady: (File) -> Unit, onError: (String) -> Unit = {}) {
        mediaCache.get(message.id)?.let { onReady(it); return }
        viewModelScope.launch {
            try {
                val bytes = messageRepository.downloadMedia(message.id)
                val file = mediaCache.put(message.id, bytes)
                onReady(file)
            } catch (e: com.voiceid.app.data.remote.MediaApiException) {
                // A 404 here means the object itself is gone from storage — by design this
                // backend deletes each media file from B2 the moment ANY recipient device
                // successfully downloads it once (api/ack.ts). If that already happened on
                // another device/install, or this device's own local copy was lost, there is
                // no way to recover the file — so say that plainly instead of an opaque
                // "Media API error (404)".
                val message2 = if (e.code == 404) {
                    "This media is no longer available — it may have already been viewed and removed."
                } else {
                    "Could not load media: ${e.message ?: e.javaClass.simpleName}"
                }
                _errorMessage.value = message2
                onError(message2)
            } catch (e: Exception) {
                val message2 = "Could not load media: ${e.message ?: e.javaClass.simpleName}"
                _errorMessage.value = message2
                onError(message2)
            }
        }
    }

    fun deleteMessage(message: Message) {
        viewModelScope.launch {
            try {
                messageRepository.deleteMessage(message)
                mediaCache.remove(message.id)
                _messages.value = _messages.value.filterNot { it.id == message.id }
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        if (NotificationsCenter.activeConversationId == conversationId) {
            NotificationsCenter.activeConversationId = null
        }
    }
}
