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
package android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestItemUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.navigation.Routes
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.theme.Typography
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.CallVolumeControlView
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.GamingAudioView
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.MediaCallSwitchView
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.MediaControlView
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.MediaNextTrackView
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.MediaReconnectionView
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.MediaVolumeControlView
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.PairingView
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.ReconnectionView
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.RecordingView
import android.bluetooth.tools.leaudiocompatibilitytool.app.viewmodel.TestSettingViewModel
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
@Composable
fun TestItemScreen(
    viewModel: TestSettingViewModel,
    selectedTestIds: List<Int>,
    navController: NavHostController,
) {
    val pagerState = rememberPagerState(pageCount = { selectedTestIds.size })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentTestId = selectedTestIds[pagerState.currentPage]
    val isCurrentTestFinished by
        viewModel
            .isCurrentTestFinished(currentTestId)
            .collectAsStateWithLifecycle(initialValue = false)

    // Navigate to the test list screen when the test is finished or quitted.
    fun navigateToTestList() {
        // Set the loading state to true to show the progress bar before loading the updated test
        // list.
        // This gives time for saving test results when it is finished or quitted.
        viewModel.isLoading = true

        // Navigate to the test list screen and pop up to the test list screen for the enter
        // animation.
        navController.navigate(Routes.TEST_LIST_SCREEN) {
            popUpTo(Routes.TEST_LIST_SCREEN) { inclusive = true }
            launchSingleTop = true
        }
    }

    // Navigate to the next test item.
    fun navigateToNextTestItem() {
        coroutineScope.launch {
            // Set the loading state to true to show the progress bar before loading the next test.
            // This gives time for the previous test to finish, which is required for the pairing
            // test.
            viewModel.isLoading = true
            viewModel.updateLoadingProgress()
            viewModel.isLoading = false

            pagerState.animateScrollToPage(page = pagerState.currentPage + 1)
            // Show an alert message if the target device is not bonded.
            if (!viewModel.isTargetDeviceBonded()) {
                Toast.makeText(
                        context,
                        "Please pair the headset from the phone's settings before starting the test.",
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                    ),
                title = {
                    Text(text = "Test Item (${pagerState.currentPage + 1}/${selectedTestIds.size})")
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = {
                        if (pagerState.currentPage + 1 == selectedTestIds.size) {
                            navigateToTestList()
                        } else {
                            navigateToNextTestItem()
                        }
                    },
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Text(
                        "Quit the test",
                        style = Typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                Button(
                    onClick = {
                        if (pagerState.currentPage + 1 == selectedTestIds.size) {
                            navigateToTestList()
                        } else {
                            navigateToNextTestItem()
                        }
                    },
                    shape = RoundedCornerShape(15.dp),
                    enabled = isCurrentTestFinished,
                ) {
                    Text(
                        if (pagerState.currentPage + 1 == selectedTestIds.size) "Finish Test"
                        else "Next Item",
                        style = Typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        if (viewModel.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.padding(innerPadding).fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                progress = { viewModel.loadingProgress },
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(innerPadding),
            userScrollEnabled = false,
        ) { page ->
            val testId = selectedTestIds[page]
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                when (testId) {
                    TestItemUiModel.PAIRING_SETTING.testItemId -> PairingView()
                    TestItemUiModel.RECONNECTION.testItemId -> ReconnectionView()
                    TestItemUiModel.MEDIA_CONTROL.testItemId -> MediaControlView()
                    TestItemUiModel.MEDIA_VOLUME_CONTROL.testItemId -> MediaVolumeControlView()
                    TestItemUiModel.CALL_VOLUME_CONTROL.testItemId -> CallVolumeControlView()
                    TestItemUiModel.MEDIA_CALL_SWITCH.testItemId -> MediaCallSwitchView()
                    TestItemUiModel.MEDIA_RECONNECTION.testItemId -> MediaReconnectionView()
                    TestItemUiModel.MEDIA_NEXT_TRACK.testItemId -> MediaNextTrackView()
                    TestItemUiModel.RECORDING.testItemId -> RecordingView()
                    TestItemUiModel.GAMING_AUDIO.testItemId -> GamingAudioView()
                }
            }
        }
    }
}
