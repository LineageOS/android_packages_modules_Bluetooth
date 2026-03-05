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
package android.bluetooth.tools.leaudiocompatibilitytool.app.data.converter

import androidx.room.TypeConverter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Type converter class which tells Room how to persist [Instant]. */
@SuppressWarnings("GoodTime")
class InstantConverter {
    /**
     * Converts the provided nullable [Instant] to epoch millis or null to be stored in the Room
     * database.
     */
    @TypeConverter fun fromInstant(time: Instant?): Long? = time?.toEpochMilli()

    /**
     * Converts the epoch millis retrieved from the Room database to an nullable [Instant] to be
     * returned to the caller.
     */
    @TypeConverter
    fun toInstant(epochMillis: Long?): Instant? = epochMillis?.let { Instant.ofEpochMilli(it) }

    companion object {
        /**
         * Converts the provided [Instant] to a string in the specified format.
         *
         * @param time The [Instant] to be converted.
         * @param format The format of the string.
         * @return The string representation of the [Instant] in the specified format.
         */
        fun Instant.toTimeString(format: TimeFormat): String {
            val timeFormatter =
                when (format) {
                    TimeFormat.DATE_TIME ->
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(ZoneId.systemDefault())
                    TimeFormat.TIME_ONLY ->
                        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
                    TimeFormat.DATE_TIME_CONCATED ->
                        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                            .withZone(ZoneId.systemDefault())
                    TimeFormat.DATE_TIME_WITH_MILLISECONDS ->
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                            .withZone(ZoneId.systemDefault())
                }
            return timeFormatter.format(this)
        }

        /** The format of the time string. */
        enum class TimeFormat {
            DATE_TIME,
            TIME_ONLY,
            DATE_TIME_WITH_MILLISECONDS,
            DATE_TIME_CONCATED,
        }
    }
}
