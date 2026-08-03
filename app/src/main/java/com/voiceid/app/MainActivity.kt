package com.voiceid.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.voiceid.app.call.PendingCallAction
import com.voiceid.app.call.PendingCallActionHolder
import com.voiceid.app.ui.auth.AuthUiState
import com.voiceid.app.ui.auth.AuthViewModel
import com.voiceid.app.ui.navigation.AuthNavGraph
import com.voiceid.app.ui.navigation.MainNavGraph
import com.voiceid.app.ui.theme.ThemeMode
import com.voiceid.app.ui.theme.ThemePreferences
import com.voiceid.app.ui.theme.VoiceIdTheme

class MainActivity : ComponentActivity() {

    // Activity-scoped (not created inside the Composable) specifically so onCreate/onNewIntent
    // can hand it an incoming deep link — e.g. the com.voiceid.app://auth-callback link tapped
    // from an email-confirmation email — using the SAME instance the UI observes, rather than a
    // separate one that would silently miss the intent.
    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        authViewModel.handleAuthDeeplink(intent)
        handlePendingCallIntent(intent)

        setContent {
            val themePreferences = remember { ThemePreferences(applicationContext) }
            val themeMode by themePreferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

            VoiceIdTheme(themeMode = themeMode) {
                VoiceIdApp(authViewModel)
            }
        }
    }

    // MainActivity is launchMode="singleTask" (AndroidManifest.xml), so a deep link tapped
    // while the app is already running arrives here instead of a new onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        authViewModel.handleAuthDeeplink(intent)
        handlePendingCallIntent(intent)
    }

    // Set by CallActionReceiver when the user taps "Answer" on the incoming-call
    // notification — see PendingCallActionHolder for why this hand-off exists.
    private fun handlePendingCallIntent(intent: Intent) {
        val action = intent.getStringExtra(EXTRA_PENDING_CALL_ACTION) ?: return
        val callId = intent.getStringExtra(EXTRA_PENDING_CALL_ID) ?: return
        PendingCallActionHolder.set(PendingCallAction(action, callId))
    }

    companion object {
        const val EXTRA_PENDING_CALL_ACTION = "pending_call_action"
        const val EXTRA_PENDING_CALL_ID = "pending_call_id"
        const val CALL_ACTION_ACCEPT = PendingCallAction.ACCEPT
        const val CALL_ACTION_REJECT = PendingCallAction.REJECT
    }
}

@Composable
private fun VoiceIdApp(authViewModel: AuthViewModel) {
    val uiState by authViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { authViewModel.checkExistingSession() }

    // Root-cause fix: this used to seed `isAuthenticated` from a synchronous
    // SupabaseModule.currentUserId() read taken at first composition, before the Auth
    // plugin's async session-restore job (kicked off inside install(Auth) in
    // SupabaseModule) had necessarily finished loading any persisted session from disk.
    // That raced session restoration exactly like the sign-up/claim race documented in
    // AuthRepository.awaitAuthenticatedSession. Driving navigation purely off `uiState` —
    // which checkExistingSession() only advances past CheckingSession once
    // awaitSessionResolved() confirms the restore job is done — removes the race instead
    // of papering over it with a default value.
    when (uiState) {
        is AuthUiState.CheckingSession -> {
            // Session restoration still in flight; the system splash screen is expected to
            // still be showing here. Render nothing until we actually know the answer.
        }
        is AuthUiState.Ready -> {
            MainNavGraph(authViewModel = authViewModel, onSignedOut = {})
        }
        else -> {
            AuthNavGraph(authViewModel = authViewModel, onAuthenticated = {})
        }
    }
}
