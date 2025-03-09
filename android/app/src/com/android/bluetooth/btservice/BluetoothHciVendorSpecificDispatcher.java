/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.bluetooth.btservice;

import static android.bluetooth.BluetoothUtils.RemoteExceptionIgnoringConsumer;

import android.bluetooth.IBluetoothHciVendorSpecificCallback;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.nio.ByteBuffer;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class BluetoothHciVendorSpecificDispatcher {
    private static final String TAG = BluetoothHciVendorSpecificDispatcher.class.getSimpleName();

    private final class Registration implements IBinder.DeathRecipient {
        final IBluetoothHciVendorSpecificCallback mCallback;
        final Set<Integer> mEventCodes;
        final UUID mUuid;

        Registration(IBluetoothHciVendorSpecificCallback callback, Set<Integer> eventCodes) {
            mCallback = callback;
            mEventCodes = eventCodes;
            mUuid = UUID.randomUUID();
        }

        public void binderDied() {
            synchronized (mRegistrations) {
                mRegistrations.remove(mCallback.asBinder());
            }
        }
    }

    @GuardedBy("mRegistrations")
    private final ArrayMap<IBinder, Registration> mRegistrations = new ArrayMap<>();

    void unregister(IBluetoothHciVendorSpecificCallback callback) {
        IBinder binder = callback.asBinder();
        synchronized (mRegistrations) {
            Registration registration = mRegistrations.remove(binder);
            if (registration == null) {
                throw new IllegalStateException("callback was never registered");
            }

            binder.unlinkToDeath(registration, 0);
        }
    }

    void register(IBluetoothHciVendorSpecificCallback callback, Set<Integer> eventCodes) {
        IBinder binder = callback.asBinder();
        synchronized (mRegistrations) {
            if (mRegistrations.containsKey(binder)) {
                throw new IllegalStateException("callback already registered");
            }

            try {
                Registration registration = new Registration(callback, eventCodes);
                binder.linkToDeath(registration, 0);
                mRegistrations.put(binder, registration);
            } catch (RemoteException e) {
                Log.e(TAG, "link to death", e);
            }
        }
    }

    Optional<byte[]> getRegisteredCookie(IBluetoothHciVendorSpecificCallback callback) {
        IBinder binder = callback.asBinder();
        synchronized (mRegistrations) {
            if (!mRegistrations.containsKey(binder)) return Optional.empty();

            Registration registration = mRegistrations.get(binder);
            ByteBuffer cookieBb = ByteBuffer.allocate(16);
            cookieBb.putLong(registration.mUuid.getMostSignificantBits());
            cookieBb.putLong(registration.mUuid.getLeastSignificantBits());
            return Optional.of(cookieBb.array());
        }
    }

    void dispatchCommandStatusOrComplete(
            byte[] cookie,
            RemoteExceptionIgnoringConsumer<IBluetoothHciVendorSpecificCallback> action) {
        ByteBuffer cookieBb = ByteBuffer.wrap(cookie);
        UUID uuid = new UUID(cookieBb.getLong(), cookieBb.getLong());
        synchronized (mRegistrations) {
            try {
                Registration registration =
                        mRegistrations.values().stream()
                                .filter((r) -> r.mUuid.equals(uuid))
                                .findAny()
                                .get();
                action.accept(registration.mCallback);
            } catch (NoSuchElementException e) {
                Log.e(TAG, "Command status or complete owner not registered");
                return;
            }
        }
    }

    void broadcastEvent(
            int eventCode,
            RemoteExceptionIgnoringConsumer<IBluetoothHciVendorSpecificCallback> action) {
        synchronized (mRegistrations) {
            mRegistrations.values().stream()
                    .filter((r) -> r.mEventCodes.contains(eventCode))
                    .forEach((r) -> action.accept(r.mCallback));
        }
    }
}
