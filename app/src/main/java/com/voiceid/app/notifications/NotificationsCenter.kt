package com.voiceid.app.notifications

import android.util.Log
import com.voiceid.app.data.model.AppNotification
import com.voiceid.app.di.AppContainer
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "VoiceID/Notifications"

/**
 * App-wide reactive notification state — the Android analogue of Web's NotificationContext
 * (src/context/NotificationContext.tsx). Web mounts that provider once at the top of the
 * app and stays subscribed to realtime:notifications for the whole session, so every
 * screen's badge stays live. Android's equivalent previously only subscribed inside
 * NotificationsScreen's own ViewModel — meaning badges never updated anywhere else in the
 * app, and there was nothing to drive the "already viewing this conversation -> mark read
 * immediately" optimization Web also has. This object is that missing always-on listener.
 *
 * Lifecycle: AuthViewModel calls start(userId) once uiState reaches Ready (covers both a
 * fresh sign-in and cold-start session restoration), and stop() on sign-out.
 */
object NotificationsCenter {
    private val notificationRepository get() = AppContainer.notificationRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    /** Mirrors Web's MobileBottomNav "Notifications" badge (unreadCount). */
    val unreadCount: StateFlow<Int> = _notifications
        .map { list -> list.count { !it.isRead } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Mirrors Web's MobileBottomNav "Messages" badge (unreadMessageCount). */
    val unreadMessageCount: StateFlow<Int> = _notifications
        .map { list -> list.count { it.type == "message" && !it.isRead } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * Set by ChatViewModel.open()/onCleared() — the Android equivalent of Web's
     * activeConversationIdRef, used so a "message" notification for a conversation the user
     * is already looking at gets marked read immediately instead of showing as unread.
     */
    @Volatile
    var activeConversationId: String? = null

    fun start(userId: String) {
        if (job != null) return // already running — re-entrant Ready transitions are harmless no-ops
        job = scope.launch {
            _notifications.value = try {
                notificationRepository.latest()
            } catch (e: Exception) {
                Log.e(TAG, "start: initial fetch failed", e)
                emptyList()
            }

            try {
                notificationRepository.realtimeNotifications(userId).collect { action ->
                    if (action is PostgresAction.Insert) {
                        val notif = action.decodeRecord<AppNotification>()
                        if (notif.type == "message" && notif.relatedId != null && notif.relatedId == activeConversationId) {
                            launch {
                                try {
                                    notificationRepository.markConversationRead(notif.relatedId)
                                } catch (e: Exception) {
                                    Log.e(TAG, "start: markConversationRead for active conversation failed", e)
                                }
                            }
                            _notifications.value = listOf(notif.copy(isRead = true)) + _notifications.value
                        } else {
                            _notifications.value = listOf(notif) + _notifications.value
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "start: realtime subscription ended unexpectedly", e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _notifications.value = emptyList()
        activeConversationId = null
    }

    // The following let existing screen-level ViewModels (NotificationsScreen etc.) keep
    // performing their own mutations through NotificationRepository as before, while
    // pushing the result into this shared source of truth so every other screen's badge
    // reflects it immediately too — instead of every screen needing its own network refetch.
    fun applyMarkRead(notificationId: String) {
        _notifications.value = _notifications.value.map { if (it.id == notificationId) it.copy(isRead = true) else it }
    }

    fun applyMarkAllRead() {
        _notifications.value = _notifications.value.map { it.copy(isRead = true) }
    }

    fun applyRemoved(notificationId: String) {
        _notifications.value = _notifications.value.filterNot { it.id == notificationId }
    }

    fun applyClearAll() {
        _notifications.value = emptyList()
    }

    fun applyFetchedMore(older: List<AppNotification>) {
        _notifications.value = _notifications.value + older
    }
}
