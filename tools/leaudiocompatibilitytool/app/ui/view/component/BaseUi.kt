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

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.logInfo.LogRecordUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.theme.Typography
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A text that can be used to display a test title.
 *
 * @param text The text to display.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TitleText(text: String) {
    Text(text, style = Typography.bodyLargeEmphasized)
}

/**
 * A text that can be used to display a sub title in the test details page.
 *
 * @param text The text to display.
 */
@Composable
fun SubTitleText(text: String) {
    Text(
        text,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * A text that can be used to display a test description such as step 1, step 2, etc.
 *
 * @param text The text to display.
 */
@Composable
fun DescriptionText(text: String) {
    Text(
        text,
        style = Typography.bodyLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/**
 * A button that can be used to perform a main action.
 *
 * @param onClickHandler The action to perform when the button is clicked.
 * @param text The text to display on the button.
 * @param enabled Whether the button is enabled or not.
 */
@Composable
fun ActionButton(onClickHandler: () -> Unit, text: String, enabled: Boolean = true) {
    Button(onClick = onClickHandler, enabled = enabled, shape = RoundedCornerShape(15.dp)) {
        Text(text, style = Typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
fun SecondaryButton(onClickHandler: () -> Unit, text: String, enabled: Boolean = true) {
    OutlinedButton(onClick = onClickHandler, enabled = enabled, shape = RoundedCornerShape(15.dp)) {
        Text(text, style = Typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
fun ButtonGroup(leftButton: @Composable () -> Unit, rightButton: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leftButton()
        rightButton()
    }
}

/**
 * A console that can be used to display a list of logs.
 *
 * @param logs The list of logs to display.
 */
@Composable
fun LogConsole(logs: MutableList<LogRecordUiModel>, isResultPage: Boolean = false) {
    val listState = rememberLazyListState()

    // Scroll to the latest log when logList changes
    if (!isResultPage) {
        LaunchedEffect(key1 = logs.size) {
            if (logs.isNotEmpty()) {
                listState.animateScrollToItem(index = logs.lastIndex)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier =
            Modifier.fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(16.dp),
    ) {
        items(logs) { log ->
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = log.getTimestampText(),
                    style = Typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                if (log.actionRequired) {
                    Text(
                        text = log.message,
                        style = Typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(text = log.message, style = Typography.bodyMedium)
                }
            }
        }
    }
}

object ViewTheme {
    val ScrollableColumnStyle = Modifier.fillMaxHeight(0.4f)
}
