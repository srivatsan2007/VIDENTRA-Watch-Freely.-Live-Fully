/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arslandaim.playtube.data.local.PreferencesManager
import com.arslandaim.playtube.utils.ConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject
import com.arslandaim.playtube.BuildConfig

@HiltViewModel
class MainViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val connectivityObserver: ConnectivityObserver,
    private val libraryRepository: com.arslandaim.playtube.domain.repository.LibraryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val localPlaylists: StateFlow<List<com.arslandaim.playtube.data.local.LocalPlaylistEntity>> = libraryRepository.getLocalPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _playlistSelectionState = MutableStateFlow(PlaylistSelectionState())
    val playlistSelectionState = _playlistSelectionState.asStateFlow()

    val connectivityStatus: StateFlow<ConnectivityObserver.Status> = connectivityObserver.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectivityObserver.Status.Available)

    val isOffline: StateFlow<Boolean> = connectivityStatus
        .map { it == ConnectivityObserver.Status.Lost || it == ConnectivityObserver.Status.Unavailable }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isPipEnabled: StateFlow<Boolean> = preferencesManager.isPipEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isBackgroundPlayEnabled: StateFlow<Boolean> = preferencesManager.isBackgroundPlayEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isIncognitoMode: StateFlow<Boolean> = preferencesManager.isIncognitoMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isDynamicColorEnabled: StateFlow<Boolean> = preferencesManager.isDynamicColorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isOnboardingCompleted: StateFlow<Boolean?> = preferencesManager.isOnboardingCompleted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isYouTubeLoggedIn: StateFlow<Boolean> = preferencesManager.isYouTubeLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isGuestBypass: StateFlow<Boolean> = preferencesManager.isGuestBypass
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val youtubeUserName: StateFlow<String?> = preferencesManager.youtubeUserName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val youtubeUserEmail: StateFlow<String?> = preferencesManager.youtubeUserEmail
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val youtubeUserAvatar: StateFlow<String?> = preferencesManager.youtubeUserAvatar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _showSignInRequiredDialog = MutableStateFlow(false)
    val showSignInRequiredDialog: StateFlow<Boolean> = _showSignInRequiredDialog.asStateFlow()

    private val _showAuthSheet = MutableStateFlow(false)
    val showAuthSheet: StateFlow<Boolean> = _showAuthSheet.asStateFlow()

    private val _pendingVideo = MutableStateFlow<com.arslandaim.playtube.domain.model.VideoItem?>(null)
    val pendingVideo: StateFlow<com.arslandaim.playtube.domain.model.VideoItem?> = _pendingVideo.asStateFlow()

    fun toggleIncognitoMode() {
        viewModelScope.launch {
            val current = preferencesManager.isIncognitoMode.first()
            preferencesManager.setIncognitoMode(!current)
        }
    }

    private val _showOfflineDialog = MutableStateFlow(false)
    val showOfflineDialog: StateFlow<Boolean> = _showOfflineDialog.asStateFlow()

    private val _showWelcomeDialog = MutableStateFlow(false)
    val showWelcomeDialog: StateFlow<Boolean> = _showWelcomeDialog.asStateFlow()

    private val _showBirthdayDialog = MutableStateFlow(false)
    val showBirthdayDialog: StateFlow<Boolean> = _showBirthdayDialog.asStateFlow()

    private val _isUpdateDialogDismissed = MutableStateFlow(false)
    val isUpdateDialogDismissed: StateFlow<Boolean> = _isUpdateDialogDismissed.asStateFlow()

    init {
        viewModelScope.launch {
            // Check offline status on startup to trigger dialog
            val initialStatus = connectivityStatus.first()
            if (initialStatus == ConnectivityObserver.Status.Lost || initialStatus == ConnectivityObserver.Status.Unavailable) {
                _showOfflineDialog.value = true
            }

            // Check if first-time user or updated version to show welcome dialog
            val hasSeenWelcome = preferencesManager.hasSeenWelcomeDialog.first()
            val lastSeenWelcomeVersion = preferencesManager.lastSeenWelcomeVersion.first()
            val currentVersionCode = BuildConfig.VERSION_CODE
            if (!hasSeenWelcome || lastSeenWelcomeVersion < currentVersionCode) {
                _showWelcomeDialog.value = true
            }

            // Check if today is Developer's Birthday (December 10th)
            val calendar = java.util.Calendar.getInstance()
            val isBirthday = calendar.get(java.util.Calendar.MONTH) == java.util.Calendar.DECEMBER &&
                    calendar.get(java.util.Calendar.DAY_OF_MONTH) == 10
            val currentYear = calendar.get(java.util.Calendar.YEAR)
            val lastCelebratedYear = preferencesManager.lastBirthdayCelebrationYear.first()

            if (isBirthday && lastCelebratedYear < currentYear) {
                _showBirthdayDialog.value = true
            }
        }
    }

    fun dismissOfflineDialog() {
        _showOfflineDialog.value = false
    }

    fun dismissWelcomeDialog() {
        viewModelScope.launch {
            preferencesManager.setHasSeenWelcomeDialog(true)
            preferencesManager.setLastSeenWelcomeVersion(BuildConfig.VERSION_CODE)
            preferencesManager.setLastAppVersion(BuildConfig.VERSION_CODE)
            _showWelcomeDialog.value = false
        }
    }

    fun dismissUpdateDialog() {
        _isUpdateDialogDismissed.value = true
    }

    fun dismissBirthdayDialog() {
        viewModelScope.launch {
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            preferencesManager.setLastBirthdayCelebrationYear(currentYear)
            _showBirthdayDialog.value = false
        }
    }

    fun triggerBirthdayDialogForPreview() {
        _showBirthdayDialog.value = true
    }

    fun setBarsVisibility(visible: Boolean) {
        _uiState.update { it.copy(isBarsVisible = visible) }
    }

    fun setPipMode(enabled: Boolean) {
        _uiState.update { it.copy(isInPipMode = enabled) }
    }

    fun setPlayerScreen(isPlayer: Boolean) {
        _uiState.update { it.copy(isPlayerScreen = isPlayer) }
    }

    private var playlistSelectionJob: Job? = null

    fun showPlaylistSelection(video: com.arslandaim.playtube.domain.model.VideoItem) {
        playlistSelectionJob?.cancel()
        playlistSelectionJob = viewModelScope.launch {
            libraryRepository.getPlaylistsContainingVideo(video.id).collectLatest { playlists ->
                _playlistSelectionState.value = PlaylistSelectionState(
                    isVisible = true, 
                    video = video,
                    playlistsWithVideo = playlists.toSet()
                )
            }
        }
    }

    fun hidePlaylistSelection() {
        playlistSelectionJob?.cancel()
        playlistSelectionJob = null
        _playlistSelectionState.value = PlaylistSelectionState(isVisible = false, video = null)
    }

    fun addVideoToPlaylist(playlistId: Int, video: com.arslandaim.playtube.domain.model.VideoItem) {
        viewModelScope.launch {
            libraryRepository.addVideoToLocalPlaylist(playlistId, video)
            hidePlaylistSelection()
        }
    }

    fun createLocalPlaylist(name: String) {
        viewModelScope.launch {
            val id = libraryRepository.createLocalPlaylist(name)
            _playlistSelectionState.value.video?.let { video ->
                addVideoToPlaylist(id.toInt(), video)
            }
        }
    }

    fun handleVideoClick(
        video: com.arslandaim.playtube.domain.model.VideoItem,
        onAuthorizedPlay: (com.arslandaim.playtube.domain.model.VideoItem) -> Unit
    ) {
        val isLoggedIn = isYouTubeLoggedIn.value
        val isGuest = isGuestBypass.value

        if (isLoggedIn || isGuest) {
            onAuthorizedPlay(video)
        } else {
            _pendingVideo.value = video
            _showSignInRequiredDialog.value = true
        }
    }

    fun onContinueAsGuest(onAuthorizedPlay: (com.arslandaim.playtube.domain.model.VideoItem) -> Unit) {
        viewModelScope.launch {
            preferencesManager.setGuestBypass(true)
            _showSignInRequiredDialog.value = false
            _pendingVideo.value?.let { video ->
                onAuthorizedPlay(video)
                _pendingVideo.value = null
            }
        }
    }

    fun onOpenAuthSheet() {
        _showSignInRequiredDialog.value = false
        _showAuthSheet.value = true
    }

    fun dismissSignInRequiredDialog() {
        _showSignInRequiredDialog.value = false
        _pendingVideo.value = null
    }

    fun dismissAuthSheet() {
        _showAuthSheet.value = false
    }

    fun onAuthSuccess(
        cookies: String,
        userName: String?,
        userEmail: String?,
        avatarUrl: String?,
        onAuthorizedPlay: (com.arslandaim.playtube.domain.model.VideoItem) -> Unit
    ) {
        viewModelScope.launch {
            preferencesManager.saveYouTubeAccount(cookies, userName, userEmail, avatarUrl)
            _showAuthSheet.value = false
            _pendingVideo.value?.let { video ->
                onAuthorizedPlay(video)
                _pendingVideo.value = null
            }
        }
    }

    fun signOutYouTube() {
        viewModelScope.launch {
            preferencesManager.clearYouTubeAccount()
            preferencesManager.setGuestBypass(false)
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
        }
    }
}

data class MainUiState(
    val isBarsVisible: Boolean = true,
    val isInPipMode: Boolean = false,
    val isPlayerScreen: Boolean = false
)

data class PlaylistSelectionState(
    val isVisible: Boolean = false,
    val video: com.arslandaim.playtube.domain.model.VideoItem? = null,
    val playlistsWithVideo: Set<Int> = emptySet()
)
