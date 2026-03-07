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
package android.bluetooth.tools.leaudiocompatibilitytool.app.utils

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.converter.InstantConverter.Companion.TimeFormat
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.converter.InstantConverter.Companion.toTimeString
import android.os.Environment
import java.io.FileWriter
import java.time.Instant

/** Utility class for exporting data to files. */
object FileExporter {
    private val FOLDER_PATH =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    const val FOLDER_PATH_TEXT = "Files > Internal Storage > Downloads"

    /** Get the full file name with the path and current time. */
    fun getFilePathWithCurrentTime(suffix: String): String {
        val filename = Instant.now().toTimeString(TimeFormat.DATE_TIME_CONCATED) + "_" + suffix
        return FOLDER_PATH.resolve(filename).toString()
    }

    /** Write the content to a file. */
    fun writeFile(filename: String, content: String) {
        FileWriter(filename).use { myWriter -> myWriter.write(content) }
    }
}
