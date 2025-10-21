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

@file:JvmName("Text")

package com.android.bluetooth.util

import kotlin.text.Charsets.UTF_8

/**
 * Prepends the given [indent] to each line of this string, after first removing all trailing
 * whitespaces.
 *
 * This function is a convenience wrapper that calls [trimEnd] before calling [prependIndent]. This
 * is useful for avoiding the standard [prependIndent] behavior of adding an indent to the blank
 * line that follows a trailing newline character (`\n`).
 *
 * Example:
 * ```
 * "Hi\n".prependIndent("  ") // returns "  Hi\n  " (undesired)
 * "Hi\n".indent("  ")        // returns "  Hi\n"   (desired)
 * ```
 *
 * On a string with no trailing newline, the behavior will be the exact same as [prependIndent]
 *
 * @param indent The string to prepend to each line (defaults to four spaces)
 * @return The indented string, with no trailing indent on the final newline
 */
fun String.indent(indent: String = "    ") = trimEnd().prependIndent(indent)

/**
 * Returns the longest prefix of a string for which the UTF-8 encoding fits into the given number of
 * bytes, with the additional guarantee that the string is not truncated in the middle of a valid
 * surrogate pair.
 *
 * <p>Unpaired surrogates are counted as taking 3 bytes of storage. However, a subsequent attempt to
 * actually encode a string containing unpaired surrogates is likely to be rejected by the UTF-8
 * implementation.
 *
 * <p>(See {@code android.text.TextUtils.truncateStringForUtf8Storage}
 *
 * @param maxbytes the maximum bytes size of the of UTF-8 string
 * @return a string that use at most {@code maxbytes} bytes in UTF-8
 */
fun String.truncateUtf8String(maxBytes: Int): String {
    require(maxBytes >= 0) { "maxBytes must not be negative." }

    // Convert the string to a UTF-8 byte array.
    val bytes = this.toByteArray(UTF_8)

    // If it already fits, no work is needed.
    if (bytes.size <= maxBytes) {
        return this
    }

    // Find the last byte that is NOT a UTF-8 continuation byte.
    // This ensures we don't cut a multi-byte character in half.
    var validEndIndex = maxBytes
    while (validEndIndex > 0 && (bytes[validEndIndex].toInt() and 0xC0) == 0x80) {
        validEndIndex--
    }

    return String(bytes, 0, validEndIndex, UTF_8)
}
