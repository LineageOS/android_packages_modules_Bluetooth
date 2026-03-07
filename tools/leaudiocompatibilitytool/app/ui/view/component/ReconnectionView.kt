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

import android.bluetooth.tools.leaudiocompatibilitytool.app.viewmodel.ReconnectionViewModel
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
fun ReconnectionView() {
    val viewModel: ReconnectionViewModel = hiltViewModel()
    val progressText by viewModel.progressString.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.initReconnectionTest()
        onDispose { viewModel.resetAndSaveReconnectionTest() }
    }

    TitleText(ReconnectionViewModel.TEST_ITEM_NAME)
    Column(modifier = ViewTheme.ScrollableColumnStyle) {
        DescriptionText(
            "Make sure the headset is paired and currently disconnected. ${ReconnectionViewModel.OPEN_CASE_STRING}"
        )
        ActionButton(
            onClickHandler = { viewModel.startReconnectionTest() },
            text = "Start Testing",
            enabled = viewModel.isReconnectBtnEnabled,
        )
    }
    DescriptionText(progressText)
    LogConsole(viewModel.logList)
}
