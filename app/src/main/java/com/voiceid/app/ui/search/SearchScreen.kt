package com.voiceid.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.voiceid.app.data.model.Profile
import com.voiceid.app.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    private val contactRepository = AppContainer.contactRepository

    private val _results = MutableStateFlow<List<Profile>>(emptyList())
    val results: StateFlow<List<Profile>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var debounceJob: Job? = null

    fun onQueryChanged(query: String) {
        debounceJob?.cancel()
        if (query.isBlank()) {
            _results.value = emptyList()
            return
        }
        debounceJob = viewModelScope.launch {
            delay(300)
            _isSearching.value = true
            _results.value = contactRepository.searchUsers(query)
            _isSearching.value = false
        }
    }

    fun sendFriendRequest(userId: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                contactRepository.sendFriendRequest(userId)
                onDone(Result.success(Unit))
            } catch (e: Exception) {
                onDone(Result.failure(e))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    results: List<Profile>,
    isSearching: Boolean,
    onQueryChanged: (String) -> Unit,
    onSendRequest: (Profile) -> Unit,
    onOpenProfile: (Profile) -> Unit
) {
    var query by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("Search") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; onQueryChanged(it) },
                placeholder = { Text("Search by @username or name") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            when {
                isSearching -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                results.isEmpty() && query.isNotBlank() -> Text("No users found.")
                else -> LazyColumn {
                    items(results, key = { it.id }) { profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenProfile(profile) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = profile.avatarUrl, contentDescription = null,
                                modifier = Modifier.size(44.dp).clip(CircleShape)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.displayName ?: "@${profile.username}")
                                Text("@${profile.username}", style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { onSendRequest(profile) }) {
                                Icon(Icons.Filled.PersonAdd, contentDescription = "Add contact")
                            }
                        }
                    }
                }
            }
        }
    }
}
