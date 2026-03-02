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

import android.annotation.SuppressLint
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.navigation.Routes
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.theme.Typography
import android.bluetooth.tools.leaudiocompatibilitytool.app.viewmodel.TestSettingViewModel
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectDeviceScreen(viewModel: TestSettingViewModel, navController: NavHostController) {

    LaunchedEffect(Unit) { viewModel.updateDeviceGroups() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                    ),
                title = { Text("Select Headsets to test") },
            )
        },
        bottomBar = { SelectDeviceButtonMenu(viewModel, navController) },
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (viewModel.deviceGroups.isEmpty()) {
                    Text(
                        text = "No bonded device",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Column(Modifier.selectableGroup()) {
                        for ((index, deviceGroup) in viewModel.deviceGroups.withIndex()) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .selectable(
                                        selected = (index == viewModel.selectedDeviceGroupIndex),
                                        onClick = {
                                            viewModel.updateSelectedDeviceGroupIndex(index)
                                        },
                                        role = Role.RadioButton,
                                    )
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = (index == viewModel.selectedDeviceGroupIndex),
                                    onClick = null,
                                )
                                Column(Modifier.padding(start = 16.dp)) {
                                    Text(
                                        text = deviceGroup.firstOrNull()?.name ?: "",
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier =
                                            Modifier.wrapContentHeight().padding(vertical = 8.dp),
                                    )
                                    for (device in deviceGroup) {
                                        Text(
                                            text = device.address,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.wrapContentHeight(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text =
                            "Don't see your headset?\r\n1. Please go to Bluetooth settings.\r\n2. Pair with your headset.\r\n3. Press the button below to reload.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        onClick = { viewModel.updateDeviceGroups() },
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Row() {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(text = "Reload", style = Typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SelectDeviceButtonMenu(viewModel: TestSettingViewModel, navController: NavHostController) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = { navController.navigateUp() }, shape = RoundedCornerShape(15.dp)) {
            Text(
                "Cancel",
                style = Typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Button(
            onClick = {
                viewModel.updateIsEditMode(false)
                if (viewModel.createAssessment()) {
                    val selectedTestIds = viewModel.getSelectedTestItemIds().joinToString(",")
                    navController.navigate("${Routes.TEST_ITEM_SCREEN}/$selectedTestIds") {
                        popUpTo(Routes.TEST_LIST_SCREEN)
                    }
                }
            },
            shape = RoundedCornerShape(15.dp),
            enabled =
                viewModel.selectedDeviceGroupIndex != TestSettingViewModel.DEVICE_GROUP_NOT_SET,
        ) {
            Text(
                "Start Test",
                style = Typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
