/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.util

/**
 * Defines a table column
 *
 * @param T The type of the data object being rendered in each row.
 * @param header The column's header text.
 * @param width Optional width. If null, width is calculated dynamically.
 * @param value Extracts cell content from a data object.
 */
data class Column<T>(val header: String, val width: Int? = null, val value: (T) -> Any)

fun <T> Iterable<T>.toTable(vararg columns: Column<T>) = toTable(columns.toList())

/**
 * Formats an iterable of items into a monospaced string table
 *
 * Column widths are calculated dynamically based on content unless a `width` is specified. To
 * indent the entire resulting table, use [indent] on the returned string:
 * ```
 * val table = myList.toTable(columns).indent("  ")
 * sb.appendLine(table)
 * ```
 * **
 *
 * @param columns A list of `Column` objects defining the table's structure.
 * @return A formatted string table or an empty string if the input is empty.
 */
fun <T> Iterable<T>.toTable(columns: List<Column<T>>): String {
    val data = this.toList()
    if (data.isEmpty() || columns.isEmpty()) {
        return ""
    }

    val colWidths = columns.map { column ->
        column.width
            ?: maxOf(
                column.header.length,
                data.maxOfOrNull { column.value(it).toString().length } ?: 0,
            )
    }

    return buildString {
        // Headers
        appendLine(
            columns
                .zip(colWidths) { column, width -> column.header.padEnd(width) }
                .joinToString(" ")
        )
        // Separators
        appendLine(colWidths.joinToString(" ") { width -> "-".repeat(width) })
        // Values
        data.forEach {
            appendLine(
                columns
                    .zip(colWidths) { column, width -> column.value(it).toString().padEnd(width) }
                    .joinToString(" ")
            )
        }
    }
}
