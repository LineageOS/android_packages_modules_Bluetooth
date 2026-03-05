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
package android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment

import androidx.room.Entity
import java.time.Instant

/**
 * Represent a selected test item in an assessment.
 *
 * @property assessmentId The ID of the assessment.
 * @property testItemId The ID of the test item.
 * @property passed Whether the test item passed or not. Default is null for not tested items.
 * @property startTime The start time of the test item. Default is null for not tested items.
 * @property endTime The end time of the test item. Default is null for not tested items.
 * @property p95ValueInMillis The p95 value of the test item. Default is null for not tested items
 *   or functional test items.
 * @property outlierValueInMillis The outlier value of the test item. Default is null for not tested
 *   items or functional test items.
 */
@Entity(primaryKeys = ["assessmentId", "testItemId"])
data class SelectedTestItem(
    val assessmentId: Int,
    val testItemId: Int,
    val passed: Boolean? = null,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val p95ValueInMillis: Long? = null,
    val outlierValueInMillis: Long? = null,
)
