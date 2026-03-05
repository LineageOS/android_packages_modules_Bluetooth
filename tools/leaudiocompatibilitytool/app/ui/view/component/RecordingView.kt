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
package android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component

import android.bluetooth.tools.leaudiocompatibilitytool.app.viewmodel.RecordingViewModel
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
@Composable
fun RecordingView() {
    val viewModel: RecordingViewModel = hiltViewModel()
    val progressText by viewModel.progressString.collectAsStateWithLifecycle()
    val maxAmplitude by viewModel.maxAmplitude.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.initRecordingTest()
        onDispose { viewModel.resetAndSaveRecordingTest() }
    }

    TitleText(RecordingViewModel.TEST_ITEM_NAME)
    Column(modifier = ViewTheme.ScrollableColumnStyle) {
        DescriptionText("Connect your headset and put them on.")
        ActionButton(
            onClickHandler = { viewModel.startRecordingTest.run() },
            text = "Start Recording",
            enabled = viewModel.isRecordingBtnEnabled,
        )

        // Only show the current amplitude when the recording button is disabled.
        if (!viewModel.isRecordingBtnEnabled) {
            DescriptionText("Current amplitude: $maxAmplitude")
        }
    }
    DescriptionText(progressText)
    LogConsole(viewModel.logList)
}
