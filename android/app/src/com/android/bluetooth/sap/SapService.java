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

package com.android.bluetooth.sap;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSap;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothSap;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.sysprop.BluetoothProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.sdp.SdpManagerNativeInterface;
import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SapService extends ProfileService implements AdapterService.BluetoothStateCallback {
    private static final String TAG = SapService.class.getSimpleName();

    private static final String SDP_SAP_SERVICE_NAME = "SIM Access";
    private static final int SDP_SAP_VERSION = 0x0102;

    /* Message ID's */
    private static final int START_LISTENER = 1;

    private static final int USER_TIMEOUT = 2;
    private static final int SHUTDOWN = 3;

    public static final int MSG_SERVERSESSION_CLOSE = 5000;
    public static final int MSG_SESSION_ESTABLISHED = 5001;
    public static final int MSG_SESSION_DISCONNECTED = 5002;

    public static final int MSG_ACQUIRE_WAKE_LOCK = 5005;
    public static final int MSG_RELEASE_WAKE_LOCK = 5006;

    public static final int MSG_CHANGE_STATE = 5007;

    /* Each time a transaction between the SIM and the BT Client is detected a wakelock is taken.
     * After an idle period of RELEASE_WAKE_LOCK_DELAY ms the wakelock is released.
     *
     * NOTE: While connected the the Nokia 616 car-kit it was noticed that the carkit do
     *       TRANSFER_APDU_REQ with 20-30 seconds interval, and it sends no requests less than 1 sec
     *       apart. Additionally the responses from the RIL seems to come within 100 ms, hence a
     *       one second timeout should be enough.
     */
    private static final int RELEASE_WAKE_LOCK_DELAY = 1000;

    /* Intent indicating timeout for user confirmation. */
    public static final String USER_CONFIRM_TIMEOUT_ACTION =
            "com.android.bluetooth.sap.USER_CONFIRM_TIMEOUT";
    private static final int USER_CONFIRM_TIMEOUT_VALUE = 25000;

    private final AdapterService mAdapterService;

    private PowerManager.WakeLock mWakeLock = null;
    private SocketAcceptThread mAcceptThread = null;
    private BluetoothServerSocket mServerSocket = null;
    private int mSdpHandle = -1;
    private BluetoothSocket mConnSocket = null;
    private BluetoothDevice mRemoteDevice = null;
    private static String sRemoteDeviceName = null;
    private volatile boolean mInterrupted;
    private int mState = BluetoothSap.STATE_DISCONNECTED;
    private SapServer mSapServer = null;
    private AlarmManager mAlarmManager = null;
    private boolean mRemoveTimeoutMsg = false;
    private boolean mIsWaitingAuthorization = false;

    private static SapService sSapService;

    private static final ParcelUuid[] SAP_UUIDS = {
        BluetoothUuid.SAP,
    };

    public SapService(AdapterService adapterService) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        BluetoothSap.invalidateBluetoothGetConnectionStateCache();

        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        filter.addAction(USER_CONFIRM_TIMEOUT_ACTION);

        registerReceiver(mSapReceiver, filter);

        mAdapterService.registerBluetoothStateCallback(getMainExecutor(), this);
        // start RFCOMM listener
        mSessionStatusHandler.sendMessage(mSessionStatusHandler.obtainMessage(START_LISTENER));
        setSapService(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileSapServerEnabled().orElse(false);
    }

    /***
     * Call this when ever an activity is detected to renew the wakelock
     *
     * @param messageHandler reference to the handler to notify
     *  - typically mSessionStatusHandler, but it cannot be accessed in a static manner.
     */
    public static void notifyUpdateWakeLock(Handler messageHandler) {
        if (messageHandler != null) {
            Message msg = Message.obtain(messageHandler);
            msg.what = MSG_ACQUIRE_WAKE_LOCK;
            msg.sendToTarget();
        }
    }

    private void removeSdpRecord() {
        SdpManagerNativeInterface nativeInterface = SdpManagerNativeInterface.getInstance();
        if (mSdpHandle >= 0 && nativeInterface.isAvailable()) {
            Log.v(TAG, "Removing SDP record handle: " + mSdpHandle);
            nativeInterface.removeSdpRecord(mSdpHandle);
            mSdpHandle = -1;
        }
    }

    private void startRfcommSocketListener() {
        Log.v(TAG, "Sap Service startRfcommSocketListener");

        if (mAcceptThread == null) {
            mAcceptThread = new SocketAcceptThread();
            mAcceptThread.setName("SapAcceptThread");
            mAcceptThread.start();
        }
    }

    private static final int CREATE_RETRY_TIME = 10;

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private boolean initSocket() {
        Log.v(TAG, "Sap Service initSocket");

        boolean initSocketOK = false;

        // It's possible that create will fail in some cases. retry for 10 times
        for (int i = 0; i < CREATE_RETRY_TIME && !mInterrupted; i++) {
            initSocketOK = true;
            try {
                // It is mandatory for MSE to support initiation of bonding and encryption.
                // TODO: Consider reusing the mServerSocket - it is indented to be reused
                //       for multiple connections.
                mServerSocket =
                        BluetoothAdapter.getDefaultAdapter()
                                .listenUsingRfcommOn(
                                        BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP,
                                        true,
                                        true);
                removeSdpRecord();
                mSdpHandle =
                        SdpManagerNativeInterface.getInstance()
                                .createSapsRecord(
                                        SDP_SAP_SERVICE_NAME,
                                        mServerSocket.getChannel(),
                                        SDP_SAP_VERSION);
            } catch (IOException e) {
                Log.e(TAG, "Error create RfcommServerSocket ", e);
                initSocketOK = false;
            } catch (SecurityException e) {
                Log.e(TAG, "Error creating RfcommServerSocket ", e);
                initSocketOK = false;
            }

            if (!initSocketOK) {
                // Need to break out of this loop if BT is being turned off.
                int state = mAdapterService.getState();
                if ((state != BluetoothAdapter.STATE_TURNING_ON)
                        && (state != BluetoothAdapter.STATE_ON)) {
                    Log.w(TAG, "initServerSocket failed as BT is (being) turned off");
                    break;
                }
                try {
                    Log.v(TAG, "wait 300 ms");
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Log.e(TAG, "socketAcceptThread thread was interrupted (3)", e);
                }
            } else {
                break;
            }
        }

        if (initSocketOK) {
            Log.v(TAG, "Succeed to create listening socket ");

        } else {
            Log.e(TAG, "Error to create listening socket after " + CREATE_RETRY_TIME + " try");
        }
        return initSocketOK;
    }

    private synchronized void closeServerSocket() {
        // exit SocketAcceptThread early
        if (mServerSocket != null) {
            try {
                // this will cause mServerSocket.accept() return early with IOException
                mServerSocket.close();
                mServerSocket = null;
            } catch (IOException ex) {
                Log.e(TAG, "Close Server Socket error: ", ex);
            }
        }
    }

    private synchronized void closeConnectionSocket() {
        if (mConnSocket != null) {
            try {
                mConnSocket.close();
                mConnSocket = null;
            } catch (IOException e) {
                Log.e(TAG, "Close Connection Socket error: ", e);
            }
        }
    }

    private void closeService() {
        Log.v(TAG, "SAP Service closeService in");

        // exit initSocket early
        mInterrupted = true;
        closeServerSocket();

        if (mAcceptThread != null) {
            try {
                mAcceptThread.shutdown();
                mAcceptThread.join();
                mAcceptThread = null;
            } catch (InterruptedException ex) {
                Log.w(TAG, "mAcceptThread close error", ex);
            }
        }

        if (mWakeLock != null) {
            mSessionStatusHandler.removeMessages(MSG_ACQUIRE_WAKE_LOCK);
            mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
            mWakeLock.release();
            mWakeLock = null;
        }

        closeConnectionSocket();

        Log.v(TAG, "SAP Service closeService out");
    }

    private void startSapServerSession() throws IOException {
        Log.v(TAG, "Sap Service startSapServerSession");

        // acquire the wakeLock before start SAP transaction thread
        if (mWakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StartingSapTransaction");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
        }

        /* Start the SAP I/O thread and associate with message handler */
        mSapServer =
                new SapServer(
                        mSessionStatusHandler,
                        this,
                        mConnSocket.getInputStream(),
                        mConnSocket.getOutputStream());
        mSapServer.start();
        /* Warning: at this point we most likely have already handled the initial connect
         *          request from the SAP client, hence we need to be prepared to handle the
         *          response. (the SapHandler should have been started before this point)*/

        mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
        mSessionStatusHandler.sendMessageDelayed(
                mSessionStatusHandler.obtainMessage(MSG_RELEASE_WAKE_LOCK),
                RELEASE_WAKE_LOCK_DELAY);

        Log.v(TAG, "startSapServerSession() success!");
    }

    private void stopSapServerSession() {

        /* When we reach this point, the SapServer is closed down, and the client is
         * supposed to close the RFCOMM connection. */
        Log.v(TAG, "SAP Service stopSapServerSession");

        mAcceptThread = null;
        closeConnectionSocket();
        closeServerSocket();

        setState(BluetoothSap.STATE_DISCONNECTED);

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        // Last SAP transaction is finished, we start to listen for incoming
        // rfcomm connection again
        if (mAdapterService.isEnabled()) {
            startRfcommSocketListener();
        }
    }

    /**
     * A thread that runs in the background waiting for remote rfcomm connect.Once a remote socket
     * connected, this thread shall be shutdown.When the remote disconnect,this thread shall run
     * again waiting for next request.
     */
    private class SocketAcceptThread extends Thread {

        private boolean mStopped = false;

        @Override
        public void run() {
            BluetoothServerSocket serverSocket;
            if (mServerSocket == null) {
                if (!initSocket()) {
                    return;
                }
            }

            while (!mStopped) {
                try {
                    Log.v(TAG, "Accepting socket connection...");
                    serverSocket = mServerSocket;
                    if (serverSocket == null) {
                        Log.w(TAG, "mServerSocket is null");
                        break;
                    }
                    mConnSocket = mServerSocket.accept();
                    Log.v(TAG, "Accepted socket connection...");
                    synchronized (SapService.this) {
                        if (mConnSocket == null) {
                            Log.w(TAG, "mConnSocket is null");
                            break;
                        }
                        mRemoteDevice = mConnSocket.getRemoteDevice();
                        BluetoothSap.invalidateBluetoothGetConnectionStateCache();
                    }
                    if (mRemoteDevice == null) {
                        Log.i(TAG, "getRemoteDevice() = null");
                        break;
                    }

                    sRemoteDeviceName = Utils.getName(mRemoteDevice);
                    // In case getRemoteName failed and return null
                    if (TextUtils.isEmpty(sRemoteDeviceName)) {
                        sRemoteDeviceName = getString(R.string.defaultname);
                    }
                    int permission = mAdapterService.getSimAccessPermission(mRemoteDevice);

                    Log.v(TAG, "getSimAccessPermission() = " + permission);

                    if (permission == BluetoothDevice.ACCESS_ALLOWED) {
                        try {
                            Log.v(
                                    TAG,
                                    "incoming connection accepted from: "
                                            + sRemoteDeviceName
                                            + " automatically as trusted device");
                            startSapServerSession();
                        } catch (IOException ex) {
                            Log.e(TAG, "catch exception starting obex server session", ex);
                        }
                    } else if (permission != BluetoothDevice.ACCESS_REJECTED) {
                        Intent intent =
                                new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_REQUEST);
                        intent.setPackage(
                                SystemProperties.get(
                                        Utils.PAIRING_UI_PROPERTY,
                                        getString(R.string.pairing_ui_package)));
                        intent.putExtra(
                                BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                BluetoothDevice.REQUEST_TYPE_SIM_ACCESS);
                        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
                        intent.putExtra(BluetoothDevice.EXTRA_PACKAGE_NAME, getPackageName());

                        mIsWaitingAuthorization = true;
                        setUserTimeoutAlarm();
                        SapService.this.sendBroadcast(
                                intent,
                                BLUETOOTH_CONNECT,
                                Utils.getTempBroadcastOptions().toBundle());

                        Log.v(
                                TAG,
                                "waiting for authorization for connection from: "
                                        + sRemoteDeviceName);

                    } else {
                        // Close RFCOMM socket for current connection and start listening
                        // again for new connections.
                        Log.w(
                                TAG,
                                "Can't connect with "
                                        + sRemoteDeviceName
                                        + " as access is rejected");
                        if (mSessionStatusHandler != null) {
                            mSessionStatusHandler.sendEmptyMessage(MSG_SERVERSESSION_CLOSE);
                        }
                    }
                    mStopped = true; // job done ,close this thread;
                } catch (IOException ex) {
                    mStopped = true;
                    Log.v(TAG, "Accept exception: ", ex);
                }
            }
        }

        void shutdown() {
            mStopped = true;
            interrupt();
        }
    }

    private final Handler mSessionStatusHandler =
            new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    Log.v(TAG, "Handler(): got msg=" + msg.what);

                    switch (msg.what) {
                        case START_LISTENER:
                            if (mAdapterService.isEnabled()) {
                                startRfcommSocketListener();
                            }
                            break;
                        case USER_TIMEOUT:
                            if (mIsWaitingAuthorization) {
                                sendCancelUserConfirmationIntent(mRemoteDevice);
                                cancelUserTimeoutAlarm();
                                mIsWaitingAuthorization = false;
                                stopSapServerSession(); // And restart RfcommListener if needed
                            }
                            break;
                        case MSG_SERVERSESSION_CLOSE:
                            stopSapServerSession();
                            break;
                        case MSG_SESSION_ESTABLISHED:
                            break;
                        case MSG_SESSION_DISCONNECTED:
                            // handled elsewhere
                            break;
                        case MSG_ACQUIRE_WAKE_LOCK:
                            Log.v(TAG, "Acquire Wake Lock request message");
                            if (mWakeLock == null) {
                                PowerManager pm = getSystemService(PowerManager.class);
                                mWakeLock =
                                        pm.newWakeLock(
                                                PowerManager.PARTIAL_WAKE_LOCK,
                                                "StartingObexMapTransaction");
                                mWakeLock.setReferenceCounted(false);
                            }
                            if (!mWakeLock.isHeld()) {
                                mWakeLock.acquire();
                                Log.d(TAG, "  Acquired Wake Lock by message");
                            }
                            mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
                            mSessionStatusHandler.sendMessageDelayed(
                                    mSessionStatusHandler.obtainMessage(MSG_RELEASE_WAKE_LOCK),
                                    RELEASE_WAKE_LOCK_DELAY);
                            break;
                        case MSG_RELEASE_WAKE_LOCK:
                            Log.v(TAG, "Release Wake Lock request message");
                            if (mWakeLock != null) {
                                mWakeLock.release();
                                Log.d(TAG, "  Released Wake Lock by message");
                            }
                            break;
                        case MSG_CHANGE_STATE:
                            Log.d(TAG, "change state message: newState = " + msg.arg1);
                            setState(msg.arg1);
                            break;
                        case SHUTDOWN:
                            /* Ensure to call close from this handler to avoid starting new stuff
                            because of pending messages */
                            closeService();
                            break;
                        default:
                            break;
                    }
                }
            };

    private void setState(int state) {
        setState(state, BluetoothSap.RESULT_SUCCESS);
    }

    private synchronized void setState(int state, int result) {
        if (state != mState) {
            Log.d(TAG, "Sap state " + mState + " -> " + state + ", result = " + result);
            if (state == STATE_CONNECTED) {
                MetricsLogger.logProfileConnectionEvent(BluetoothMetricsProto.ProfileId.SAP);
            }
            int prevState = mState;
            mState = state;
            mAdapterService.updateProfileConnectionAdapterProperties(
                    mRemoteDevice, BluetoothProfile.SAP, mState, prevState);

            BluetoothSap.invalidateBluetoothGetConnectionStateCache();
            Intent intent = new Intent(BluetoothSap.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothProfile.EXTRA_STATE, mState);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
            sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
        }
    }

    public int getState() {
        return mState;
    }

    public BluetoothDevice getRemoteDevice() {
        return mRemoteDevice;
    }

    public static String getRemoteDeviceName() {
        return sRemoteDeviceName;
    }

    public boolean disconnect(BluetoothDevice device) {
        boolean result = false;
        synchronized (SapService.this) {
            if (mRemoteDevice != null && mRemoteDevice.equals(device)) {
                switch (mState) {
                    case BluetoothSap.STATE_CONNECTED:
                        closeConnectionSocket();
                        setState(BluetoothSap.STATE_DISCONNECTED, BluetoothSap.RESULT_CANCELED);
                        result = true;
                        break;
                    default:
                        break;
                }
            }
        }
        return result;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        synchronized (this) {
            if (mState == BluetoothSap.STATE_CONNECTED && mRemoteDevice != null) {
                devices.add(mRemoteDevice);
            }
        }
        return devices;
    }

    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        int connectionState;
        synchronized (this) {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
                if (!BluetoothUuid.containsAnyUuid(featureUuids, SAP_UUIDS)) {
                    continue;
                }
                connectionState = getConnectionState(device);
                for (int i = 0; i < states.length; i++) {
                    if (connectionState == states[i]) {
                        deviceList.add(device);
                    }
                }
            }
        }
        return deviceList;
    }

    public int getConnectionState(BluetoothDevice device) {
        synchronized (this) {
            if (getState() == BluetoothSap.STATE_CONNECTED
                    && getRemoteDevice() != null
                    && getRemoteDevice().equals(device)) {
                return STATE_CONNECTED;
            } else {
                return STATE_DISCONNECTED;
            }
        }
    }

    /**
     * Set connection policy of the profile and disconnects it if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p>The device should already be paired. Connection policy can be one of: {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED}, {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        enforceCallingOrSelfPermission(
                BLUETOOTH_PRIVILEGED, "Need BLUETOOTH_PRIVILEGED permission");
        mAdapterService
                .getDatabase()
                .setProfileConnectionPolicy(device, BluetoothProfile.SAP, connectionPolicy);
        if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p>The connection policy can be any of: {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     */
    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    public int getConnectionPolicy(BluetoothDevice device) {
        enforceCallingOrSelfPermission(
                BLUETOOTH_PRIVILEGED, "Need BLUETOOTH_PRIVILEGED permission");
        return mAdapterService
                .getDatabase()
                .getProfileConnectionPolicy(device, BluetoothProfile.SAP);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new SapBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup Sap Service");

        setSapService(null);
        unregisterReceiver(mSapReceiver);
        mAdapterService.unregisterBluetoothStateCallback(this);
        setState(BluetoothSap.STATE_DISCONNECTED, BluetoothSap.RESULT_CANCELED);
        sendShutdownMessage();

        setState(BluetoothSap.STATE_DISCONNECTED, BluetoothSap.RESULT_CANCELED);
        closeService();
        if (mSessionStatusHandler != null) {
            mSessionStatusHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onBluetoothStateChange(int prevState, int newState) {
        if (newState == BluetoothAdapter.STATE_TURNING_OFF) {
            Log.d(TAG, "STATE_TURNING_OFF");
            sendShutdownMessage();
        } else if (newState == BluetoothAdapter.STATE_ON) {
            Log.d(TAG, "STATE_ON");
            // start RFCOMM listener
            mSessionStatusHandler.sendMessage(mSessionStatusHandler.obtainMessage(START_LISTENER));
        }
    }

    /**
     * Get the current instance of {@link SapService}
     *
     * @return current instance of {@link SapService}
     */
    @VisibleForTesting
    public static synchronized SapService getSapService() {
        if (sSapService == null) {
            Log.w(TAG, "getSapService(): service is null");
            return null;
        }
        if (!sSapService.isAvailable()) {
            Log.w(TAG, "getSapService(): service is not available");
            return null;
        }
        return sSapService;
    }

    private static synchronized void setSapService(SapService instance) {
        Log.d(TAG, "setSapService(): set to: " + instance);
        sSapService = instance;
    }

    private void setUserTimeoutAlarm() {
        Log.d(TAG, "setUserTimeOutAlarm()");
        cancelUserTimeoutAlarm();
        mRemoveTimeoutMsg = true;
        Intent timeoutIntent = new Intent(USER_CONFIRM_TIMEOUT_ACTION);
        PendingIntent pIntent =
                PendingIntent.getBroadcast(this, 0, timeoutIntent, PendingIntent.FLAG_IMMUTABLE);
        mAlarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + USER_CONFIRM_TIMEOUT_VALUE,
                pIntent);
    }

    private void cancelUserTimeoutAlarm() {
        Log.d(TAG, "cancelUserTimeOutAlarm()");
        if (mAlarmManager == null) {
            mAlarmManager = this.getSystemService(AlarmManager.class);
        }
        if (mRemoveTimeoutMsg) {
            Intent timeoutIntent = new Intent(USER_CONFIRM_TIMEOUT_ACTION);
            PendingIntent sender =
                    PendingIntent.getBroadcast(
                            this, 0, timeoutIntent, PendingIntent.FLAG_IMMUTABLE);
            mAlarmManager.cancel(sender);
            mRemoveTimeoutMsg = false;
        }
    }

    private void sendCancelUserConfirmationIntent(BluetoothDevice device) {
        Intent intent = new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
        intent.setPackage(
                SystemProperties.get(
                        Utils.PAIRING_UI_PROPERTY, getString(R.string.pairing_ui_package)));
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(
                BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE, BluetoothDevice.REQUEST_TYPE_SIM_ACCESS);
        sendBroadcast(intent, BLUETOOTH_CONNECT);
    }

    private void sendShutdownMessage() {
        /* Any pending messages are no longer valid.
        To speed up things, simply delete them. */
        if (mRemoveTimeoutMsg) {
            Intent timeoutIntent = new Intent(USER_CONFIRM_TIMEOUT_ACTION);
            sendBroadcast(timeoutIntent);
            mIsWaitingAuthorization = false;
            cancelUserTimeoutAlarm();
        }
        removeSdpRecord();
        mSessionStatusHandler.removeCallbacksAndMessages(null);
        // Request release of all resources
        mSessionStatusHandler.obtainMessage(SHUTDOWN).sendToTarget();
    }

    private void sendConnectTimeoutMessage() {
        Log.d(TAG, "sendConnectTimeoutMessage()");
        if (mSessionStatusHandler != null) {
            Message msg = mSessionStatusHandler.obtainMessage(USER_TIMEOUT);
            msg.sendToTarget();
        } // Can only be null during shutdown
    }

    @VisibleForTesting SapBroadcastReceiver mSapReceiver = new SapBroadcastReceiver();

    @VisibleForTesting
    class SapBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {

            Log.v(TAG, "onReceive");
            String action = intent.getAction();
            if (action.equals(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY)) {
                Log.v(TAG, " - Received BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY");

                int requestType = intent.getIntExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE, -1);
                if (requestType != BluetoothDevice.REQUEST_TYPE_SIM_ACCESS
                        || !mIsWaitingAuthorization) {
                    return;
                }

                mIsWaitingAuthorization = false;

                if (intent.getIntExtra(
                                BluetoothDevice.EXTRA_CONNECTION_ACCESS_RESULT,
                                BluetoothDevice.CONNECTION_ACCESS_NO)
                        == BluetoothDevice.CONNECTION_ACCESS_YES) {
                    // bluetooth connection accepted by user
                    if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                        boolean result =
                                mRemoteDevice.setSimAccessPermission(
                                        BluetoothDevice.ACCESS_ALLOWED);
                        Log.v(TAG, "setSimAccessPermission(ACCESS_ALLOWED) result=" + result);
                    }
                    boolean result = setConnectionPolicy(mRemoteDevice, CONNECTION_POLICY_ALLOWED);
                    Log.d(TAG, "setConnectionPolicy ALLOWED, result = " + result);

                    try {
                        if (mConnSocket != null) {
                            // start obex server and rfcomm connection
                            startSapServerSession();
                        } else {
                            stopSapServerSession();
                        }
                    } catch (IOException ex) {
                        Log.e(TAG, "Caught the error: ", ex);
                    }
                } else {
                    if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                        boolean result =
                                mRemoteDevice.setSimAccessPermission(
                                        BluetoothDevice.ACCESS_REJECTED);
                        Log.v(TAG, "setSimAccessPermission(ACCESS_REJECTED) result=" + result);
                    }
                    boolean result =
                            setConnectionPolicy(mRemoteDevice, CONNECTION_POLICY_FORBIDDEN);
                    Log.d(TAG, "setConnectionPolicy FORBIDDEN, result = " + result);
                    // Ensure proper cleanup, and prepare for new connect.
                    mSessionStatusHandler.sendEmptyMessage(MSG_SERVERSESSION_CLOSE);
                }
                return;
            }

            if (action.equals(USER_CONFIRM_TIMEOUT_ACTION)) {
                Log.d(TAG, "USER_CONFIRM_TIMEOUT_ACTION Received.");
                // send us self a message about the timeout.
                sendConnectTimeoutMessage();
                return;
            }
        }
    }

    public void aclDisconnected(BluetoothDevice device) {
        mSessionStatusHandler.post(() -> handleAclDisconnected(device));
    }

    private void handleAclDisconnected(BluetoothDevice device) {
        if (!mIsWaitingAuthorization) {
            return;
        }
        if (mRemoteDevice == null || device == null) {
            Log.i(TAG, "Unexpected error!");
            return;
        }

        Log.d(TAG, "ACL disconnected for " + device);

        if (mRemoteDevice.equals(device)) {
            if (mRemoveTimeoutMsg) {
                // Send any pending timeout now, as ACL got disconnected.
                mSessionStatusHandler.removeMessages(USER_TIMEOUT);
                mSessionStatusHandler.obtainMessage(USER_TIMEOUT).sendToTarget();
            }
            setState(BluetoothSap.STATE_DISCONNECTED);
            // Ensure proper cleanup, and prepare for new connect.
            mSessionStatusHandler.sendEmptyMessage(MSG_SERVERSESSION_CLOSE);
        }
    }

    // Binder object: Must be static class or memory leak may occur

    /**
     * This class implements the IBluetoothSap interface - or actually it validates the
     * preconditions for calling the actual functionality in the SapService, and calls it.
     */
    private static class SapBinder extends IBluetoothSap.Stub implements IProfileServiceBinder {
        private SapService mService;

        SapBinder(SapService service) {
            Log.v(TAG, "SapBinder()");
            mService = service;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        private SapService getService(AttributionSource source) {
            // Cache mService because it can change while getService is called
            SapService service = mService;

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            return service;
        }

        @Override
        public int getState(AttributionSource source) {
            Log.v(TAG, "getState()");

            SapService service = getService(source);
            if (service == null) {
                return BluetoothSap.STATE_DISCONNECTED;
            }

            return service.getState();
        }

        @Override
        public BluetoothDevice getClient(AttributionSource source) {
            Log.v(TAG, "getClient()");

            SapService service = getService(source);
            if (service == null) {
                return null;
            }

            Log.v(TAG, "getClient() - returning " + service.getRemoteDevice());
            return service.getRemoteDevice();
        }

        @Override
        public boolean isConnected(BluetoothDevice device, AttributionSource source) {
            Log.v(TAG, "isConnected()");

            SapService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.getConnectionState(device) == STATE_CONNECTED;
        }

        @Override
        public boolean disconnect(BluetoothDevice device, AttributionSource source) {
            Log.v(TAG, "disconnect()");

            SapService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            Log.v(TAG, "getConnectedDevices()");

            SapService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            Log.v(TAG, "getDevicesMatchingConnectionStates()");

            SapService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            Log.v(TAG, "getConnectionState()");

            SapService service = getService(source);
            if (service == null) {
                return STATE_DISCONNECTED;
            }

            return service.getConnectionState(device);
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            SapService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            SapService service = getService(source);
            if (service == null) {
                return CONNECTION_POLICY_UNKNOWN;
            }

            return service.getConnectionPolicy(device);
        }
    }
}
