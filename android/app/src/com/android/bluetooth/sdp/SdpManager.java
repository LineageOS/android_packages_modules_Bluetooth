/*
* Copyright (C) 2015 Samsung System LSI
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
package com.android.bluetooth.sdp;

import static android.Manifest.permission.BLUETOOTH_CONNECT;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.SdpDipRecord;
import android.bluetooth.SdpMasRecord;
import android.bluetooth.SdpMnsRecord;
import android.bluetooth.SdpOppOpsRecord;
import android.bluetooth.SdpPseRecord;
import android.bluetooth.SdpRecord;
import android.bluetooth.SdpSapsRecord;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AbstractionLayer;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Arrays;

public class SdpManager {
    private static final String TAG = SdpManager.class.getSimpleName();

    private static final Object TRACKER_LOCK = new Object();

    /* The timeout to wait for reply from native. Should never fire. */
    private static final int SDP_INTENT_DELAY = 11000;
    private static final int MESSAGE_SDP_INTENT = 2;

    /* Variables to keep track of ongoing and queued search requests.
     * mTrackerLock must be held, when using/changing mSdpSearchTracker
     * and mSearchInProgress. */
    @GuardedBy("TRACKER_LOCK")
    private final SdpSearchTracker mSdpSearchTracker = new SdpSearchTracker();

    private final AdapterService mAdapterService;
    private final Handler mHandler;
    private final SdpManagerNativeInterface mNativeInterface =
            SdpManagerNativeInterface.getInstance();

    private boolean mSearchInProgress = false;
    private boolean mNativeAvailable;

    /* Inner class used for wrapping sdp search instance data */
    private class SdpSearchInstance {
        private final BluetoothDevice mDevice;
        private final ParcelUuid mUuid;
        private int mStatus = 0;
        private boolean mSearching;

        /* TODO: If we change the API to use another mechanism than intents for
         *       delivering the results, this would be the place to keep a list
         *       of the objects to deliver the results to. */
        SdpSearchInstance(int status, BluetoothDevice device, ParcelUuid uuid) {
            this.mDevice = device;
            this.mUuid = uuid;
            this.mStatus = status;
            mSearching = true;
        }

        public BluetoothDevice getDevice() {
            return mDevice;
        }

        public ParcelUuid getUuid() {
            return mUuid;
        }

        public int getStatus() {
            return mStatus;
        }

        public void setStatus(int status) {
            this.mStatus = status;
        }

        public void startSearch() {
            mSearching = true;
            Message message = mHandler.obtainMessage(MESSAGE_SDP_INTENT, this);
            mHandler.sendMessageDelayed(message, SDP_INTENT_DELAY);
        }

        public void stopSearch() {
            if (mSearching) {
                mHandler.removeMessages(MESSAGE_SDP_INTENT, this);
            }
            mSearching = false;
        }

        public boolean isSearching() {
            return mSearching;
        }
    }

    /* We wrap the ArrayList class to decorate with functionality to
     * find an instance based on UUID AND device address.
     * As we use a mix of byte[] and object instances, this is more
     * efficient than implementing comparable. */
    class SdpSearchTracker {
        private final ArrayList<SdpSearchInstance> mList = new ArrayList<>();

        void clear() {
            mList.clear();
        }

        boolean add(SdpSearchInstance inst) {
            return mList.add(inst);
        }

        boolean remove(SdpSearchInstance inst) {
            return mList.remove(inst);
        }

        SdpSearchInstance getNext() {
            if (mList.size() > 0) {
                return mList.get(0);
            }
            return null;
        }

        SdpSearchInstance getSearchInstance(byte[] address, byte[] uuidBytes) {
            String addressString = Utils.getAddressStringFromByte(address);
            addressString =
                    Flags.identityAddressNullIfUnknown()
                            ? Utils.getBrEdrAddress(addressString, mAdapterService)
                            : mAdapterService.getIdentityAddress(addressString);
            ParcelUuid uuid = Utils.byteArrayToUuid(uuidBytes)[0];
            for (SdpSearchInstance inst : mList) {
                String instAddressString =
                        Flags.identityAddressNullIfUnknown()
                                ? Utils.getBrEdrAddress(inst.getDevice(), mAdapterService)
                                : mAdapterService.getIdentityAddress(inst.getDevice().getAddress());
                if (instAddressString.equals(addressString) && inst.getUuid().equals(uuid)) {
                    return inst;
                }
            }
            return null;
        }

        boolean isSearching(BluetoothDevice device, ParcelUuid uuid) {
            String addressString =
                    Flags.identityAddressNullIfUnknown()
                            ? Utils.getBrEdrAddress(device, mAdapterService)
                            : mAdapterService.getIdentityAddress(device.getAddress());
            for (SdpSearchInstance inst : mList) {
                String instAddressString =
                        Flags.identityAddressNullIfUnknown()
                                ? Utils.getBrEdrAddress(inst.getDevice(), mAdapterService)
                                : mAdapterService.getIdentityAddress(inst.getDevice().getAddress());
                if (instAddressString != null
                        && instAddressString.equals(addressString)
                        && inst.getUuid().equals(uuid)) {
                    return inst.isSearching();
                }
            }
            return false;
        }
    }

    public SdpManager(AdapterService adapterService) {
        this(adapterService, Looper.myLooper());
    }

    public SdpManager(AdapterService adapterService, Looper looper) {
        mAdapterService = adapterService;
        mNativeInterface.init(this);
        mNativeAvailable = true;
        mHandler =
                new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        switch (msg.what) {
                            case MESSAGE_SDP_INTENT:
                                SdpSearchInstance msgObj = (SdpSearchInstance) msg.obj;
                                Log.w(TAG, "Search timedout for UUID " + msgObj.getUuid());
                                synchronized (TRACKER_LOCK) {
                                    sendSdpIntent(msgObj, null, false);
                                }
                                break;
                        }
                    }
                };
    }

    public void cleanup() {
        synchronized (TRACKER_LOCK) {
            mSdpSearchTracker.clear();
        }

        if (mNativeAvailable) {
            mNativeInterface.cleanup();
            mNativeAvailable = false;
        }
    }


    void sdpMasRecordFoundCallback(int status, byte[] address, byte[] uuid, int masInstanceId,
            int l2capPsm, int rfcommCannelNumber, int profileVersion, int supportedFeatures,
            int supportedMessageTypes, String serviceName, boolean moreResults) {

        synchronized (TRACKER_LOCK) {
            SdpSearchInstance inst = mSdpSearchTracker.getSearchInstance(address, uuid);
            SdpMasRecord sdpRecord = null;
            if (inst == null) {
                Log.e(TAG, "sdpMasRecordFoundCallback: Search instance is NULL");
                return;
            }
            inst.setStatus(status);
            if (status == AbstractionLayer.BT_STATUS_SUCCESS) {
                sdpRecord = new SdpMasRecord(masInstanceId, l2capPsm, rfcommCannelNumber,
                        profileVersion, supportedFeatures, supportedMessageTypes, serviceName);
            }
            Log.d(TAG, "UUID: " + Arrays.toString(uuid));
            Log.d(TAG, "UUID in parcel: " + ((Utils.byteArrayToUuid(uuid))[0]).toString());
            sendSdpIntent(inst, sdpRecord, moreResults);
        }
    }

    void sdpMnsRecordFoundCallback(int status, byte[] address, byte[] uuid, int l2capPsm,
            int rfcommCannelNumber, int profileVersion, int supportedFeatures, String serviceName,
            boolean moreResults) {
        synchronized (TRACKER_LOCK) {
            SdpSearchInstance inst = mSdpSearchTracker.getSearchInstance(address, uuid);
            SdpMnsRecord sdpRecord = null;
            if (inst == null) {
                Log.e(TAG, "sdpMnsRecordFoundCallback: Search instance is NULL");
                return;
            }
            inst.setStatus(status);
            if (status == AbstractionLayer.BT_STATUS_SUCCESS) {
                sdpRecord = new SdpMnsRecord(l2capPsm, rfcommCannelNumber, profileVersion,
                        supportedFeatures, serviceName);
            }
            Log.d(TAG, "UUID: " + Arrays.toString(uuid));
            Log.d(TAG, "UUID in parcel: " + ((Utils.byteArrayToUuid(uuid))[0]).toString());
            sendSdpIntent(inst, sdpRecord, moreResults);
        }
    }

    void sdpPseRecordFoundCallback(int status, byte[] address, byte[] uuid, int l2capPsm,
            int rfcommCannelNumber, int profileVersion, int supportedFeatures,
            int supportedRepositories, String serviceName, boolean moreResults) {
        synchronized (TRACKER_LOCK) {
            SdpSearchInstance inst = mSdpSearchTracker.getSearchInstance(address, uuid);
            SdpPseRecord sdpRecord = null;
            if (inst == null) {
                Log.e(TAG, "sdpPseRecordFoundCallback: Search instance is NULL");
                return;
            }
            inst.setStatus(status);
            if (status == AbstractionLayer.BT_STATUS_SUCCESS) {
                sdpRecord = new SdpPseRecord(l2capPsm, rfcommCannelNumber, profileVersion,
                        supportedFeatures, supportedRepositories, serviceName);
            }
            Log.d(TAG, "UUID: " + Arrays.toString(uuid));
            Log.d(TAG, "UUID in parcel: " + ((Utils.byteArrayToUuid(uuid))[0]).toString());
            sendSdpIntent(inst, sdpRecord, moreResults);
        }
    }

    void sdpOppOpsRecordFoundCallback(int status, byte[] address, byte[] uuid, int l2capPsm,
            int rfcommCannelNumber, int profileVersion, String serviceName, byte[] formatsList,
            boolean moreResults) {

        synchronized (TRACKER_LOCK) {
            SdpSearchInstance inst = mSdpSearchTracker.getSearchInstance(address, uuid);
            SdpOppOpsRecord sdpRecord = null;

            if (inst == null) {
                Log.e(TAG, "sdpOppOpsRecordFoundCallback: Search instance is NULL");
                return;
            }
            inst.setStatus(status);
            if (status == AbstractionLayer.BT_STATUS_SUCCESS) {
                sdpRecord = new SdpOppOpsRecord(serviceName, rfcommCannelNumber, l2capPsm,
                        profileVersion, formatsList);
            }
            Log.d(TAG, "UUID: " + Arrays.toString(uuid));
            Log.d(TAG, "UUID in parcel: " + ((Utils.byteArrayToUuid(uuid))[0]).toString());
            sendSdpIntent(inst, sdpRecord, moreResults);
        }
    }

    void sdpSapsRecordFoundCallback(int status, byte[] address, byte[] uuid, int rfcommCannelNumber,
            int profileVersion, String serviceName, boolean moreResults) {

        synchronized (TRACKER_LOCK) {
            SdpSearchInstance inst = mSdpSearchTracker.getSearchInstance(address, uuid);
            SdpSapsRecord sdpRecord = null;
            if (inst == null) {
                Log.e(TAG, "sdpSapsRecordFoundCallback: Search instance is NULL");
                return;
            }
            inst.setStatus(status);
            if (status == AbstractionLayer.BT_STATUS_SUCCESS) {
                sdpRecord = new SdpSapsRecord(rfcommCannelNumber, profileVersion, serviceName);
            }
            Log.d(TAG, "UUID: " + Arrays.toString(uuid));
            Log.d(TAG, "UUID in parcel: " + ((Utils.byteArrayToUuid(uuid))[0]).toString());
            sendSdpIntent(inst, sdpRecord, moreResults);
        }
    }

    void sdpDipRecordFoundCallback(
            int status,
            byte[] address,
            byte[] uuid,
            int specificationId,
            int vendorId,
            int vendorIdSource,
            int productId,
            int version,
            boolean primaryRecord,
            boolean moreResults) {
        synchronized (TRACKER_LOCK) {
            SdpSearchInstance inst = mSdpSearchTracker.getSearchInstance(address, uuid);
            SdpDipRecord sdpRecord = null;
            if (inst == null) {
                Log.e(TAG, "sdpDipRecordFoundCallback: Search instance is NULL");
                return;
            }
            inst.setStatus(status);
            Log.d(TAG, "sdpDipRecordFoundCallback: status " + status);
            if (status == AbstractionLayer.BT_STATUS_SUCCESS) {
                sdpRecord =
                        new SdpDipRecord(
                                specificationId,
                                vendorId,
                                vendorIdSource,
                                productId,
                                version,
                                primaryRecord);
            }
            Log.d(TAG, "UUID: " + Arrays.toString(uuid));
            Log.d(TAG, "UUID in parcel: " + ((Utils.byteArrayToUuid(uuid))[0]).toString());
            sendSdpIntent(inst, sdpRecord, moreResults);
        }
    }

    /* TODO: Test or remove! */
    void sdpRecordFoundCallback(
            int status, byte[] address, byte[] uuid, int sizeRecord, byte[] record) {
        synchronized (TRACKER_LOCK) {
            SdpSearchInstance inst = mSdpSearchTracker.getSearchInstance(address, uuid);
            SdpRecord sdpRecord = null;
            if (inst == null) {
                Log.e(TAG, "sdpRecordFoundCallback: Search instance is NULL");
                return;
            }
            inst.setStatus(status);
            if (status == AbstractionLayer.BT_STATUS_SUCCESS) {
                Log.d(TAG, "sdpRecordFoundCallback: found a sdp record of size " + sizeRecord);
                Log.d(TAG, "Record:" + Arrays.toString(record));
                sdpRecord = new SdpRecord(sizeRecord, record);
            }
            Log.d(TAG, "UUID: " + Arrays.toString(uuid));
            Log.d(TAG, "UUID in parcel: " + ((Utils.byteArrayToUuid(uuid))[0]).toString());
            sendSdpIntent(inst, sdpRecord, false);
        }
    }

    public void sdpSearch(BluetoothDevice device, ParcelUuid uuid) {
        if (!mNativeAvailable) {
            Log.e(TAG, "Native not initialized!");
            return;
        }
        synchronized (TRACKER_LOCK) {
            if (mSdpSearchTracker.isSearching(device, uuid)) {
                /* Search already in progress */
                return;
            }

            SdpSearchInstance inst = new SdpSearchInstance(0, device, uuid);
            mSdpSearchTracker.add(inst); // Queue the request

            startSearch(); // Start search if not busy
        }
    }

    /* Caller must hold the mTrackerLock */
    @GuardedBy("TRACKER_LOCK")
    private void startSearch() {

        SdpSearchInstance inst = mSdpSearchTracker.getNext();

        if ((inst != null) && (!mSearchInProgress)) {
            Log.d(TAG, "Starting search for UUID: " + inst.getUuid());
            mSearchInProgress = true;

            inst.startSearch(); // Trigger timeout message

            mNativeInterface.sdpSearch(
                    Flags.identityAddressNullIfUnknown()
                            ? Utils.getByteBrEdrAddress(inst.getDevice())
                            : mAdapterService.getByteIdentityAddress(inst.getDevice()),
                    Utils.uuidToByteArray(inst.getUuid()));
        } else { // Else queue is empty.
            Log.d(
                    TAG,
                    "startSearch():"
                            + (" nextInst = " + inst)
                            + (" mSearchInProgress = " + mSearchInProgress)
                            + " - search busy or queue empty.");
        }
    }

    /* Caller must hold the mTrackerLock */
    @GuardedBy("TRACKER_LOCK")
    private void sendSdpIntent(SdpSearchInstance inst, Parcelable record, boolean moreResults) {

        inst.stopSearch();

        mAdapterService.sendSdpSearchRecord(
                inst.getDevice(), inst.getStatus(), record, inst.getUuid());

        Intent intent = new Intent(BluetoothDevice.ACTION_SDP_RECORD);

        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, inst.getDevice());
        intent.putExtra(BluetoothDevice.EXTRA_SDP_SEARCH_STATUS, inst.getStatus());
        if (record != null) {
            intent.putExtra(BluetoothDevice.EXTRA_SDP_RECORD, record);
        }
        intent.putExtra(BluetoothDevice.EXTRA_UUID, inst.getUuid());
        /* TODO:  BLUETOOTH_ADMIN_PERM was private... change to callback interface.
         * Keep in mind that the MAP client needs to use this as well,
         * hence to make it call-backs, the MAP client profile needs to be
         * part of the Bluetooth APK. */
        mAdapterService.sendBroadcast(
                intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());

        if (!moreResults) {
            //Remove the outstanding UUID request
            mSdpSearchTracker.remove(inst);
            mSearchInProgress = false;
            startSearch();
        }
    }
}
