/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.bluetooth.opp;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;

import static com.android.bluetooth.TestUtils.MockitoRule;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.AdapterService;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothOppServiceCleanupTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    @UiThreadTest
    public void testCleanup() throws Exception {
        AdapterService adapterService = new AdapterService(mTargetContext);

        // Don't need to disable again since it will be handled in OppService.cleanup
        enableBtOppProvider();

        // Add thousands of placeholder rows
        for (int i = 0; i < 2000; i++) {
            ContentValues values = new ContentValues();
            mTargetContext.getContentResolver().insert(BluetoothShare.CONTENT_URI, values);
        }

        BluetoothOppService service = null;
        try {
            service = new BluetoothOppService(adapterService);
            service.setAvailable(true);

            // Call cleanup while UpdateThread is running.
            service.cleanup();
        } finally {
            if (service != null) {
                Thread updateNotificationThread = service.mNotifier.mUpdateNotificationThread;
                if (updateNotificationThread != null) {
                    updateNotificationThread.join();
                }
            }
            mTargetContext.getContentResolver().delete(BluetoothShare.CONTENT_URI, null, null);
        }
    }

    private void enableBtOppProvider() {
        mTargetContext
                .getPackageManager()
                .setApplicationEnabledSetting(
                        mTargetContext.getPackageName(),
                        COMPONENT_ENABLED_STATE_ENABLED,
                        DONT_KILL_APP);

        ComponentName providerName =
                new ComponentName(mTargetContext, BluetoothOppProvider.class.getCanonicalName());
        mTargetContext
                .getPackageManager()
                .setComponentEnabledSetting(
                        providerName, COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP);
    }
}
