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

import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.theme.Typography
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.LogConsole
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.component.SubTitleText
import android.bluetooth.tools.leaudiocompatibilitytool.app.viewmodel.ResultDetailsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultDetailsScreen(testItemId: Int, navController: NavHostController) {
    val viewModel: ResultDetailsViewModel = hiltViewModel()
    val PASSED_COLOR = Color(0, 150, 0)
    val FAILED_COLOR = Color.Red

    LaunchedEffect(testItemId) { // Fetch the result when testItemId changes
        viewModel.getTestResult(testItemId)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                    ),
                title = { Text(viewModel.testName) },
            )
        },
        bottomBar = { TestDetailsButtonMenu(navController) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(horizontal = 16.dp)) {
            viewModel.testItem?.let { testItem ->
                Column(
                    modifier = Modifier.fillMaxHeight(0.6f).verticalScroll(rememberScrollState())
                ) {
                    Column {
                        SubTitleText("Target Performance Criteria")
                        Text(testItem.qualification.description)

                        SubTitleText("Latest test result")
                        if (testItem.qualification.isPerformanceTest) {
                            Text(testItem.getLastTestResultText())
                        } else {
                            if (testItem.lastTestTime != null) {
                                Text("Failed ${viewModel.runList.count { !it.passed }} times.")
                            } else {
                                Text("No tested.")
                            }
                        }

                        SubTitleText("Latest test time")
                        Text(testItem.getLastTestTimeText())
                    }

                    SubTitleText("Device")
                    Text(viewModel.deviceName, modifier = Modifier.padding(bottom = 8.dp))
                    for (address in viewModel.deviceAddressList) {
                        Text(
                            text = address,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.wrapContentHeight(),
                        )
                    }

                    SubTitleText("Result")
                    for (run in viewModel.runList) {
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Text(text = "Run ${run.runId}. ")
                            Text(
                                text = if (run.passed) "Passed" else "Failed",
                                color = if (run.passed) PASSED_COLOR else FAILED_COLOR,
                            )
                            if (run.isPerformanceTest) {
                                Text(
                                    text =
                                        "Duration: ${ String.format(Locale.US, "%,d", run.duration?.toMillis() ?: 0).toString()} ms",
                                    color = if (run.passed) PASSED_COLOR else FAILED_COLOR,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            LogConsole(logs = viewModel.logList, isResultPage = true)
        }
    }
}

@Composable
fun TestDetailsButtonMenu(navController: NavHostController) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = { navController.navigateUp() }, shape = RoundedCornerShape(15.dp)) {
            Text(
                "Return to Result List",
                style = Typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
