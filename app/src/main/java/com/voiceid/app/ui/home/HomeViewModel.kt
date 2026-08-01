package com.voiceid.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceid.app.data.model.ConversationSummary
import com.voiceid.app.di.AppContainer
import com.voiceid.app.notifications.NotificationsCenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val conversationRepository = AppContainer.conversationRepository
    private val presenceRepository = AppContainer.presenceRepository

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Root-cause fix: this used to be a one-time fetch computed at init and never updated
    // again. It's now a live pass-through of the app-wide NotificationsCenter (which stays
    // subscribed to realtime:notifications for the whole session — see AuthViewModel where
    // it's started), so the bottom-nav badge updates immediately, matching Web's
    // MobileBottomNav's unreadMessageCount sourced from the always-on NotificationContext.
    val unreadMessageCount: StateFlow<Int> = NotificationsCenter.unreadMessageCount

    init {
        viewModelScope.launch { presenceRepository.start(viewModelScope) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _conversations.value = conversationRepository.conversationSummaries(presenceRepository.onlineUsers.value)
            _isLoading.value = false
        }
    }
}
