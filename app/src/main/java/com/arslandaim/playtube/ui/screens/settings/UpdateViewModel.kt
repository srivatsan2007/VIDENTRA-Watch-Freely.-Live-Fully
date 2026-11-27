/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arslandaim.playtube.data.local.PreferencesManager
import com.arslandaim.playtube.domain.repository.UpdateInfo
import com.arslandaim.playtube.domain.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    val updateInfo: StateFlow<UpdateInfo> = updateRepository.updateInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UpdateInfo())

    val isAutoUpdateEnabled: StateFlow<Boolean> = preferencesManager.isAutoUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    init {
        viewModelScope.launch {
            isAutoUpdateEnabled.collectLatest { enabled ->
                if (enabled) {
                    checkForUpdates()
                }
            }
        }
    }

    fun checkForUpdates() {
        if (_isChecking.value) return
        viewModelScope.launch {
            _isChecking.value = true
            try {
                updateRepository.checkForUpdates()
            } finally {
                _isChecking.value = false
            }
        }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setAutoUpdateEnabled(enabled)
            if (enabled) {
                checkForUpdates()
            }
        }
    }
}
