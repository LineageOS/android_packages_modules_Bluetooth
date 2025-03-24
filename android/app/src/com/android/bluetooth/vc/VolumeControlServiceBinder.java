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

package com.android.bluetooth.vc;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.AudioInputControl.AudioInputStatus;
import android.bluetooth.AudioInputControl.AudioInputType;
import android.bluetooth.AudioInputControl.GainMode;
import android.bluetooth.AudioInputControl.Mute;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IAudioInputCallback;
import android.bluetooth.IBluetoothVolumeControl;
import android.bluetooth.IBluetoothVolumeControlCallback;
import android.content.AttributionSource;
import android.os.Handler;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.SneakyThrow;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

class VolumeControlServiceBinder extends IBluetoothVolumeControl.Stub
        implements IProfileServiceBinder {
    private static final String TAG = VolumeControlServiceBinder.class.getSimpleName();

    @VisibleForTesting boolean mIsTesting = false;
    private VolumeControlService mService;

    VolumeControlServiceBinder(VolumeControlService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private VolumeControlService getService(AttributionSource source) {
        requireNonNull(source);

        VolumeControlService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            return null;
        }

        return service;
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        VolumeControlService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectedDevices();
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        VolumeControlService service = getService(source);
        if (service == null) {
            return Collections.emptyList();
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);

        VolumeControlService service = getService(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        requireNonNull(device);

        VolumeControlService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);

        VolumeControlService service = getService(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionPolicy(device);
    }

    @Override
    public boolean isVolumeOffsetAvailable(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);

        VolumeControlService service = getService(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.isVolumeOffsetAvailable(device);
    }

    @Override
    public int getNumberOfVolumeOffsetInstances(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);

        VolumeControlService service = getService(source);
        if (service == null) {
            return 0;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getNumberOfVolumeOffsetInstances(device);
    }

    @Override
    public void setVolumeOffset(
            BluetoothDevice device, int instanceId, int volumeOffset, AttributionSource source) {
        requireNonNull(device);

        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setVolumeOffset(device, instanceId, volumeOffset);
    }

    @Override
    public void setDeviceVolume(
            BluetoothDevice device, int volume, boolean isGroupOp, AttributionSource source) {
        requireNonNull(device);

        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setDeviceVolume(device, volume, isGroupOp);
    }

    @Override
    public void setGroupVolume(int groupId, int volume, AttributionSource source) {
        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }
        service.setGroupVolume(groupId, volume);
    }

    @Override
    public int getGroupVolume(int groupId, AttributionSource source) {
        VolumeControlService service = getService(source);
        if (service == null) {
            return 0;
        }
        return service.getGroupVolume(groupId);
    }

    @Override
    public void setGroupActive(int groupId, boolean active, AttributionSource source) {
        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }
        service.setGroupActive(groupId, active);
    }

    @Override
    public void mute(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);

        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }
        service.mute(device);
    }

    @Override
    public void muteGroup(int groupId, AttributionSource source) {
        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }
        service.muteGroup(groupId);
    }

    @Override
    public void unmute(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);

        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }
        service.unmute(device);
    }

    @Override
    public void unmuteGroup(int groupId, AttributionSource source) {
        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }
        service.unmuteGroup(groupId);
    }

    private static void postAndWait(Handler handler, Runnable runnable) {
        FutureTask<Void> task = new FutureTask(Executors.callable(runnable));

        handler.post(task);
        try {
            task.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            SneakyThrow.sneakyThrow(e);
        } catch (ExecutionException e) {
            SneakyThrow.sneakyThrow(e.getCause());
        }
    }

    @Override
    public void registerCallback(
            IBluetoothVolumeControlCallback callback, AttributionSource source) {
        requireNonNull(callback);

        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        postAndWait(service.getHandler(), () -> service.registerCallback(callback));
    }

    @Override
    public void unregisterCallback(
            IBluetoothVolumeControlCallback callback, AttributionSource source) {
        requireNonNull(callback);

        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        postAndWait(service.getHandler(), () -> service.unregisterCallback(callback));
    }

    @Override
    public void notifyNewRegisteredCallback(
            IBluetoothVolumeControlCallback callback, AttributionSource source) {
        requireNonNull(callback);

        VolumeControlService service = getService(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        postAndWait(service.getHandler(), () -> service.notifyNewRegisteredCallback(callback));
    }

    private static void validateBluetoothDevice(BluetoothDevice device) {
        requireNonNull(device);
        String address = device.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IllegalArgumentException("Invalid device address: " + address);
        }
    }

    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    private <R> R aicsWrapper(
            AttributionSource source,
            BluetoothDevice device,
            Function<VolumeControlInputDescriptor, R> fn,
            R defaultValue) {
        validateBluetoothDevice(device);

        VolumeControlService service = getService(source);
        if (service == null) {
            return defaultValue;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

        VolumeControlInputDescriptor inputs = service.getAudioInputs().get(device);
        if (inputs == null) {
            Log.w(TAG, "No audio inputs for " + device);
            return defaultValue;
        }

        return fn.apply(inputs);
    }

    @Override
    public int getNumberOfAudioInputControlServices(
            AttributionSource source, BluetoothDevice device) {
        validateBluetoothDevice(device);
        Log.d(TAG, "getNumberOfAudioInputControlServices(" + device + ")");
        return aicsWrapper(source, device, i -> i.size(), 0);
    }

    @Override
    public void registerAudioInputControlCallback(
            AttributionSource source,
            BluetoothDevice device,
            int instanceId,
            IAudioInputCallback callback) {
        requireNonNull(callback);
        Log.d(
                TAG,
                "registerAudioInputControlCallback("
                        + (device + ", " + instanceId + ", " + callback)
                        + ")");
        aicsWrapper(
                source,
                device,
                i -> {
                    i.registerCallback(instanceId, callback);
                    return null;
                },
                null);
    }

    @Override
    public void unregisterAudioInputControlCallback(
            AttributionSource source,
            BluetoothDevice device,
            int instanceId,
            IAudioInputCallback callback) {
        requireNonNull(callback);
        Log.d(
                TAG,
                "unregisterAudioInputControlCallback("
                        + (device + ", " + instanceId + ", " + callback)
                        + ")");
        aicsWrapper(
                source,
                device,
                i -> {
                    i.unregisterCallback(instanceId, callback);
                    return null;
                },
                null);
    }

    @Override
    public int getAudioInputGainSettingUnit(
            AttributionSource source, BluetoothDevice device, int instanceId) {
        Log.d(TAG, "getAudioInputGainSettingUnit(" + device + ", " + instanceId + ")");
        return aicsWrapper(source, device, i -> i.getGainSettingUnit(instanceId), 0);
    }

    @Override
    public int getAudioInputGainSettingMin(
            AttributionSource source, BluetoothDevice device, int instanceId) {
        Log.d(TAG, "getAudioInputGainSettingMin(" + device + ", " + instanceId + ")");
        return aicsWrapper(source, device, i -> i.getGainSettingMin(instanceId), 0);
    }

    @Override
    public int getAudioInputGainSettingMax(
            AttributionSource source, BluetoothDevice device, int instanceId) {
        Log.d(TAG, "getAudioInputGainSettingMax(" + device + ", " + instanceId + ")");
        return aicsWrapper(source, device, i -> i.getGainSettingMax(instanceId), 0);
    }

    @Override
    public String getAudioInputDescription(
            AttributionSource source, BluetoothDevice device, int instanceId) {
        Log.d(TAG, "getAudioInputDescription(" + device + ", " + instanceId + ")");
        return aicsWrapper(source, device, i -> i.getDescription(instanceId), "");
    }

    @Override
    public boolean isAudioInputDescriptionWritable(
            AttributionSource source, BluetoothDevice device, int instanceId) {
        Log.d(TAG, "isAudioInputDescriptionWritable(" + device + ", " + instanceId + ")");
        return aicsWrapper(source, device, i -> i.isDescriptionWritable(instanceId), false);
    }

    @Override
    public boolean setAudioInputDescription(
            AttributionSource source, BluetoothDevice device, int instanceId, String description) {
        requireNonNull(description);
        Log.d(TAG, "setAudioInputDescription(" + device + ", " + instanceId + ")");
        return aicsWrapper(source, device, i -> i.setDescription(instanceId, description), false);
    }

    @Override
    public @AudioInputStatus int getAudioInputStatus(
            AttributionSource source, BluetoothDevice device, int instanceId) {
        Log.d(TAG, "getAudioInputStatus(" + device + ", " + instanceId + ")");
        return aicsWrapper(
                source,
                device,
                i -> i.getStatus(instanceId),
                (int) bluetooth.constants.aics.AudioInputStatus.INACTIVE);
    }

    @Override
    public @AudioInputType int getAudioInputType(
            AttributionSource source, BluetoothDevice device, int instanceId) {
        Log.d(TAG, "getAudioInputType(" + device + ", " + instanceId + ")");
        return aicsWrapper(
                source,
                device,
                i -> i.getType(instanceId),
                bluetooth.constants.AudioInputType.UNSPECIFIED);
    }

    @Override
    public int getAudioInputGainSetting(
            AttributionSource source, BluetoothDevice device, int instanceId) {
        Log.d(TAG, "getAudioInputGainSetting(" + device + ", " + instanceId + ")");
        return aicsWrapper(source, device, i -> i.getGainSetting(instanceId), 0);
    }

    @Override
    public boolean setAudioInputGainSetting(
            AttributionSource source, BluetoothDevice device, int instanceId, int gainSetting) {
        Log.d(TAG, "setAudioInputGainSetting(" + device + ", " + instanceId + ")");
        return aicsWrapper(source, device, i -> i.setGainSetting(instanceId, gainSetting), false);
    }

    @Override
    public @GainMode int getAudioInputGainMode(
            AttributionSource source, BluetoothDevice device, int instanceId) {
        Log.d(TAG, "getAudioInputGainMode(" + device + ", " + instanceId + ")");
        return aicsWrapper(
                source,
                device,
                i -> i.getGainMode(instanceId),
                (int) bluetooth.constants.aics.GainMode.AUTOMATIC_ONLY);
    }

    @Override
    public boolean setAudioInputGainMode(
            AttributionSource source,
            BluetoothDevice device,
            int instanceId,
            @GainMode int gainMode) {
        Log.d(TAG, "setAudioInputGainMode(" + device + ", " + instanceId + ")");
        return aicsWrapper(source, device, i -> i.setGainMode(instanceId, gainMode), false);
    }

    @Override
    public @Mute int getAudioInputMute(
            AttributionSource source, BluetoothDevice device, int instanceId) {
        Log.d(TAG, "getAudioInputMute(" + device + ", " + instanceId + ")");
        return aicsWrapper(
                source,
                device,
                i -> i.getMute(instanceId),
                (int) bluetooth.constants.aics.Mute.DISABLED);
    }

    @Override
    public boolean setAudioInputMute(
            AttributionSource source, BluetoothDevice device, int instanceId, @Mute int mute) {
        Log.d(TAG, "setAudioInputMute(" + device + ", " + instanceId + ")");
        return aicsWrapper(source, device, i -> i.setMute(instanceId, mute), false);
    }
}
