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

package android.bluetooth;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.LOCAL_MAC_ADDRESS;

import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;

import com.android.server.bluetooth.SystemServiceMessage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** @hide */
public class SystemServiceMessenger {
    private static final String TAG = SystemServiceMessenger.class.getSimpleName();

    // See https://en.wikipedia.org/wiki/Initialization-on-demand_holder_idiom
    private static final HandlerThread LAZY_MESSENGER_THREAD = createThread();

    private static HandlerThread createThread() {
        HandlerThread thread = new HandlerThread("Bluetooth System Server Reply");
        thread.start();
        return thread;
    }

    private final Messenger mMessenger;

    public SystemServiceMessenger(Messenger messenger) {
        mMessenger = messenger;
    }

    @RequiresNoPermission
    SystemServiceMessage.RegisterAdapter.Reply send(SystemServiceMessage.RegisterAdapter data) {
        return send(data, SystemServiceMessage.RegisterAdapter.Reply.class);
    }

    @RequiresNoPermission
    SystemServiceMessage.UnregisterAdapter.Reply send(SystemServiceMessage.UnregisterAdapter data) {
        return send(data, SystemServiceMessage.UnregisterAdapter.Reply.class);
    }

    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission") // Messenger doesn't indicate permission
    public SystemServiceMessage.Enable.Reply send(SystemServiceMessage.Enable data) {
        return send(data, SystemServiceMessage.Enable.Reply.class);
    }

    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission") // Messenger doesn't indicate permission
    public SystemServiceMessage.Disable.Reply send(SystemServiceMessage.Disable data) {
        return send(data, SystemServiceMessage.Disable.Reply.class);
    }

    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    @SuppressLint("AndroidFrameworkRequiresPermission") // Messenger doesn't indicate permission
    public SystemServiceMessage.FactoryReset.Reply send(SystemServiceMessage.FactoryReset data) {
        return send(data, SystemServiceMessage.FactoryReset.Reply.class);
    }

    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, LOCAL_MAC_ADDRESS})
    @SuppressLint("AndroidFrameworkRequiresPermission") // Messenger doesn't indicate permission
    SystemServiceMessage.GetAddress.Reply send(SystemServiceMessage.GetAddress data) {
        return send(data, SystemServiceMessage.GetAddress.Reply.class);
    }

    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SuppressLint("AndroidFrameworkRequiresPermission") // Messenger doesn't indicate permission
    SystemServiceMessage.GetName.Reply send(SystemServiceMessage.GetName data) {
        return send(data, SystemServiceMessage.GetName.Reply.class);
    }

    @RequiresNoPermission
    SystemServiceMessage.IsBleScanAvailable.Reply send(
            SystemServiceMessage.IsBleScanAvailable data) {
        return send(data, SystemServiceMessage.IsBleScanAvailable.Reply.class);
    }

    @RequiresNoPermission
    SystemServiceMessage.IsHearingAidSupported.Reply send(
            SystemServiceMessage.IsHearingAidSupported data) {
        return send(data, SystemServiceMessage.IsHearingAidSupported.Reply.class);
    }

    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    @SuppressLint("AndroidFrameworkRequiresPermission") // Messenger doesn't indicate permission
    SystemServiceMessage.SetSnoopLog.Reply send(SystemServiceMessage.SetSnoopLog data) {
        return send(data, SystemServiceMessage.SetSnoopLog.Reply.class);
    }

    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    @SuppressLint("AndroidFrameworkRequiresPermission") // Messenger doesn't indicate permission
    SystemServiceMessage.GetSnoopLog.Reply send(SystemServiceMessage.GetSnoopLog data) {
        return send(data, SystemServiceMessage.GetSnoopLog.Reply.class);
    }

    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    @SuppressLint("AndroidFrameworkRequiresPermission") // Messenger doesn't indicate permission
    SystemServiceMessage.IsAutoSupported.Reply send(SystemServiceMessage.IsAutoSupported data) {
        return send(data, SystemServiceMessage.IsAutoSupported.Reply.class);
    }

    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    @SuppressLint("AndroidFrameworkRequiresPermission") // Messenger doesn't indicate permission
    SystemServiceMessage.IsAutoEnabled.Reply send(SystemServiceMessage.IsAutoEnabled data) {
        return send(data, SystemServiceMessage.IsAutoEnabled.Reply.class);
    }

    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    @SuppressLint("AndroidFrameworkRequiresPermission") // Messenger doesn't indicate permission
    SystemServiceMessage.SetAutoOnEnabled.Reply send(SystemServiceMessage.SetAutoOnEnabled data) {
        return send(data, SystemServiceMessage.SetAutoOnEnabled.Reply.class);
    }

    private <T extends Parcelable, U> U send(T data, Class<U> replyClass) {
        CompletableFuture<U> future = new CompletableFuture();

        Handler.Callback replyFn =
                (reply) -> {
                    Object replyObj = reply.obj;
                    RuntimeException exception =
                            reply.getData().getSerializable("exception", RuntimeException.class);
                    if (exception != null) {
                        future.completeExceptionally(exception);
                    } else if (replyClass.isInstance(replyObj)) {
                        future.complete(replyClass.cast(replyObj));
                    } else {
                        future.completeExceptionally(
                                new IllegalArgumentException(
                                        ("Unexpected reply [" + replyObj + "] returned,")
                                                + (" when calling for [" + data + "].")
                                                + (" Expected value: [" + replyClass + "]")));
                    }
                    return true;
                };
        Message msg = Message.obtain();
        msg.obj = data;
        msg.replyTo = new Messenger(new Handler(LAZY_MESSENGER_THREAD.getLooper(), replyFn));
        try {
            mMessenger.send(msg);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        try {
            return future.orTimeout(10, TimeUnit.SECONDS).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                // Rethrow initial exception while removing the CompletionException wrapper
                throw (RuntimeException) cause;
            }
            throw e;
        }
    }
}
