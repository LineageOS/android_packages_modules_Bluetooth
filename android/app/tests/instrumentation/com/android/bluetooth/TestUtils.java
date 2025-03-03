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
package com.android.bluetooth;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.annotation.IntRange;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Message;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.rules.MethodRule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;

import java.util.stream.IntStream;

/** A set of methods useful in Bluetooth instrumentation tests */
public class TestUtils {
    private static String sSystemScreenOffTimeout = "10000";

    private static final String TAG = "BluetoothTestUtils";

    /** Helper function to mock getSystemService calls */
    public static <T> void mockGetSystemService(
            Context ctx, String serviceName, Class<T> serviceClass, T mockService) {
        // doReturn(mockService).when(ctx).getSystemService(eq(serviceClass)); // need extended mock
        doReturn(mockService).when(ctx).getSystemService(eq(serviceName));
        doReturn(serviceName).when(ctx).getSystemServiceName(eq(serviceClass));
    }

    /** Helper function to mock getSystemService calls */
    public static <T> T mockGetSystemService(
            Context ctx, String serviceName, Class<T> serviceClass) {
        T mockedService = mock(serviceClass);
        mockGetSystemService(ctx, serviceName, serviceClass, mockedService);
        return mockedService;
    }

    /**
     * Create a test device.
     *
     * @param id the test device ID. It must be an integer in the interval [0, 0xFF].
     * @return {@link BluetoothDevice} test device for the device ID
     */
    public static BluetoothDevice getTestDevice(@IntRange(from = 0x00, to = 0xFF) int id) {
        assertThat(id).isAtMost(0xFF);
        BluetoothDevice testDevice =
                InstrumentationRegistry.getInstrumentation()
                        .getTargetContext()
                        .getSystemService(BluetoothManager.class)
                        .getAdapter()
                        .getRemoteDevice(String.format("00:01:02:03:04:%02X", id));
        assertThat(testDevice).isNotNull();
        return testDevice;
    }

    /**
     * Dispatch all the message on the Loopper and check that the `what` is expected
     *
     * @param looper looper to execute the message from
     * @param what list of Messages.what that are expected to be run by the handler
     */
    public static void syncHandler(TestLooper looper, int... what) {
        IntStream.of(what)
                .forEach(
                        w -> {
                            Message msg = looper.nextMessage();
                            assertWithMessage("Expecting [" + w + "] instead of null Msg")
                                    .that(msg)
                                    .isNotNull();
                            assertWithMessage("Not the expected Message:\n" + msg)
                                    .that(msg.what)
                                    .isEqualTo(w);
                            Log.d(TAG, "Processing message: " + msg);
                            msg.getTarget().dispatchMessage(msg);
                        });
    }

    public static void setUpUiTest() throws Exception {
        final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // Disable animation
        device.executeShellCommand("settings put global window_animation_scale 0.0");
        device.executeShellCommand("settings put global transition_animation_scale 0.0");
        device.executeShellCommand("settings put global animator_duration_scale 0.0");

        // change device screen_off_timeout to 5 minutes
        sSystemScreenOffTimeout =
                device.executeShellCommand("settings get system screen_off_timeout");
        device.executeShellCommand("settings put system screen_off_timeout 300000");

        // Turn on screen and unlock
        device.wakeUp();
        device.executeShellCommand("wm dismiss-keyguard");

        // Back to home screen, in case some dialog/activity is in front
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome();
    }

    public static void tearDownUiTest() throws Exception {
        final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("wm dismiss-keyguard");

        // Re-enable animation
        device.executeShellCommand("settings put global window_animation_scale 1.0");
        device.executeShellCommand("settings put global transition_animation_scale 1.0");
        device.executeShellCommand("settings put global animator_duration_scale 1.0");

        // restore screen_off_timeout
        device.executeShellCommand(
                "settings put system screen_off_timeout " + sSystemScreenOffTimeout);
    }

    public static class RetryTestRule implements TestRule {
        private int retryCount = 5;

        public RetryTestRule() {
            this(5);
        }

        public RetryTestRule(int retryCount) {
            this.retryCount = retryCount;
        }

        public Statement apply(Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    Throwable caughtThrowable = null;

                    // implement retry logic here
                    for (int i = 0; i < retryCount; i++) {
                        try {
                            base.evaluate();
                            return;
                        } catch (Throwable t) {
                            caughtThrowable = t;
                            Log.e(
                                    TAG,
                                    description.getDisplayName() + ": run " + (i + 1) + " failed",
                                    t);
                        }
                    }
                    Log.e(
                            TAG,
                            description.getDisplayName()
                                    + ": giving up after "
                                    + retryCount
                                    + " failures");
                    throw caughtThrowable;
                }
            };
        }
    }

    /** Wrapper around MockitoJUnit.rule() to clear the inline mock at the end of the test. */
    public static class MockitoRule implements MethodRule {
        private final org.mockito.junit.MockitoRule mMockitoRule = MockitoJUnit.rule();

        public Statement apply(Statement base, FrameworkMethod method, Object target) {
            Statement nestedStatement = mMockitoRule.apply(base, method, target);

            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    nestedStatement.evaluate();

                    // Prevent OutOfMemory errors due to mock maker leaks.
                    // See https://github.com/mockito/mockito/issues/1614, b/259280359, b/396177821
                    Mockito.framework().clearInlineMocks();
                }
            };
        }
    }
}
