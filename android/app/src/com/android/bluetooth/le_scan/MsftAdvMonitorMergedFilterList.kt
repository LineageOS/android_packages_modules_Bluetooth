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

package com.android.bluetooth.le_scan

/**
 * Helper class to keep track of MSFT filters (Patterns, UUIDs, Addresses), their filter index, and
 * number of monitors registered with that filter. Some chipsets don't support multiple monitors
 * with the same filter. To solve that and to generally ease their task, we merge monitors with the
 * same filter, so those monitors will only be sent once.
 */
class MsftAdvMonitorMergedFilterList {

    internal class MergedPattern(
        val patterns: Array<MsftAdvMonitor.Pattern>,
        val filterIndex: Int,
    ) {
        var count: Int = 0
    }

    internal class MergedUuid(val uuid: MsftAdvMonitor.Uuid, val filterIndex: Int) {
        var count: Int = 0
    }

    internal class MergedAddress(val address: MsftAdvMonitor.Address, val filterIndex: Int) {
        var count: Int = 0
    }

    // Three separate lists to track the different filter structures
    private val mergedPatterns = mutableListOf<MergedPattern>()
    private val mergedUuids = mutableListOf<MergedUuid>()
    private val mergedAddresses = mutableListOf<MergedAddress>()

    // --- Retrieve Methods ---

    // Returns merged pattern or null if not found.
    private fun getMergedPattern(pattern: Array<MsftAdvMonitor.Pattern>): MergedPattern? {
        return mergedPatterns.find { it.patterns.contentEquals(pattern) }
    }

    // Returns merged UUID or null if not found. Matches based on underlying byte array.
    private fun getMergedUuid(uuid: MsftAdvMonitor.Uuid): MergedUuid? {
        return mergedUuids.find { it.uuid.uuid.contentEquals(uuid.uuid) }
    }

    // Returns merged Address or null if not found. Matches based on type and string address.
    private fun getMergedAddress(address: MsftAdvMonitor.Address): MergedAddress? {
        return mergedAddresses.find {
            it.address.addr_type == address.addr_type && it.address.bd_addr == address.bd_addr
        }
    }

    // --- Add Methods ---

    // If pattern doesn't exist, creates new entry with given index.
    // If pattern exists, increases count and returns filter index.
    fun addPattern(filterIndex: Int, pattern: Array<MsftAdvMonitor.Pattern>): Int {
        val mergedPattern =
            getMergedPattern(pattern)
                ?: MergedPattern(pattern, filterIndex).also { mergedPatterns.add(it) }

        mergedPattern.count++
        return mergedPattern.filterIndex
    }

    fun addUuid(filterIndex: Int, uuid: MsftAdvMonitor.Uuid): Int {
        val mergedUuid =
            getMergedUuid(uuid) ?: MergedUuid(uuid, filterIndex).also { mergedUuids.add(it) }

        mergedUuid.count++
        return mergedUuid.filterIndex
    }

    fun addAddress(filterIndex: Int, address: MsftAdvMonitor.Address): Int {
        val mergedAddress =
            getMergedAddress(address)
                ?: MergedAddress(address, filterIndex).also { mergedAddresses.add(it) }

        mergedAddress.count++
        return mergedAddress.filterIndex
    }

    // --- Global Operations ---

    // Decreases count for the provided filter index across any matching list.
    // If count hits 0, removes entry. Returns true if there are no more instances.
    fun remove(filterIndex: Int): Boolean {
        mergedPatterns.filter { it.filterIndex == filterIndex }.forEach { it.count-- }
        val removedPatterns = mergedPatterns.removeAll { it.count == 0 }

        mergedUuids.filter { it.filterIndex == filterIndex }.forEach { it.count-- }
        val removedUuids = mergedUuids.removeAll { it.count == 0 }

        mergedAddresses.filter { it.filterIndex == filterIndex }.forEach { it.count-- }
        val removedAddresses = mergedAddresses.removeAll { it.count == 0 }

        // Return true if ANY of the lists actually removed an item
        return removedPatterns || removedUuids || removedAddresses
    }
}
