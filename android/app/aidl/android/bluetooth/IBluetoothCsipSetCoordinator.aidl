/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;
import android.os.ParcelUuid;
import android.bluetooth.IBluetoothCsipSetCoordinatorLockCallback;
import java.util.List;
import java.util.Map;

/** Binder method for CSIP interaction */
@JavaPassthrough(annotation="@android.annotation.Hide")
interface IBluetoothCsipSetCoordinator {
  @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
  List<BluetoothDevice> getConnectedDevices(in AttributionSource attributionSource);
  @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
  List<BluetoothDevice> getDevicesMatchingConnectionStates(in int[] states, in AttributionSource attributionSource);
  @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
  int getConnectionState(in BluetoothDevice device, in AttributionSource attributionSource);
  @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
  boolean setConnectionPolicy(in BluetoothDevice device, int connectionPolicy, in AttributionSource attributionSource);
  @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
  int getConnectionPolicy(in BluetoothDevice device, in AttributionSource attributionSource);
  @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
  List getAllGroupIds(in ParcelUuid uuid, in AttributionSource attributionSource);
  @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
  Map getGroupUuidMapByDevice(in BluetoothDevice device, in AttributionSource attributionSource);
  @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
  int getDesiredGroupSize(in int group_id, in AttributionSource attributionSource);
  @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
  ParcelUuid lockGroup(int groupId, in IBluetoothCsipSetCoordinatorLockCallback callback, in AttributionSource attributionSource);
  @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
  void unlockGroup(in ParcelUuid lockUuid, in AttributionSource attributionSource);

  const int CSIS_GROUP_ID_INVALID = -1;
  const int CSIS_GROUP_SIZE_UNKNOWN = 1;

  const int CSIS_GROUP_LOCK_SUCCESS = 0;
  const int CSIS_GROUP_LOCK_FAILED_INVALID_GROUP = 1;
  const int CSIS_GROUP_LOCK_FAILED_GROUP_EMPTY = 2;
  const int CSIS_GROUP_LOCK_FAILED_GROUP_NOT_CONNECTED = 3;
  const int CSIS_GROUP_LOCK_FAILED_LOCKED_BY_OTHER = 4;
  const int CSIS_GROUP_LOCK_FAILED_OTHER_REASON = 5;
  const int CSIS_LOCKED_GROUP_MEMBER_LOST = 6;
}
