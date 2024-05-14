/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.bluetooth.test_utils;

import static com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.bluetooth.BluetoothAdapter;

import com.android.compatibility.common.util.BeforeAfterRule;

import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * TestRule to activate Bluetooth before the test.
 *
 * <ul>
 *   <li>If Bluetooth was off before the test, it will be disable again at the end of the test.
 *   <li>If enableTestMode is set, Bluetooth scanner will return hardcoded results
 *   <li>If toggleBluetooth is set, Bluetooth will be shutdown before enabling it
 * </ul>
 */
public class EnableBluetoothRule extends BeforeAfterRule {
    private final boolean mEnableTestMode;
    private final boolean mToggleBluetooth;

    /** Convenient getter to get the Bluetooth adapter associated with the Test instrumentation */
    public final BluetoothAdapter mAdapter = BlockingBluetoothAdapter.getAdapter();

    private boolean mWasBluetoothAdapterEnabled = true;

    /** Default constructor with no test mode and no turn off */
    public EnableBluetoothRule() {
        this(false, false);
    }

    /**
     * Constructor that allows test mode
     *
     * @param enableTestMode whether test mode is enabled
     */
    public EnableBluetoothRule(boolean enableTestMode) {
        this(enableTestMode, false);
    }

    /**
     * Constructor that allows test mode and allow to force shutdown bluetooth
     *
     * @param enableTestMode whether test mode is enabled
     * @param toggleBluetooth whether to shutdown Bluetooth if it was already on
     */
    public EnableBluetoothRule(boolean enableTestMode, boolean toggleBluetooth) {
        mEnableTestMode = enableTestMode;
        mToggleBluetooth = toggleBluetooth;
    }

    private void enableBluetoothTestMode() {
        runShellCommandOrThrow(
                "dumpsys activity service"
                        + " com.android.bluetooth.btservice.AdapterService set-test-mode enabled");
    }

    private void disableBluetoothTestMode() {
        runShellCommandOrThrow(
                "dumpsys activity service"
                        + " com.android.bluetooth.btservice.AdapterService set-test-mode disabled");
    }

    @Override
    protected void onBefore(Statement base, Description description) {
        assumeTrue(TestUtils.hasBluetooth());
        mWasBluetoothAdapterEnabled = mAdapter.isEnabled();
        if (!mWasBluetoothAdapterEnabled) {
            assertThat(BlockingBluetoothAdapter.enable()).isTrue();
        } else if (mToggleBluetooth) {
            assertThat(BlockingBluetoothAdapter.disable(true)).isTrue();
            assertThat(BlockingBluetoothAdapter.enable()).isTrue();
        }
        if (mEnableTestMode) {
            enableBluetoothTestMode();
        }
    }

    @Override
    protected void onAfter(Statement base, Description description) {
        assumeTrue(TestUtils.hasBluetooth());
        disableBluetoothTestMode();
        if (!mWasBluetoothAdapterEnabled) {
            assertThat(BlockingBluetoothAdapter.disable(true)).isTrue();
        }
    }
}
