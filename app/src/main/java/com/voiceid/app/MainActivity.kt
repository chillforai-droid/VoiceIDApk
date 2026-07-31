package com.voiceid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceid.app.data.remote.SupabaseModule
import com.voiceid.app.ui.auth.AuthUiState
import com.voiceid.app.ui.auth.AuthViewModel
import com.voiceid.app.ui.navigation.AuthNavGraph
import com.voiceid.app.ui.navigation.MainNavGraph
import com.voiceid.app.ui.theme.ThemeMode
import com.voiceid.app.ui.theme.ThemePreferences
import com.voiceid.app.ui.theme.VoiceIdTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themePreferences = remember { ThemePreferences(applicationContext) }
            val themeMode by themePreferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

            VoiceIdTheme(themeMode = themeMode) {
                VoiceIdApp()
            }
        }
    }
}

@Composable
private fun VoiceIdApp() {
    val authViewModel: AuthViewModel = viewModel()
    val uiState by authViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { authViewModel.checkExistingSession() }

    var isAuthenticated by remember { mutableStateOf(SupabaseModule.currentUserId() != null && uiState is AuthUiState.Ready) }

    LaunchedEffect(uiState) {
        if (uiState is AuthUiState.Ready) isAuthenticated = true
    }

    if (isAuthenticated) {
        MainNavGraph(authViewModel = authViewModel, onSignedOut = { isAuthenticated = false })
    } else {
        AuthNavGraph(authViewModel = authViewModel, onAuthenticated = { isAuthenticated = true })
    }
}
