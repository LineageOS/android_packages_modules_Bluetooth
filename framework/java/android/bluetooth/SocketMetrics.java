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

package android.bluetooth;

import static android.bluetooth.BluetoothUtils.getSyncTimeout;

import android.os.RemoteException;
import android.util.Log;

import com.android.modules.utils.SynchronousResultReceiver;

import java.util.concurrent.TimeoutException;

/** Utility class for socket metrics */
class SocketMetrics {
    private static final String TAG = SocketMetrics.class.getSimpleName();

    /*package*/ static final int SOCKET_NO_ERROR = -1;

    // Defined in BluetoothProtoEnums.L2capCocConnectionResult of proto logging
    private static final int RESULT_L2CAP_CONN_UNKNOWN = 0;
    /*package*/ static final int RESULT_L2CAP_CONN_SUCCESS = 1;
    private static final int RESULT_L2CAP_CONN_BLUETOOTH_SOCKET_CONNECTION_FAILED = 1000;
    private static final int RESULT_L2CAP_CONN_BLUETOOTH_SOCKET_CONNECTION_CLOSED = 1001;
    private static final int RESULT_L2CAP_CONN_BLUETOOTH_UNABLE_TO_SEND_RPC = 1002;
    private static final int RESULT_L2CAP_CONN_BLUETOOTH_NULL_BLUETOOTH_DEVICE = 1003;
    private static final int RESULT_L2CAP_CONN_BLUETOOTH_GET_SOCKET_MANAGER_FAILED = 1004;
    private static final int RESULT_L2CAP_CONN_BLUETOOTH_NULL_FILE_DESCRIPTOR = 1005;
    /*package*/ static final int RESULT_L2CAP_CONN_SERVER_FAILURE = 2000;

    static void logSocketConnect(
            int socketExceptionCode,
            long socketConnectionTimeMillis,
            int connType,
            BluetoothDevice device,
            int port,
            boolean auth,
            long socketCreationTimeMillis,
            long socketCreationLatencyMillis) {
        if (connType != BluetoothSocket.TYPE_L2CAP_LE) {
            return;
        }
        IBluetooth bluetoothProxy = BluetoothAdapter.getDefaultAdapter().getBluetoothService();
        if (bluetoothProxy == null) {
            Log.w(TAG, "logSocketConnect: bluetoothProxy is null");
            return;
        }
        int errCode = getL2capLeConnectStatusCode(socketExceptionCode);
        try {
            final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
            bluetoothProxy.logL2capcocClientConnection(
                    device,
                    port,
                    auth,
                    errCode,
                    socketCreationTimeMillis, // to calculate end to end latency
                    socketCreationLatencyMillis, // latency of the constructor
                    socketConnectionTimeMillis, // to calculate the latency of connect()
                    recv);
            recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
        } catch (RemoteException | TimeoutException e) {
            Log.w(TAG, "logL2capcocClientConnection failed", e);
        }
    }

    static void logSocketAccept(
            BluetoothSocket acceptedSocket,
            BluetoothSocket socket,
            int connType,
            int channel,
            int timeout,
            int result,
            long socketCreationTimeMillis,
            long socketCreationLatencyMillis,
            long socketConnectionTimeMillis) {
        if (connType != BluetoothSocket.TYPE_L2CAP_LE) {
            return;
        }
        IBluetooth bluetoothProxy = BluetoothAdapter.getDefaultAdapter().getBluetoothService();
        if (bluetoothProxy == null) {
            Log.w(TAG, "logSocketConnect: bluetoothProxy is null");
            return;
        }
        try {
            final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
            bluetoothProxy.logL2capcocServerConnection(
                    acceptedSocket == null ? null : acceptedSocket.getRemoteDevice(),
                    channel,
                    socket.isAuth(),
                    result,
                    socketCreationTimeMillis, // pass creation time to calculate end to end latency
                    socketCreationLatencyMillis, // socket creation latency
                    socketConnectionTimeMillis, // send connection start time for connection latency
                    timeout,
                    recv);
            recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);

        } catch (RemoteException | TimeoutException e) {
            Log.w(TAG, "logL2capcocServerConnection failed", e);
        }
    }

    private static int getL2capLeConnectStatusCode(int socketExceptionCode) {
        switch (socketExceptionCode) {
            case (SOCKET_NO_ERROR):
                return RESULT_L2CAP_CONN_SUCCESS;
            case (BluetoothSocketException.NULL_DEVICE):
                return RESULT_L2CAP_CONN_BLUETOOTH_NULL_BLUETOOTH_DEVICE;
            case (BluetoothSocketException.SOCKET_MANAGER_FAILURE):
                return RESULT_L2CAP_CONN_BLUETOOTH_GET_SOCKET_MANAGER_FAILED;
            case (BluetoothSocketException.SOCKET_CLOSED):
                return RESULT_L2CAP_CONN_BLUETOOTH_SOCKET_CONNECTION_CLOSED;
            case (BluetoothSocketException.SOCKET_CONNECTION_FAILURE):
                return RESULT_L2CAP_CONN_BLUETOOTH_SOCKET_CONNECTION_FAILED;
            case (BluetoothSocketException.RPC_FAILURE):
                return RESULT_L2CAP_CONN_BLUETOOTH_UNABLE_TO_SEND_RPC;
            case (BluetoothSocketException.UNIX_FILE_SOCKET_CREATION_FAILURE):
                return RESULT_L2CAP_CONN_BLUETOOTH_NULL_FILE_DESCRIPTOR;
            default:
                return RESULT_L2CAP_CONN_UNKNOWN;
        }
    }
}
