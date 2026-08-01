package com.voiceid.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceid.app.data.model.AppNotification
import com.voiceid.app.data.repository.NotificationNav
import com.voiceid.app.di.AppContainer
import com.voiceid.app.notifications.NotificationsCenter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NotificationsViewModel : ViewModel() {
    private val notificationRepository = AppContainer.notificationRepository

    // Root-cause fix: this screen used to run its own separate fetch + realtime
    // subscription, entirely disconnected from the rest of the app (see NotificationsCenter
    // for the full explanation). It now just displays the shared, always-on list — opening
    // this screen no longer causes a second, redundant subscription.
    val notifications: StateFlow<List<AppNotification>> = NotificationsCenter.notifications

    fun markRead(notification: AppNotification) {
        viewModelScope.launch {
            notificationRepository.markRead(notification.id)
            NotificationsCenter.applyMarkRead(notification.id)
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            notificationRepository.markAllRead()
            NotificationsCenter.applyMarkAllRead()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            notificationRepository.clearAll()
            NotificationsCenter.applyClearAll()
        }
    }
}

@Composable
fun NotificationsScreen(
    notifications: List<AppNotification>,
    isLoading: Boolean,
    onNotificationClick: (AppNotification) -> Unit,
    onMarkAllRead: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                actions = { TextButton(onClick = onMarkAllRead) { Text("Mark all read") } }
            )
        }
    ) { padding ->
        when {
            isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            notifications.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("You're all caught up.")
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(notifications, key = { it.id }) { notif ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNotificationClick(notif) }
                            .background(if (!notif.isRead) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.background)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape)
                                .background(if (!notif.isRead) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                                .align(Alignment.CenterVertically)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(notif.title, style = MaterialTheme.typography.titleMedium)
                            Text(notif.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
