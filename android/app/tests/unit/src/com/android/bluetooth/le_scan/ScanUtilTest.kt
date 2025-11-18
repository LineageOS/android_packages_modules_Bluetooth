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

package com.android.bluetooth.le_scan

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.le_scan.ScanUtil.statusToString
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [ScanUtil]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScanUtilTest {
    @Test
    fun statusToString() {
        assertThat(statusToString(ScanCallback.NO_ERROR)).isEqualTo("SUCCESS")
        assertThat(statusToString(ScanCallback.SCAN_FAILED_ALREADY_STARTED))
            .isEqualTo("ALREADY_STARTED")
        assertThat(statusToString(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED))
            .isEqualTo("APP_REGISTRATION_FAILED")
        assertThat(statusToString(ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
            .isEqualTo("INTERNAL_ERROR")
        assertThat(statusToString(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED))
            .isEqualTo("FEATURE_UNSUPPORTED")
        assertThat(statusToString(ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES))
            .isEqualTo("OUT_OF_HARDWARE_RESOURCES")
        assertThat(statusToString(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY))
            .isEqualTo("SCANNING_TOO_FREQUENTLY")
        assertThat(statusToString(99)).isEqualTo("UNKNOWN(99)")
    }

    @Test
    fun callbackTypeToString() {
        assertThat(CallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).toString())
            .isEqualTo("ALL_MATCHES")
        assertThat(CallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH).toString())
            .isEqualTo("FIRST_MATCH")
        assertThat(CallbackType(ScanSettings.CALLBACK_TYPE_MATCH_LOST).toString()).isEqualTo("LOST")
        assertThat(CallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH).toString())
            .isEqualTo("ALL_MATCHES_AUTO_BATCH")
        val firstMatchOrLost =
            CallbackType(
                ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST
            )
        assertThat(firstMatchOrLost.toString()).isEqualTo("[FIRST_MATCH | LOST]")
        assertThat(CallbackType(99).toString()).isEqualTo("UNKNOWN(99)")
    }

    @Test
    fun matchModeToString() {
        assertThat(MatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).toString()).isEqualTo("AGGRESSIVE")
        assertThat(MatchMode(ScanSettings.MATCH_MODE_STICKY).toString()).isEqualTo("STICKY")
        assertThat(MatchMode(99).toString()).isEqualTo("UNKNOWN(99)")
    }

    @Test
    fun numberOfMatchesToString() {
        assertThat(NumberOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT).toString())
            .isEqualTo("ONE")
        assertThat(NumberOfMatches(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT).toString())
            .isEqualTo("FEW")
        assertThat(NumberOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT).toString())
            .isEqualTo("MAX")
        assertThat(NumberOfMatches(99).toString()).isEqualTo("UNKNOWN(99)")
    }

    @Test
    fun phyToString() {
        assertThat(Phy(ScanSettings.PHY_LE_ALL_SUPPORTED).toString()).isEqualTo("ALL")
        assertThat(Phy(android.bluetooth.BluetoothDevice.PHY_LE_1M).toString()).isEqualTo("1M")
        assertThat(Phy(android.bluetooth.BluetoothDevice.PHY_LE_CODED).toString())
            .isEqualTo("CODED")
        assertThat(Phy(99).toString()).isEqualTo("UNKNOWN(99)")
    }

    @Test
    fun resultTypeToString() {
        assertThat(ResultType(ScanSettings.SCAN_RESULT_TYPE_FULL).toString()).isEqualTo("FULL")
        assertThat(ResultType(ScanSettings.SCAN_RESULT_TYPE_ABBREVIATED).toString())
            .isEqualTo("ABBREVIATED")
        assertThat(ResultType(99).toString()).isEqualTo("UNKNOWN(99)")
    }

    @Test
    fun scanModeToString() {
        assertThat(ScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC).toString())
            .isEqualTo("OPPORTUNISTIC")
        assertThat(ScanMode(ScanSettings.SCAN_MODE_LOW_POWER).toString()).isEqualTo("LOW_POWER")
        assertThat(ScanMode(ScanSettings.SCAN_MODE_BALANCED).toString()).isEqualTo("BALANCED")
        assertThat(ScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).toString()).isEqualTo("LOW_LATENCY")
        assertThat(ScanMode(ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY).toString())
            .isEqualTo("AMBIENT_DISCOVERY")
        assertThat(ScanMode(ScanSettings.SCAN_MODE_SCREEN_OFF).toString()).isEqualTo("SCREEN_OFF")
        assertThat(ScanMode(ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED).toString())
            .isEqualTo("SCREEN_OFF_BALANCED")
        assertThat(ScanMode(99).toString()).isEqualTo("UNKNOWN(99)")
    }

    @Test
    fun typeToString() {
        assertThat(Type(ScanSettings.SCAN_TYPE_UNKNOWN).toString()).isEqualTo("UNKNOWN")
        assertThat(Type(ScanSettings.SCAN_TYPE_PASSIVE).toString()).isEqualTo("PASSIVE")
        assertThat(Type(ScanSettings.SCAN_TYPE_ACTIVE).toString()).isEqualTo("ACTIVE")
        assertThat(Type(99).toString()).isEqualTo("UNKNOWN(99)")
    }
}
