/*
 * Copyright 2018 The Android Open Source Project
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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.service.media.MediaBrowserService;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.rules.MethodRule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;

/** A set of methods useful in Bluetooth instrumentation tests */
public class TestUtils {
    private static final String TAG = Utils.TAG_PREFIX_BLUETOOTH + TestUtils.class.getSimpleName();

    private static String sSystemScreenOffTimeout = "10000";

    /**
     * Set the return value of {@link AdapterService#getAdapterService()} to a test specified value
     *
     * @param adapterService the designated {@link AdapterService} in test, must not be null, can be
     *     mocked or spied
     */
    public static void setAdapterService(AdapterService adapterService) {
        assertWithMessage(
                        "AdapterService.getAdapterService() must be null before setting another"
                                + " AdapterService")
                .that(AdapterService.getAdapterService())
                .isNull();
        assertThat(adapterService).isNotNull();
        // We cannot mock AdapterService.getAdapterService() with Mockito.
        // Hence we need to set AdapterService.sAdapterService field.
        AdapterService.setAdapterService(adapterService);
    }

    /**
     * Clear the return value of {@link AdapterService#getAdapterService()} to null
     *
     * @param adapterService the {@link AdapterService} used when calling {@link
     *     TestUtils#setAdapterService(AdapterService)}
     */
    public static void clearAdapterService(AdapterService adapterService) {
        assertWithMessage(
                        "AdapterService.getAdapterService() must return the same object as the"
                                + " supplied adapterService in this method")
                .that(adapterService)
                .isSameInstanceAs(AdapterService.getAdapterService());
        assertThat(adapterService).isNotNull();
        AdapterService.clearAdapterService(adapterService);
    }

    /** Helper function to mock getSystemService calls */
    public static <T> void mockGetSystemService(
            Context ctx, String serviceName, Class<T> serviceClass, T mockService) {
        doReturn(mockService).when(ctx).getSystemService(eq(serviceClass));
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

    public static Resources getTestApplicationResources(Context context) {
        try {
            return context.getPackageManager()
                    .getResourcesForApplication("com.android.bluetooth.tests");
        } catch (PackageManager.NameNotFoundException e) {
            assertWithMessage("Unable to get test application resources: " + e.toString()).fail();
            return null;
        }
    }


    /**
     * Wait for looper to finish its current task and all tasks schedule before this
     *
     * @param looper looper of interest
     */
    public static void waitForLooperToFinishScheduledTask(Looper looper) {
        runOnLooperSync(
                looper,
                () -> {
                    // do nothing, just need to make sure looper finishes current task
                });
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

    /**
     * Wait for looper to become idle
     *
     * @param looper looper of interest
     */
    public static void waitForLooperToBeIdle(Looper looper) {
        class Idler implements MessageQueue.IdleHandler {
            private boolean mIdle = false;

            @Override
            public boolean queueIdle() {
                synchronized (this) {
                    mIdle = true;
                    notifyAll();
                }
                return false;
            }

            public synchronized void waitForIdle() {
                while (!mIdle) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Log.w(TAG, "waitForIdle got interrupted", e);
                    }
                }
            }
        }

        Idler idle = new Idler();
        looper.getQueue().addIdleHandler(idle);
        // Ensure we are not Idle to begin with so the idle handler will run
        waitForLooperToFinishScheduledTask(looper);
        idle.waitForIdle();
    }

    /**
     * Run synchronously a runnable action on a looper. The method will return after the action has
     * been execution to completion.
     *
     * <p>Example:
     *
     * <pre>{@code
     * TestUtils.runOnMainSync(new Runnable() {
     *       public void run() {
     *           assertThat(mA2dpService.stop()).isTrue();
     *       }
     *   });
     * }</pre>
     *
     * @param looper the looper used to run the action
     * @param action the action to run
     */
    private static void runOnLooperSync(Looper looper, Runnable action) {
        if (Looper.myLooper() == looper) {
            // requested thread is the same as the current thread. call directly.
            action.run();
        } else {
            Handler handler = new Handler(looper);
            SyncRunnable sr = new SyncRunnable(action);
            handler.post(sr);
            sr.waitForComplete();
        }
    }

    /**
     * Prepare the intent to start bluetooth browser media service.
     *
     * @return intent with the appropriate component & action set.
     */
    public static Intent prepareIntentToStartBluetoothBrowserMediaService() {
        final Intent intent =
                new Intent(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        BluetoothMediaBrowserService.class);
        intent.setAction(MediaBrowserService.SERVICE_INTERFACE);
        return intent;
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

    /** Helper class used to run synchronously a runnable action on a looper. */
    private static final class SyncRunnable implements Runnable {
        private final Runnable mTarget;
        private volatile boolean mComplete = false;

        SyncRunnable(Runnable target) {
            mTarget = target;
        }

        @Override
        public void run() {
            mTarget.run();
            synchronized (this) {
                mComplete = true;
                notifyAll();
            }
        }

        public void waitForComplete() {
            synchronized (this) {
                while (!mComplete) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Log.w(TAG, "waitForComplete got interrupted", e);
                    }
                }
            }
        }
    }

    public static final class FakeTimeProvider implements Utils.TimeProvider {
        private Instant currentTime = Instant.EPOCH;

        @Override
        public long elapsedRealtime() {
            return currentTime.toEpochMilli();
        }

        public void advanceTime(Duration amountToAdvance) {
            currentTime = currentTime.plus(amountToAdvance);
        }
    }
}
