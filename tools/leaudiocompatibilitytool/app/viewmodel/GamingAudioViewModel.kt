/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.bluetooth.tools.leaudiocompatibilitytool.app.viewmodel

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestItemUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.utils.TestStateManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class GamingAudioViewModel @Inject constructor(private val testStateManager: TestStateManager) :
    ViewModel() {

    /**
     * A StateFlow that emits the current testing progress as a string. This will automatically
     * update the UI when collected as state.
     */
    val progressString: StateFlow<String> =
        testStateManager.finishedRuns
            .map { runsMap: Map<Int, Int> ->
                val finishedRuns = runsMap[TEST_ITEM_ID] ?: 0
                "Runs: $finishedRuns/${RUNS_REQUIRED}"
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = "Runs: 0/${RUNS_REQUIRED}",
            )

    companion object {
        val TEST_ITEM_ID = TestItemUiModel.GAMING_AUDIO.testItemId
        val TEST_ITEM_NAME = TestItemUiModel.GAMING_AUDIO.name
        val RUNS_REQUIRED = TestItemUiModel.GAMING_AUDIO.qualification.runsRequired
        val TAG = "${TEST_ITEM_NAME} Testing"
    }
}
