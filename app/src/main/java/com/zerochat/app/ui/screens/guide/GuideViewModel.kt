package com.zerochat.app.ui.screens.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.app.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GuideViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val completedSteps: StateFlow<Set<String>> = settingsRepository.completedGuideSteps
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    fun markStepComplete(stepId: String) {
        viewModelScope.launch {
            settingsRepository.markGuideStepComplete(stepId)
        }
    }

    fun resetGuide() {
        viewModelScope.launch {
            settingsRepository.resetGuideSteps()
        }
    }
}
