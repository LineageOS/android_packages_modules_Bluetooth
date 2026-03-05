/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import android.annotation.IntRange;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothDevice.AddressType;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemProperties;
import android.service.media.MediaBrowserService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.media_audio.sink.BluetoothMediaBrowserService;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** A set of methods useful in Bluetooth instrumentation tests */
public class TestUtils {
    private static final String TAG = Util.BT_PREFIX + TestUtils.class.getSimpleName();

    private static Context getContext() {
        return InstrumentationRegistry.getInstrumentation().getContext();
    }

    /** Returns the real {@link BluetoothManager} */
    public static BluetoothManager getBluetoothManager() {
        return getContext().getSystemService(BluetoothManager.class);
    }

    /** Mocks {@link Context#getSystemService(Class)} to return a mock instance of the service. */
    public static <T> T mockGetSystemService(Context context, Class<T> serviceClass) {
        T mockedService = mock(serviceClass);
        mockGetSystemService(context, serviceClass, mockedService);
        return mockedService;
    }

    /** Mocks {@link Context#getSystemService(Class)} to return a specific service instance. */
    public static <T> void mockGetSystemService(Context context, Class<T> serviceClass, T service) {
        doReturn(service).when(context).getSystemService(serviceClass);
        final var serviceName = getContext().getSystemServiceName(serviceClass);
        doReturn(service).when(context).getSystemService(serviceName);
        doReturn(serviceName).when(context).getSystemServiceName(serviceClass);
    }

    /**
     * Mocks {@code Context.getSystemService(BluetoothManager.class)} to return the real {@link
     * BluetoothManager}.
     */
    public static void mockGetBluetoothManager(Context context) {
        final var manager = getBluetoothManager();
        assertThat(manager).isNotNull();
        doReturn(manager).when(context).getSystemService(BluetoothManager.class);
    }

    /**
     * Mocks {@link AdapterService#getRemoteDevice(String)} to return the same {@link
     * BluetoothDevice} object for the same address.
     */
    public static void mockGetRemoteDevice(
            AdapterService adapterService, BluetoothDevice... devices) {
        for (BluetoothDevice device : devices) {
            final String address = device.getAddress();
            lenient().doReturn(device).when(adapterService).getRemoteDevice(address);
        }
    }

    /**
     * Make use of the ExtendedMockito framework to mock the return value of SystemProperty.get.
     * This method require the test to use a {@link StaticMockitoRule}
     */
    public static void mockSystemPropertyGet(String key, boolean value) {
        ExtendedMockito.doReturn(value)
                .when(() -> SystemProperties.getBoolean(eq(key), anyBoolean()));
    }

    /**
     * Make use of the ExtendedMockito framework to mock the return value of SystemProperty.get.
     * This method require the test to use a {@link StaticMockitoRule}
     */
    public static void mockSystemPropertyGet(String key, String value) {
        ExtendedMockito.doReturn(value).when(() -> SystemProperties.get(eq(key), anyString()));
    }

    /**
     * Make use of the ExtendedMockito framework to mock the return value of SystemProperty.get.
     * This method require the test to use a {@link StaticMockitoRule}
     */
    public static void mockSystemPropertyGet(String key, int value) {
        ExtendedMockito.doReturn(value).when(() -> SystemProperties.getInt(eq(key), anyInt()));
    }

    /**
     * Create a mock BluetoothDevice.
     *
     * @param id the test device ID. It must be an integer in the interval [0, 0xFF].
     * @return {@link BluetoothDevice} test device for the device ID
     */
    public static BluetoothDevice getTestDevice(@IntRange(from = 0x00, to = 0xFF) int id) {
        assertThat(id).isAtMost(0xFF);
        final String address = String.format("00:01:02:03:04:%02X", id);
        return getTestDevice(address);
    }

    /**
     * Create a mock BluetoothDevice.
     *
     * @param address the test device address string.
     * @return {@link BluetoothDevice} test device for the device address.
     */
    public static BluetoothDevice getTestDevice(@NonNull String address) {
        BluetoothDevice mockDevice = mock(BluetoothDevice.class);
        doReturn(address).when(mockDevice).getAddress();
        doReturn(address).when(mockDevice).toString();
        return mockDevice;
    }

    /**
     * Create a test device.
     *
     * @param id the test device ID. It must be an integer in the interval [0, 0xFF].
     * @return {@link BluetoothDevice} test device for the device ID
     */
    public static BluetoothDevice getRealDevice(@IntRange(from = 0x00, to = 0xFF) int id) {
        assertThat(id).isAtMost(0xFF);
        final String address = String.format("00:01:02:03:04:%02X", id);
        return getRealDevice(address);
    }

    /**
     * Create a test device.
     *
     * @param address the test device address string.
     * @return {@link BluetoothDevice} test device for the device address.
     */
    public static BluetoothDevice getRealDevice(@NonNull String address) {
        return getDevice(address, BluetoothDevice.ADDRESS_TYPE_PUBLIC);
    }

    /**
     * Create a test device.
     *
     * @param address the test device address string.
     * @param type Bluetooth address type
     * @return {@link BluetoothDevice} test device for the device address.
     */
    public static BluetoothDevice getRealDevice(@NonNull String address, @AddressType int type) {
        return getDevice(address, type);
    }

    private static BluetoothDevice getDevice(String address, @AddressType int type) {
        assertThat(BluetoothAdapter.checkBluetoothAddress(address)).isTrue();
        BluetoothDevice testDevice =
                getBluetoothManager().getAdapter().getRemoteLeDevice(address, type);
        assertThat(testDevice).isNotNull();
        return testDevice;
    }

    public static Resources getTestApplicationResources() {
        try {
            return getContext()
                    .getPackageManager()
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

        void waitForComplete() {
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

    /**
     * Dispatch all the message on the Looper and check that the `what` is expected
     *
     * @param looper looper to execute the message from
     * @param what list of Messages.what that are expected to be run by the handler
     */
    public static void syncHandler(TestLooper looper, int... what) {
        IntStream.of(what).forEach(w -> syncHandlerInternal(looper, w));
    }

    private static void syncHandlerInternal(TestLooper looper, int what) {
        Message msg = looper.nextMessage();
        assertWithMessage("Expecting [" + what + "] instead of null Msg").that(msg).isNotNull();
        if (msg.what != what) {
            List<Message> msgList = new ArrayList<>();

            Message nextMsg;
            while ((nextMsg = looper.nextMessage()) != null) {
                msgList.add(nextMsg);
            }

            String customError =
                    "Not the expected message."
                            + (" Expected what=[" + what + "] but got what=[" + msg.what + "].\n")
                            + ("  -> Received Msg: " + msg + "\n")
                            + ("  -> List of queued messages: " + msgList);

            assertWithMessage(customError).that(msg.what).isEqualTo(what);
        }
        Log.d(TAG, "Processing message: " + msg);
        msg.getTarget().dispatchMessage(msg);
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

            synchronized void waitForIdle() {
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
     * Prepare the intent to start bluetooth browser media service.
     *
     * @return intent with the appropriate component & action set.
     */
    public static Intent prepareIntentToStartBluetoothBrowserMediaService() {
        final Intent intent = new Intent(getContext(), BluetoothMediaBrowserService.class);
        intent.setAction(MediaBrowserService.SERVICE_INTERFACE);
        return intent;
    }
}
