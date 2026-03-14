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

package com.android.bluetooth.btservice;

import static android.bluetooth.BluetoothDevice.TRANSPORT_AUTO;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.OobData;

import com.android.bluetooth.Util;

import java.io.FileDescriptor;
import java.lang.annotation.Native;

/** Native interface to be used by AdapterService */
public class AdapterNativeInterface {
    private static final String TAG = Util.BT_PREFIX + AdapterNativeInterface.class.getSimpleName();

    @Native private AdapterNativeCallback mNativeCallback;

    AdapterNativeInterface() {}

    AdapterNativeCallback getCallbacks() {
        return mNativeCallback;
    }

    boolean init(
            AdapterService service,
            AdapterProperties adapterProperties,
            boolean startRestricted,
            boolean isCommonCriteriaMode,
            int configCompareResult,
            boolean isAtvDevice,
            String hciInstanceName) {
        mNativeCallback = new AdapterNativeCallback(service, adapterProperties);
        return initNative(
                startRestricted,
                isCommonCriteriaMode,
                configCompareResult,
                isAtvDevice,
                hciInstanceName,
                android.bluetooth.platform.flags.Flags.autonomousRepairingInitiation());
    }

    void cleanup() {
        cleanupNative();
    }

    void enable(String localName) {
        enableNative(localName);
    }

    void disable() {
        disableNative();
    }

    boolean setScanMode(int mode) {
        return setScanModeNative(mode);
    }

    void setLocalName(String localName) {
        setLocalNameNative(localName);
    }

    boolean setAdapterProperty(int type, byte[] val) {
        return setAdapterPropertyNative(type, val);
    }

    boolean getAdapterProperty(int type) {
        return getAdapterPropertyNative(type);
    }

    boolean setDeviceProperty(byte[] address, int type, byte[] val) {
        return setDevicePropertyNative(address, type, val);
    }

    boolean getDeviceProperty(byte[] address, int type) {
        return getDevicePropertyNative(address, type);
    }

    boolean createBond(byte[] address, int addressType, int transport) {
        return createBondNative(address, addressType, transport);
    }

    boolean createBondOutOfBand(byte[] address, int transport, OobData p192Data, OobData p256Data) {
        return createBondOutOfBandNative(address, transport, p192Data, p256Data);
    }

    boolean removeBond(byte[] address) {
        return removeBondNative(address);
    }

    boolean cancelBond(byte[] address) {
        return cancelBondNative(address);
    }

    boolean pairingIsBusy() {
        return pairingIsBusyNative();
    }

    void generateLocalOobData(int transport) {
        generateLocalOobDataNative(transport);
    }

    boolean sdpSearch(byte[] address, byte[] uuid) {
        return sdpSearchNative(address, uuid);
    }

    boolean startDiscovery() {
        return startDiscoveryNative();
    }

    boolean cancelDiscovery() {
        return cancelDiscoveryNative();
    }

    boolean pinReply(byte[] address, boolean accept, int len, byte[] pin) {
        return pinReplyNative(address, accept, len, pin);
    }

    boolean sspReply(byte[] address, int type, boolean accept, int passkey) {
        return sspReplyNative(address, type, accept, passkey);
    }

    boolean getRemoteServices(byte[] address, int transport) {
        return getRemoteServicesNative(address, transport);
    }

    boolean getRemoteMasInstances(byte[] address) {
        return getRemoteMasInstancesNative(address);
    }

    int readEnergyInfo() {
        return readEnergyInfoNative();
    }

    void dump(FileDescriptor fd, String[] arguments) {
        dumpNative(fd, arguments);
    }

    byte[] obfuscateAddress(byte[] address) {
        return obfuscateAddressNative(address);
    }

    boolean setBufferLengthMillis(int codec, int value) {
        return setBufferLengthMillisNative(codec, value);
    }

    int getMetricId(byte[] address) {
        return getMetricIdNative(address);
    }

    int connectSocket(
            byte[] address,
            int type,
            byte[] uuid,
            int port,
            int flag,
            int callingUid,
            int dataPath,
            String socketName,
            long hubId,
            long endpointId,
            int maximumPacketSize) {
        return connectSocketNative(
                address,
                type,
                uuid,
                port,
                flag,
                callingUid,
                dataPath,
                socketName,
                hubId,
                endpointId,
                maximumPacketSize);
    }

    int createSocketChannel(
            int type,
            String serviceName,
            byte[] uuid,
            int port,
            int flag,
            int callingUid,
            int dataPath,
            String socketName,
            long hubId,
            long endpointId,
            int maximumPacketSize) {
        return createSocketChannelNative(
                type,
                serviceName,
                uuid,
                port,
                flag,
                callingUid,
                dataPath,
                socketName,
                hubId,
                endpointId,
                maximumPacketSize);
    }

    void requestMaximumTxDataLength(byte[] address) {
        requestMaximumTxDataLengthNative(address);
    }

    boolean allowLowLatencyAudio(boolean allowed, byte[] address) {
        return allowLowLatencyAudioNative(allowed, address);
    }

    void metadataChanged(BluetoothDevice device, int key, byte[] value) {
        metadataChangedNative(Util.getBytesFromAddress(device.getAddress()), key, value);
    }

    boolean interopMatchAddrOrName(String featureName, String address) {
        return interopMatchAddrOrNameNative(featureName, address);
    }

    int getRemotePbapPceVersion(String address) {
        return getRemotePbapPceVersionNative(address);
    }

    boolean pbapPseDynamicVersionUpgradeIsEnabled() {
        return pbapPseDynamicVersionUpgradeIsEnabledNative();
    }

    boolean setDefaultEventMaskExcept(long mask, long leMask) {
        return setDefaultEventMaskExceptNative(mask, leMask);
    }

    boolean clearEventFilter() {
        return clearEventFilterNative();
    }

    boolean clearFilterAcceptList() {
        return clearFilterAcceptListNative();
    }

    boolean disconnectAllAcls() {
        return disconnectAllAclsNative();
    }

    boolean disconnectAllAcls(BluetoothDevice device) {
        return disconnectAcl(device, TRANSPORT_AUTO);
    }

    boolean disconnectAcl(BluetoothDevice device, int transport) {
        return disconnectAclNative(Util.getBytesFromAddress(device.getAddress()), transport);
    }

    boolean allowWakeByHid() {
        return allowWakeByHidNative();
    }

    boolean restoreFilterAcceptList() {
        return restoreFilterAcceptListNative();
    }

    boolean setSuspendState(boolean suspend) {
        return setSuspendStateNative(suspend);
    }

    private native boolean initNative(
            boolean startRestricted,
            boolean isCommonCriteriaMode,
            int configCompareResult,
            boolean isAtvDevice,
            String hciInstanceName,
            boolean autonomousRepairingInitiation);

    private native void cleanupNative();

    private native void enableNative(String localName);

    private native void disableNative();

    private native boolean setScanModeNative(int mode);

    private native void setLocalNameNative(String localName);

    private native boolean setAdapterPropertyNative(int type, byte[] val);

    private native boolean getAdapterPropertyNative(int type);

    private native boolean setDevicePropertyNative(byte[] address, int type, byte[] val);

    private native boolean getDevicePropertyNative(byte[] address, int type);

    private native boolean createBondNative(byte[] address, int addressType, int transport);

    private native boolean createBondOutOfBandNative(
            byte[] address, int transport, OobData p192Data, OobData p256Data);

    private native boolean removeBondNative(byte[] address);

    private native boolean cancelBondNative(byte[] address);

    private native boolean pairingIsBusyNative();

    private native void generateLocalOobDataNative(int transport);

    private native boolean sdpSearchNative(byte[] address, byte[] uuid);

    private native boolean startDiscoveryNative();

    private native boolean cancelDiscoveryNative();

    private native boolean pinReplyNative(byte[] address, boolean accept, int len, byte[] pin);

    private native boolean sspReplyNative(byte[] address, int type, boolean accept, int passkey);

    private native boolean getRemoteServicesNative(byte[] address, int transport);

    private native boolean getRemoteMasInstancesNative(byte[] address);

    private native int readEnergyInfoNative();

    private native void dumpNative(FileDescriptor fd, String[] arguments);

    private native byte[] obfuscateAddressNative(byte[] address);

    private native boolean setBufferLengthMillisNative(int codec, int value);

    private native int getMetricIdNative(byte[] address);

    private native int connectSocketNative(
            byte[] address,
            int type,
            byte[] uuid,
            int port,
            int flag,
            int callingUid,
            int dataPath,
            String socketName,
            long hubId,
            long endpointId,
            int maximumPacketSize);

    private native int createSocketChannelNative(
            int type,
            String serviceName,
            byte[] uuid,
            int port,
            int flag,
            int callingUid,
            int dataPath,
            String socketName,
            long hubId,
            long endpointId,
            int maximumPacketSize);

    private native void requestMaximumTxDataLengthNative(byte[] address);

    private native boolean allowLowLatencyAudioNative(boolean allowed, byte[] address);

    private native void metadataChangedNative(byte[] address, int key, byte[] value);

    private native boolean interopMatchAddrOrNameNative(String featureName, String address);

    private native void interopDatabaseAddRemoveAddrNative(
            boolean doAdd, String featureName, String address, int length);

    private native void interopDatabaseAddRemoveNameNative(
            boolean doAdd, String featureName, String name);

    private native int getRemotePbapPceVersionNative(String address);

    private native boolean pbapPseDynamicVersionUpgradeIsEnabledNative();

    private native boolean setDefaultEventMaskExceptNative(long mask, long leMask);

    private native boolean clearEventFilterNative();

    private native boolean clearFilterAcceptListNative();

    private native boolean disconnectAllAclsNative();

    private native boolean disconnectAclNative(byte[] address, int transport);

    private native boolean allowWakeByHidNative();

    private native boolean restoreFilterAcceptListNative();

    private native boolean setSuspendStateNative(boolean suspend);
}
