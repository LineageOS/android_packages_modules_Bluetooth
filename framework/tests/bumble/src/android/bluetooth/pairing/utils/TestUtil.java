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

package android.bluetooth.pairing.utils;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import org.mockito.ArgumentCaptor;

import java.time.Duration;

public class TestUtil {
  private static final String TAG = TestUtil.class.getSimpleName();

  private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);

  private final Context mTargetContext;
  private final BluetoothProfile.ServiceListener mProfileServiceListener;
  private final BluetoothAdapter mAdapter;

  private TestUtil(Builder builder) {
    mTargetContext = builder.mTargetContext;
    mProfileServiceListener = builder.mProfileServiceListener;
    mAdapter = builder.mAdapter;
  }

  public static class Builder {
    /* Target context is required for all the test functions */
    private final Context mTargetContext;

    private BluetoothProfile.ServiceListener mProfileServiceListener;
    private BluetoothAdapter mAdapter;

    public Builder(@NonNull Context context) {
      mTargetContext = context;
      mProfileServiceListener = null;
      mAdapter = null;
    }

    public Builder setProfileServiceListener(BluetoothProfile.ServiceListener
        profileServiceListener) {
      mProfileServiceListener = profileServiceListener;
      return this;
    }

    public Builder setBluetoothAdapter(BluetoothAdapter adapter) {
      mAdapter = adapter;
      return this;
    }

    public TestUtil build() {
      return new TestUtil(this);
    }
  }

  /**
   * Helper function to remove the bond for the given device
   *
   * @param parentIntentReceiver IntentReceiver instance from the parent test caller
   *  This should be `null` if there is no parent IntentReceiver instance.
   * @param device The device to remove the bond for
   */
  public void removeBond(IntentReceiver parentIntentReceiver,
      BluetoothDevice device) {
    IntentReceiver intentReceiver =
        IntentReceiver.update(parentIntentReceiver,
            new IntentReceiver.Builder(
                mTargetContext,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED));

    assertThat(device.removeBond()).isTrue();
    intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.BOND_NONE));

    intentReceiver.close();
  }

  /**
   * Get the profile proxy for the given profile
   *
   * @param profile The profile to get the proxy for
   * @throws RuntimeException if mProfileServiceListener || mAdapter is null
   *      (passed during instance creation)
   * @return The profile proxy
   */
  public BluetoothProfile getProfileProxy(int profile) {
    if (mProfileServiceListener == null ||
            mAdapter == null) {
        throw new RuntimeException(
            "TestUtil: ServiceListener or BluetoothAdapter in getProfileProxy() is NULL");
    }

    mAdapter.getProfileProxy(mTargetContext, mProfileServiceListener, profile);
    ArgumentCaptor<BluetoothProfile> proxyCaptor =
            ArgumentCaptor.forClass(BluetoothProfile.class);
    verify(mProfileServiceListener, timeout(BOND_INTENT_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture());
    return proxyCaptor.getValue();
  }
}