package com.voiceid.app.ui.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.voiceid.app.BuildConfig
import com.voiceid.app.data.model.Profile
import com.voiceid.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Error(val message: String) : AuthUiState()
    object AwaitingOnboarding : AuthUiState()
    object Ready : AuthUiState()
}

class AuthViewModel : ViewModel() {

    private val authRepository = AppContainer.authRepository

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _ownProfile = MutableStateFlow<Profile?>(null)
    val ownProfile: StateFlow<Profile?> = _ownProfile.asStateFlow()

    /** Native Google sign-in via Credential Manager (BACKEND_README §2.1 / AI_HANDOFF §6.1). */
    fun signInWithGoogle(activity: Activity) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                val rawNonce = UUID.randomUUID().toString()
                val hashedNonce = MessageDigest.getInstance("SHA-256")
                    .digest(rawNonce.toByteArray())
                    .joinToString("") { "%02x".format(it) }

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                    .setNonce(hashedNonce)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val credentialManager = CredentialManager.create(activity)
                val result = credentialManager.getCredential(activity, request)

                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    authRepository.signInWithGoogleIdToken(googleIdTokenCredential.idToken, rawNonce)
                    afterSignIn()
                } else {
                    _uiState.value = AuthUiState.Error("Unexpected credential type")
                }
            } catch (e: GoogleIdTokenParsingException) {
                _uiState.value = AuthUiState.Error("Google sign-in failed: ${e.message}")
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.message ?: "Google sign-in failed")
            }
        }
    }

    fun signUp(email: String, password: String, fullName: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                authRepository.signUp(email, password, fullName)
                afterSignIn()
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.message ?: "Sign up failed")
            }
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                authRepository.signInWithPassword(email, password)
                afterSignIn()
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.message ?: "Invalid email or password")
            }
        }
    }

    fun sendPasswordReset(email: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                authRepository.sendPasswordResetEmail(email)
                _uiState.value = AuthUiState.Idle
                onDone()
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.message ?: "Could not send reset email")
            }
        }
    }

    fun claimUsername(username: String, displayName: String?) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                authRepository.claimUsername(username, displayName)
                _ownProfile.value = authRepository.fetchOwnProfile()
                _uiState.value = AuthUiState.Ready
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.message ?: "Could not claim username")
            }
        }
    }

    /** Post-auth onboarding gate check — API_REFERENCE.md §2 / BACKEND_README.md §2.3. */
    private suspend fun afterSignIn() {
        val profile = authRepository.fetchOwnProfile()
        _ownProfile.value = profile
        _uiState.value = if (profile == null || profile.username.isNullOrBlank()) {
            AuthUiState.AwaitingOnboarding
        } else {
            AuthUiState.Ready
        }
    }

    fun checkExistingSession() {
        viewModelScope.launch {
            if (authRepository.currentUserId() != null) {
                afterSignIn()
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _ownProfile.value = null
            _uiState.value = AuthUiState.Idle
        }
    }
}
