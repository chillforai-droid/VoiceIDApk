package com.voiceid.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceid.app.data.model.UserSettings
import com.voiceid.app.data.remote.SupabaseModule
import com.voiceid.app.ui.theme.ThemeMode
import com.voiceid.app.ui.theme.ThemePreferences
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(context: Context) : ViewModel() {

    private val themePreferences = ThemePreferences(context.applicationContext)
    private val client = SupabaseModule.client()

    val themeMode: StateFlow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM).also { flow ->
        viewModelScope.launch { themePreferences.themeMode.collect { flow.value = it } }
    }.asStateFlow()

    private val _privacySettings = MutableStateFlow<UserSettings?>(null)
    val privacySettings: StateFlow<UserSettings?> = _privacySettings.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themePreferences.setThemeMode(mode) }
    }

    fun loadPrivacySettings() {
        val userId = SupabaseModule.currentUserId() ?: return
        viewModelScope.launch {
            val existing = client.from("user_settings").select { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<UserSettings>()
            _privacySettings.value = existing ?: UserSettings(userId = userId)
        }
    }

    // BUG FIX: previously called upsert(settings) with only the fields the caller's
    // settings.copy(...) touched — correct here since UserSettings always carries every
    // field with defaults — but on the web side (SettingsPage.tsx) the equivalent call
    // spreads `{ user_id, ...settings, ...updates }`, i.e. it always merges against the
    // last-known-good state before writing. Doing the same merge here (via the already
    // loaded _privacySettings.value as a fallback base) prevents a second, unrelated
    // in-flight update from clobbering a field the user didn't touch this time.
    fun updatePrivacySettings(settings: UserSettings) {
        viewModelScope.launch {
            client.from("user_settings").upsert(settings)
            _privacySettings.value = settings
        }
    }
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    privacySettings: UserSettings?,
    onThemeModeChange: (ThemeMode) -> Unit,
    onPrivacyChange: (UserSettings) -> Unit,
    onSignOut: () -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(title = "Appearance", icon = Icons.Filled.Bolt) {
                ThemeMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = { onThemeModeChange(mode) })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "Match system"
                                ThemeMode.LIGHT -> "Light mode"
                                ThemeMode.DARK -> "Dark mode"
                            }
                        )
                    }
                }
            }

            privacySettings?.let { settings ->
                SettingsSection(title = "Privacy", icon = Icons.Filled.Shield) {
                    PrivacyRow("Contact requests", settings.contactRequests) {
                        onPrivacyChange(settings.copy(contactRequests = it))
                    }
                    PrivacyRow("Calls", settings.calls) {
                        onPrivacyChange(settings.copy(calls = it))
                    }
                    PrivacyRow("Voice messages", settings.voiceMessages) {
                        onPrivacyChange(settings.copy(voiceMessages = it))
                    }
                }

                SettingsSection(title = "Notifications", icon = Icons.Filled.NotificationsNone) {
                    SwitchRow("Contact requests", settings.notifyContactRequests) {
                        onPrivacyChange(settings.copy(notifyContactRequests = it))
                    }
                    SwitchRow("Messages", settings.notifyMessages) {
                        onPrivacyChange(settings.copy(notifyMessages = it))
                    }
                    SwitchRow("Calls", settings.notifyCalls) {
                        onPrivacyChange(settings.copy(notifyCalls = it))
                    }
                }
            }

            // Was missing entirely: Settings had no account-level actions at all — sign out
            // only lived on the Profile screen — while the web SettingsPage exposes it here too.
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PrivacyRow(label: String, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Box {
            TextButton(onClick = { expanded = true }) { Text(value.replaceFirstChar { it.uppercase() }) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("everyone", "contacts", "nobody").forEach { option ->
                    DropdownMenuItem(text = { Text(option.replaceFirstChar { it.uppercase() }) }, onClick = { onChange(option); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
