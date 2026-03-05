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

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.converter.InstantConverter.Companion.TimeFormat
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.converter.InstantConverter.Companion.toTimeString
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Duration
import java.time.Instant

/**
 * Represents a test item in the LE Audio assessment.
 *
 * @property id The unique identifier for the test item.
 * @property lastAssessmentId The id of the last assessment that contains this test item.
 */
@Entity data class TestItem(@PrimaryKey val testItemId: Int, val lastAssessmentId: Int? = null)

/**
 * Represents a test item to be shown in the View.
 *
 * @property testItemId The unique identifier for the test item.
 * @property name The name of the test item.
 * @property lastTestTime The timestamp of the latest test execution (optional).
 * @property passed Indicates whether the test passed or failed (optional).
 * @property lastAssessmentId The id of the last assessment that contains this test item (optional).
 * @property qualification The pass qualification of the test item.
 */
data class TestItemUiModel(
    val testItemId: Int,
    val name: String,
    val lastTestTime: Instant? = null,
    val lastTestP95ValueInMillis: Long? = null,
    val lastTestOutlierValueInMillis: Long? = null,
    val passed: Boolean? = null,
    val lastAssessmentId: Int? = null,
    val qualification: Qualification,
) {
    /** Returns the last test time in a human-readable format. */
    fun getLastTestTimeText(): String =
        lastTestTime?.toTimeString(TimeFormat.DATE_TIME) ?: "Not tested."

    /** Returns the last test value in a human-readable format. */
    fun getLastTestResultText(): String {
        val p95Value = lastTestP95ValueInMillis?.let { it / 1000f }
        val outlierValue = lastTestOutlierValueInMillis?.let { it / 1000f }

        if (p95Value != null && outlierValue != null) {
            return "P95: ${p95Value} s, Outlier: ${outlierValue} s"
        }
        return "Not tested."
    }

    companion object {
        const val FUNCTIONALIY_TEST_QUALIFICATION_TEXT = "Should not fail at all."

        val PAIRING_SETTING =
            TestItemUiModel(
                testItemId = 1,
                name = "Pairing - Setting",
                qualification =
                    Qualification(
                        isPerformanceTest = true,
                        outlierValue = Duration.ofSeconds(25),
                        p95Value = Duration.ofSeconds(15),
                        runsRequired = 20,
                    ),
            )
        val RECONNECTION =
            TestItemUiModel(
                testItemId = 2,
                name = "Re-connection",
                qualification =
                    Qualification(
                        isPerformanceTest = true,
                        outlierValue = Duration.ofSeconds(16),
                        p95Value = Duration.ofSeconds(8),
                        runsRequired = 20,
                    ),
            )
        val MEDIA_CONTROL =
            TestItemUiModel(
                testItemId = 3,
                name = "Media Controls (Play, Pause)",
                qualification =
                    Qualification(
                        description =
                            "Headset should be able to trigger media play and pause. ${FUNCTIONALIY_TEST_QUALIFICATION_TEXT}",
                        isPerformanceTest = false,
                        runsRequired = 3,
                    ),
            )
        val MEDIA_VOLUME_CONTROL =
            TestItemUiModel(
                testItemId = 4,
                name = "Media Volume Control",
                qualification =
                    Qualification(
                        description =
                            "Headset should be able to trigger media volume up and down. ${FUNCTIONALIY_TEST_QUALIFICATION_TEXT}",
                        isPerformanceTest = false,
                        runsRequired = 3,
                    ),
            )
        val CALL_VOLUME_CONTROL =
            TestItemUiModel(
                testItemId = 5,
                name = "Call Volume Control",
                qualification =
                    Qualification(
                        isPerformanceTest = false,
                        description =
                            "Headset should be able to trigger call volume up and down. ${FUNCTIONALIY_TEST_QUALIFICATION_TEXT}",
                        runsRequired = 3,
                    ),
            )
        val MEDIA_CALL_SWITCH =
            TestItemUiModel(
                testItemId = 6,
                name = "Media Call Switch",
                qualification =
                    Qualification(
                        isPerformanceTest = false,
                        description =
                            "Headset should be able to pick up and hang up the call when media is playing. During the call, the communication device should be switched to the headset and back when the call ended. ${FUNCTIONALIY_TEST_QUALIFICATION_TEXT}",
                        runsRequired = 3,
                    ),
            )

        val MEDIA_RECONNECTION =
            TestItemUiModel(
                testItemId = 7,
                name = "Media Re-connection",
                qualification =
                    Qualification(
                        isPerformanceTest = false,
                        description =
                            "Headset should be able to hear the media after reconnection. ${FUNCTIONALIY_TEST_QUALIFICATION_TEXT}",
                        runsRequired = 3,
                    ),
            )

        val MEDIA_NEXT_TRACK =
            TestItemUiModel(
                testItemId = 8,
                name = "Media Controls (Next, Previous)",
                qualification =
                    Qualification(
                        isPerformanceTest = false,
                        description =
                            "Headset should be able to switch to the next or previous track. ${FUNCTIONALIY_TEST_QUALIFICATION_TEXT}",
                        runsRequired = 3,
                    ),
            )

        val RECORDING =
            TestItemUiModel(
                testItemId = 9,
                name = "Recording",
                qualification =
                    Qualification(
                        isPerformanceTest = false,
                        description =
                            "Headset should be able to record the media. ${FUNCTIONALIY_TEST_QUALIFICATION_TEXT}",
                        runsRequired = 1,
                    ),
            )

        val GAMING_AUDIO =
            TestItemUiModel(
                testItemId = 10,
                name = "Gaming Audio",
                qualification =
                    Qualification(
                        isPerformanceTest = false,
                        description =
                            "Headset should be able to stream the gaming audio. ${FUNCTIONALIY_TEST_QUALIFICATION_TEXT}",
                        runsRequired = 1,
                    ),
            )

        // The list of test items for unicast performance testing.
        val LIST: List<TestItemUiModel> =
            listOf(
                PAIRING_SETTING,
                RECONNECTION,
                MEDIA_CONTROL,
                MEDIA_VOLUME_CONTROL,
                CALL_VOLUME_CONTROL,
                MEDIA_CALL_SWITCH,
                MEDIA_RECONNECTION,
                MEDIA_NEXT_TRACK,
                RECORDING,
                GAMING_AUDIO,
            )
    }
}

/**
 * Represents a qualification of a test item.
 *
 * @property description The description of the qualification.
 * @property isPerformanceTest Indicates whether the qualification is a performance test. False if
 *   it is a functional test.
 * @property outlierValue The outlier value of the performance test.
 * @property p95Value The p95 value of the performance test.
 * @property runsRequired The number of runs required to test.
 */
data class Qualification(
    var description: String = "",
    val isPerformanceTest: Boolean,
    val outlierValue: Duration = Duration.ofSeconds(0),
    val p95Value: Duration = Duration.ofSeconds(0),
    val runsRequired: Int = 20,
) {
    init {
        if (isPerformanceTest) {
            description =
                "P95 < ${p95Value.toMillis()/1000f} s, Outlier < ${outlierValue.toMillis()/1000f} s"
        }
    }
}
