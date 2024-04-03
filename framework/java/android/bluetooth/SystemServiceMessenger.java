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

import static java.util.Objects.requireNonNull;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;

import com.android.server.bluetooth.SystemServiceMessage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class SystemServiceMessenger {
    private static final String TAG = SystemServiceMessenger.class.getSimpleName();

    // See https://en.wikipedia.org/wiki/Initialization-on-demand_holder_idiom
    private static final HandlerThread LAZY_MESSENGER_THREAD = createThread();

    private static HandlerThread createThread() {
        HandlerThread thread = new HandlerThread("Bluetooth System Server Reply");
        thread.start();
        return thread;
    }

    private final Messenger mMessenger;

    SystemServiceMessenger(IBluetoothManager managerService) {
        try {
            mMessenger = requireNonNull(managerService.getServiceMessenger());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    SystemServiceMessage.RegisterAdapter.Reply send(SystemServiceMessage.RegisterAdapter data) {
        return send(data, SystemServiceMessage.RegisterAdapter.Reply.class);
    }

    SystemServiceMessage.UnregisterAdapter.Reply send(SystemServiceMessage.UnregisterAdapter data) {
        return send(data, SystemServiceMessage.UnregisterAdapter.Reply.class);
    }

    SystemServiceMessage.Enable.Reply send(SystemServiceMessage.Enable data) {
        return send(data, SystemServiceMessage.Enable.Reply.class);
    }

    SystemServiceMessage.Disable.Reply send(SystemServiceMessage.Disable data) {
        return send(data, SystemServiceMessage.Disable.Reply.class);
    }

    SystemServiceMessage.FactoryReset.Reply send(SystemServiceMessage.FactoryReset data) {
        return send(data, SystemServiceMessage.FactoryReset.Reply.class);
    }

    SystemServiceMessage.GetAddress.Reply send(SystemServiceMessage.GetAddress data) {
        return send(data, SystemServiceMessage.GetAddress.Reply.class);
    }

    SystemServiceMessage.GetName.Reply send(SystemServiceMessage.GetName data) {
        return send(data, SystemServiceMessage.GetName.Reply.class);
    }

    SystemServiceMessage.IsBleScanAvailable.Reply send(
            SystemServiceMessage.IsBleScanAvailable data) {
        return send(data, SystemServiceMessage.IsBleScanAvailable.Reply.class);
    }

    SystemServiceMessage.IsHearingAidSupported.Reply send(
            SystemServiceMessage.IsHearingAidSupported data) {
        return send(data, SystemServiceMessage.IsHearingAidSupported.Reply.class);
    }

    SystemServiceMessage.SetSnoopLog.Reply send(SystemServiceMessage.SetSnoopLog data) {
        return send(data, SystemServiceMessage.SetSnoopLog.Reply.class);
    }

    SystemServiceMessage.GetSnoopLog.Reply send(SystemServiceMessage.GetSnoopLog data) {
        return send(data, SystemServiceMessage.GetSnoopLog.Reply.class);
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
        return future.orTimeout(1, TimeUnit.SECONDS).join();
    }
}
