/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothDevice.AddressType;
import android.bluetooth.BluetoothDevice.BluetoothAddress;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test cases for {@link BluetoothDevice}. */
@RunWith(JUnit4.class)
public class BluetoothDeviceTest {

    @Test
    public void testBluetoothAddress_empty() {
        BluetoothAddress bluetoothAddress =
                new BluetoothAddress(null, BluetoothDevice.ADDRESS_TYPE_UNKNOWN);

        assertThat(bluetoothAddress.getAddress()).isNull();
        assertThat(bluetoothAddress.getAddressType())
                .isEqualTo(BluetoothDevice.ADDRESS_TYPE_UNKNOWN);
    }

    @Test
    public void testBluetoothAddress_addressWithTypePublic() {
        doTestAddressWithType("00:11:22:AA:BB:CC", BluetoothDevice.ADDRESS_TYPE_PUBLIC);
    }

    @Test
    public void testBluetoothAddress_addressWithTypeRandom() {
        doTestAddressWithType("51:F7:A8:75:AC:5E", BluetoothDevice.ADDRESS_TYPE_RANDOM);
    }

    @Test
    public void testBluetoothAddress_Parcelable() {
        String address = "00:11:22:AA:BB:CC";
        int addressType = BluetoothDevice.ADDRESS_TYPE_PUBLIC;

        BluetoothAddress bluetoothAddress = new BluetoothAddress(address, addressType);

        doAssertBluetoothAddress(bluetoothAddress, address, addressType);

        Parcel parcel = Parcel.obtain();
        bluetoothAddress.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);
        BluetoothAddress bluetoothAddressOut = BluetoothAddress.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        doAssertBluetoothAddress(bluetoothAddressOut, address, addressType);
    }

    private void doTestAddressWithType(String address, @AddressType int addressType) {
        BluetoothAddress bluetoothAddress = new BluetoothAddress(address, addressType);

        doAssertBluetoothAddress(bluetoothAddress, address, addressType);
    }

    private void doAssertBluetoothAddress(
            BluetoothAddress bluetoothAddress, String address, @AddressType int addressType) {
        assertThat(bluetoothAddress.getAddress()).isEqualTo(address);
        assertThat(bluetoothAddress.getAddressType())
                .isEqualTo(
                        addressType == BluetoothDevice.ADDRESS_TYPE_RANDOM
                                ? BluetoothDevice.ADDRESS_TYPE_RANDOM
                                : BluetoothDevice.ADDRESS_TYPE_PUBLIC);
    }
}
