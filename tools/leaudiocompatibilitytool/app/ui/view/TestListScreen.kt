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

import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.navigation.Routes
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.theme.Typography
import android.bluetooth.tools.leaudiocompatibilitytool.app.viewmodel.TestSettingViewModel
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestListScreen(viewModel: TestSettingViewModel, navController: NavHostController) {

    // Get the test item list with result when the screen is launched.
    LaunchedEffect(Unit) { viewModel.updateTestListViewWithResults() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                    ),
                title = {
                    Text(
                        text =
                            if (viewModel.isEditMode) "Select Items to Test"
                            else "Latest Performance Test Result"
                    )
                },
            )
        },
        bottomBar = { TestListButtonMenu(viewModel, navController) },
    ) { innerPadding ->
        if (viewModel.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.padding(innerPadding).fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                progress = { viewModel.loadingProgress },
            )
        } else {
            LazyColumn(modifier = Modifier.padding(innerPadding)) {
                itemsIndexed(viewModel.testItemList) { index, testItem ->
                    Row(
                        Modifier.clickable(
                                onClick = {
                                    if (!viewModel.isEditMode) {
                                        navController.navigate(
                                            "${Routes.RESULT_DETAIL_SCREEN}/${testItem.testItemId}"
                                        )
                                    } else {
                                        viewModel.onTestItemChecked(index)
                                    }
                                },
                                role = if (viewModel.isEditMode) Role.Checkbox else null,
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (viewModel.isEditMode) {
                            Column(modifier = Modifier.wrapContentHeight().padding(end = 16.dp)) {
                                Checkbox(
                                    checked = viewModel.testSelectedStates[index],
                                    onCheckedChange = null,
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1.5f)) {
                            Text(
                                modifier = Modifier.wrapContentHeight(),
                                text = testItem.name,
                                overflow = TextOverflow.Ellipsis,
                                style = Typography.bodyLarge,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Row() {
                                testItem.passed?.let { passed ->
                                    Column() {
                                        Icon(
                                            imageVector =
                                                if (passed) Icons.Outlined.Check
                                                else Icons.Outlined.Close,
                                            contentDescription = if (passed) "Passed" else "Failed",
                                            tint = if (passed) Color(0, 150, 0) else Color.Red,
                                        )
                                    }
                                }
                                Text(
                                    text = testItem.getLastTestTimeText(),
                                    overflow = TextOverflow.Ellipsis,
                                    style = Typography.bodyLarge,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                        }
                        if (!viewModel.isEditMode) {
                            Column(modifier = Modifier.wrapContentHeight()) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color = Color.LightGray,
                        modifier = Modifier.height(1.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
fun TestListButtonMenu(viewModel: TestSettingViewModel, navController: NavHostController) {
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (viewModel.isEditMode) {
            TextButton(
                onClick = { viewModel.updateIsEditMode(false) },
                shape = RoundedCornerShape(15.dp),
            ) {
                Text(
                    "Cancel",
                    style = Typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Button(
                onClick = { navController.navigate(Routes.SELECT_DEVICE_SCREEN) },
                shape = RoundedCornerShape(15.dp),
                enabled = viewModel.testSelectedStates.any { it },
            ) {
                Text(
                    "Next: Select Headset",
                    style = Typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            OutlinedButton(
                onClick = {
                    viewModel.exportResult { message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(15.dp),
                enabled = viewModel.isExportBtnEnabled,
            ) {
                Text(
                    "Export Result",
                    style = Typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Button(
                onClick = { viewModel.updateIsEditMode(true) },
                shape = RoundedCornerShape(15.dp),
            ) {
                Text(
                    "Create Test",
                    style = Typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}
