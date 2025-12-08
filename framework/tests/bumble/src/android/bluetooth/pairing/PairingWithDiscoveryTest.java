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

package android.bluetooth.pairing;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.PandoraDevice;
import android.bluetooth.StreamObserverSpliterator;
import android.bluetooth.Utils;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.pairing.utils.IntentReceiver;
import android.bluetooth.test_utils.BlockingBluetoothAdapter;
import android.bluetooth.test_utils.EnableBluetoothRule;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.protobuf.ByteString;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.hamcrest.MockitoHamcrest;
import org.mockito.stubbing.Answer;

import pandora.BumbleConfigProto;
import pandora.HostProto;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.AdvertiseResponse;
import pandora.HostProto.DisconnectRequest;
import pandora.HostProto.DiscoverabilityMode;
import pandora.HostProto.OwnAddressType;
import pandora.HostProto.SetDiscoverabilityModeRequest;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Test cases for {@link PairingWithDiscoveryTest}. */
@RunWith(AndroidJUnit4.class)
public class PairingWithDiscoveryTest {
    private static final String TAG = PairingWithDiscoveryTest.class.getSimpleName();
    private static final String BUMBLE_DEVICE_NAME = "Bumble";
    private static final String BUMBLE_DEVICE_NAME_2 = "Bumble_2";
    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DISCOVERY_TIMEOUT = 2000; // 2 seconds

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mManager = mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mAdapter = mManager.getAdapter();

    private final Map<String, Integer> mActionRegistrationCounts = new HashMap<>();

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    @Rule(order = 3)
    public final PandoraDevice mSecondBumble = PandoraDevice.createSecondPandoraDevice();

    @Rule(order = 4)
    public final EnableBluetoothRule mEnableBluetoothRule =
            new EnableBluetoothRule(false /* enableTestMode */, true /* toggleBluetooth */);

    private final BluetoothLeScanner mLeScanner = mAdapter.getBluetoothLeScanner();

    private BluetoothDevice mBumbleDevice;
    private BluetoothDevice mRemoteLeDevice;
    private BluetoothDevice mSecondBumbleDevice;
    private InOrder mInOrder = null;
    private CompletableFuture<BluetoothDevice> mDeviceFound;
    private CompletableFuture<BluetoothDevice> mSecondDeviceFound;
    private String mCfName;
    @Mock private BroadcastReceiver mReceiver;

    @SuppressLint("MissingPermission")
    private final Answer<Void> mIntentHandler =
            inv -> {
                Log.i(TAG, "onReceive(): intent=" + Arrays.toString(inv.getArguments()));
                Intent intent = inv.getArgument(1);
                String action = intent.getAction();
                switch (action) {
                    case BluetoothDevice.ACTION_FOUND -> {
                        BluetoothDevice device =
                                intent.getParcelableExtra(
                                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                        String deviceName =
                                String.valueOf(intent.getStringExtra(BluetoothDevice.EXTRA_NAME));
                        Log.i(TAG, "Discovered device: " + device + " with name: " + deviceName);
                        if (deviceName != null
                                && BUMBLE_DEVICE_NAME.equals(deviceName)
                                && mDeviceFound != null) {
                            mDeviceFound.complete(device);
                        } else if (deviceName != null
                                && BUMBLE_DEVICE_NAME_2.equals(deviceName)
                                && mSecondDeviceFound != null) {
                            mSecondDeviceFound.complete(device);
                        }
                    }
                    default -> Log.i(TAG, "onReceive(): unknown intent action " + action);
                }
                return null;
            };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doAnswer(mIntentHandler).when(mReceiver).onReceive(any(), any());

        mInOrder = inOrder(mReceiver);
        mCfName = mAdapter.getName();

        mBumbleDevice = mBumble.getRemoteDevice();
        mRemoteLeDevice =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        mSecondBumbleDevice = mSecondBumble.getRemoteDevice();

        for (BluetoothDevice device : mAdapter.getBondedDevices()) {
            removeBond(device);
        }
    }

    @After
    public void tearDown() throws Exception {
        for (BluetoothDevice device : mAdapter.getBondedDevices()) {
            removeBond(device);
        }
        mBumbleDevice = null;
        if (getTotalActionRegistrationCounts() > 0) {
            mContext.unregisterReceiver(mReceiver);
            mActionRegistrationCounts.clear();
        }
    }

    /**
     * Test the address type reported for a discovered remote Bluetooth device.
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bluetooth is enabled on both Android and Bumble.
     *   <li>Bumble is not bonded with Android.
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble starts advertising with its own address type set to {@link
     *       OwnAddressType#PUBLIC}.
     *   <li>Android starts scanning for Bluetooth devices.
     *   <li>Android receives a {@link BluetoothDevice#ACTION_FOUND} intent for Bumble.
     *   <li>The address type of the discovered {@link BluetoothDevice} is verified to be {@link
     *       BluetoothDevice#ADDRESS_TYPE_PUBLIC}.
     *   <li>Android cancels the discovery process.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>The {@link BluetoothDevice} object received in the {@link BluetoothDevice#ACTION_FOUND}
     *       intent reflects the address type with which Bumble was advertising.
     * </ul>
     */
    @Test
    public void testAddressType_AtDeviceDiscovery_typePublic() throws Exception {
        registerIntentActions(BluetoothDevice.ACTION_FOUND);
        mDeviceFound = new CompletableFuture<>();

        // Start advertising from bumble side with address type PUBLIC
        mBumble.hostBlocking()
                .advertise(
                        AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(OwnAddressType.PUBLIC)
                                .build());
        mBumble.hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                                .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                                .build());
        // Start device discovery from android
        assertThat(mAdapter.startDiscovery()).isTrue();
        // Verify device to be discovered
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_FOUND),
                hasExtra(BluetoothDevice.EXTRA_NAME, BUMBLE_DEVICE_NAME));

        BluetoothDevice device =
                mDeviceFound
                        .completeOnTimeout(null, DISCOVERY_TIMEOUT, TimeUnit.MILLISECONDS)
                        .join();
        // Verify address
        assertThat(device.getAddress()).isEqualTo(mBumbleDevice.getAddress());
        // Verify address type
        assertThat(device.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_PUBLIC);
        // Cancel discovery
        assertThat(mAdapter.cancelDiscovery()).isTrue();

        unregisterIntentActions(BluetoothDevice.ACTION_FOUND);
    }

    /**
     * Test the address type reported for a discovered remote Bluetooth device.
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bluetooth is enabled on both Android and Bumble.
     *   <li>Bumble is not bonded with Android.
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble starts advertising again with its own address type set to {@link
     *       OwnAddressType#RANDOM}.
     *   <li>Android starts scanning for Bluetooth devices.
     *   <li>Android receives a {@link BluetoothDevice#ACTION_FOUND} intent for Bumble.
     *   <li>The address type of the newly discovered {@link BluetoothDevice} is verified to be
     *       {@link BluetoothDevice#ADDRESS_TYPE_RANDOM}.
     *   <li>Android cancels the discovery process.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>The {@link BluetoothDevice} object received in the {@link BluetoothDevice#ACTION_FOUND}
     *       intent reflects the address type with which Bumble was advertising.
     * </ul>
     */
    @Test
    public void testAddressType_AtDeviceDiscovery_typeRandom() throws Exception {
        CompletableFuture<ScanResult> future = new CompletableFuture<>();
        // LE Scan setting
        ScanSettings scanSettings =
                new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .build();
        // LE Scan filter
        ScanFilter scanFilter = new ScanFilter.Builder().build();
        // LE Scan result callback
        ScanCallback scanCallback =
                new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        Log.d(TAG, "onScanResult: result=" + result);
                        assertThat(result).isNotNull();
                        assertThat(result.getDevice()).isNotNull();
                        if (Utils.BUMBLE_RANDOM_ADDRESS.equals(result.getDevice().getAddress())) {
                            future.complete(result);
                        }
                    }

                    @Override
                    public void onScanFailed(int errorCode) {
                        Log.d(TAG, "onScanFailed: errorCode=" + errorCode);
                        future.complete(null);
                    }
                };
        // Start advertising from bumble side with address type RANDOM
        mBumble.hostBlocking()
                .advertise(
                        AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(OwnAddressType.RANDOM)
                                .build());
        // Make Bumble discoverable
        mBumble.hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                                .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                                .build());
        // Start LE scanning
        mLeScanner.startScan(List.of(scanFilter), scanSettings, scanCallback);
        // Wait for the LE scan completion
        ScanResult result =
                future.completeOnTimeout(null, DISCOVERY_TIMEOUT, TimeUnit.MILLISECONDS).join();
        // Stop LE Scan
        mLeScanner.stopScan(scanCallback);
        // Verify that the result list is not empty
        assertThat(result).isNotNull();
        // Get the first device from the list
        BluetoothDevice leDevice = result.getDevice();
        // Verify address
        assertThat(leDevice.getAddress()).isEqualTo(Utils.BUMBLE_RANDOM_ADDRESS);
        // Verify address type
        assertThat(leDevice.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM);
    }

    /**
     * Test the address type reported upon receiving a connection from a remote device.
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble is discoverable.
     *   <li>Android Bluetooth is enabled.
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Android registers an intent listener for ACL connection events.
     *   <li>Bumble initiates a connection to the Android device.
     *   <li>Android receives the connection.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>The address type of the connected device is {@link
     *       BluetoothDevice#ADDRESS_TYPE_PUBLIC}.
     * </ul>
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_RETAIN_ADDRESS_TYPE})
    public void testAddressType_AtConnectionFromRemote_typePublic() throws Exception {
        registerIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED);
        // Connect to android from bumble
        HostProto.ConnectResponse conn =
                mBumble.hostBlocking()
                        .connect(
                                HostProto.ConnectRequest.newBuilder()
                                        .setAddress(
                                                ByteString.copyFrom(
                                                        Utils.addressBytesFromString(
                                                                mAdapter.getAddress())))
                                        .build());
        assertThat(conn.hasConnection()).isTrue();
        // Verify ACL connection
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));

        assertThat(mBumbleDevice.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_PUBLIC);

        // Disconnect from bumble
        mBumble.hostBlocking()
                .disconnect(
                        HostProto.DisconnectRequest.newBuilder()
                                .setConnection(conn.getConnection())
                                .build());

        // Verify ACL disconnection
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));

        unregisterIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED);
    }

    /**
     * Test the address type reported upon receiving a connection from a remote device with a random
     * address.
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Android Bluetooth is enabled.
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Android registers an intent listener for ACL connection events.
     *   <li>Bumble's address type is set to random.
     *   <li>Bumble initiates a connection to the Android device.
     *   <li>Android receives the connection.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>The address type of the connected device is {@link
     *       BluetoothDevice#ADDRESS_TYPE_RANDOM}.
     * </ul>
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_RETAIN_ADDRESS_TYPE})
    public void testAddressType_AtConnectionFromRemote_typeRandom() throws Exception {
        registerIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED);
        testStep_Advertise(OwnAddressType.RANDOM);
        // Scan for LE advertisement from DUT
        ByteString deviceAddr = ByteString.EMPTY;
        String deviceName = "";
        Iterator<HostProto.ScanningResponse> scanningResponseIterator =
                mBumble.hostBlocking().scan(HostProto.ScanRequest.newBuilder().build());
        // Wait till the DUT is discovered by Bumble
        while (true) {
            if (scanningResponseIterator.hasNext()) {
                HostProto.ScanningResponse scanningResponse = scanningResponseIterator.next();
                deviceAddr = scanningResponse.getRandom();
                deviceName = scanningResponse.getData().getCompleteLocalName();
                if (deviceName.contains(mCfName)) {
                    break;
                }
            }
        }
        // Check if DUT address is not empty
        assertThat(deviceAddr.isEmpty()).isFalse();
        // Create LE connection from REF side
        HostProto.ConnectLEResponse leConn =
                mBumble.hostBlocking()
                        .connectLE(
                                HostProto.ConnectLERequest.newBuilder()
                                        .setOwnAddressType(HostProto.OwnAddressType.RANDOM)
                                        .setRandom(deviceAddr)
                                        .build());
        // Verify that connection response has connection
        assertThat(leConn.hasConnection()).isTrue();
        // Verify ACL connection
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteLeDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));

        // Verify address type
        assertThat(mRemoteLeDevice.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM);

        // Disconnect from bumble
        mBumble.hostBlocking()
                .disconnect(
                        HostProto.DisconnectRequest.newBuilder()
                                .setConnection(leConn.getConnection())
                                .build());
        // Verify ACL disconnection
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteLeDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));

        unregisterIntentActions(
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED);
    }

    /**
     * Test the address type of a bonded device after a Bluetooth restart.
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Android Bluetooth is enabled.
     *   <li>Bumble and Android are bonded over BR/EDR.
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bond Bumble and Android using the {@link
     *       #testStep_Bond(IntentReceiver,BluetoothDevice,int)} helper method.
     *   <li>Restart the Bluetooth adapter using the {@link #testStep_restartBt()} helper method.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>The set of bonded devices still contains the Bumble device after the restart.
     *   <li>The address type of the bonded Bumble device is {@link
     *       BluetoothDevice#ADDRESS_TYPE_PUBLIC}.
     * </ul>
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_RETAIN_ADDRESS_TYPE})
    public void testAddressType_onBluetoothOnOff_typePublic() throws Exception {
        IntentReceiver intentReceiver =
                new IntentReceiver.Builder(
                                mContext,
                                BluetoothDevice.ACTION_ACL_CONNECTED,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_PAIRING_REQUEST)
                        .build();
        // Create Bond over classic
        testStep_Bond(intentReceiver, mBumbleDevice, BluetoothDevice.TRANSPORT_BREDR);
        // Verify address type
        assertThat(mBumbleDevice.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_PUBLIC);
        // Restart Bluetooth
        testStep_restartBt();
        // Verify address type after restart
        assertThat(mAdapter.getBondedDevices()).contains(mBumbleDevice);
        assertThat(mBumbleDevice.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_PUBLIC);

        intentReceiver.close();
    }

    /**
     * Test the address type of a bonded device with a random address after a Bluetooth restart.
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Android Bluetooth is enabled.
     *   <li>Bumble and Android are bonded over BR/EDR.
     *   <li>Bumble is configured to use a random address.
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Scan for Bumble device with RANDOM address.
     *   <li>Create bond with Bumble
     *   <li>Restart the Bluetooth adapter using the {@link #testStep_restartBt()} helper method.
     *   <li>Get the {@link BluetoothDevice} object for the bonded Bumble device.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>The address type of the bonded Bumble device is {@link
     *       BluetoothDevice#ADDRESS_TYPE_RANDOM}.
     * </ul>
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_RETAIN_ADDRESS_TYPE})
    public void testAddressType_onBluetoothOnOff_typeRandom() throws Exception {
        IntentReceiver intentReceiver =
                new IntentReceiver.Builder(
                                mContext,
                                BluetoothDevice.ACTION_ACL_CONNECTED,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_PAIRING_REQUEST)
                        .build();
        // Start advertising from bumble side with address type RANDOM
        CompletableFuture<ScanResult> future = new CompletableFuture<>();
        // LE Scan setting
        ScanSettings scanSettings =
                new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .build();
        // LE Scan filter
        ScanFilter scanFilter = new ScanFilter.Builder().build();
        // LE Scan result callback
        ScanCallback scanCallback =
                new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        Log.d(TAG, "onScanResult: result=" + result);
                        assertThat(result).isNotNull();
                        assertThat(result.getDevice()).isNotNull();
                        if (Utils.BUMBLE_RANDOM_ADDRESS.equals(result.getDevice().getAddress())) {
                            future.complete(result);
                        }
                    }

                    @Override
                    public void onScanFailed(int errorCode) {
                        Log.d(TAG, "onScanFailed: errorCode=" + errorCode);
                        future.complete(null);
                    }
                };
        // Start advertising from bumble side with address type RANDOM
        mBumble.hostBlocking()
                .advertise(
                        AdvertiseRequest.newBuilder()
                                .setLegacy(true)
                                .setConnectable(true)
                                .setOwnAddressType(OwnAddressType.RANDOM)
                                .build());
        // Make Bumble discoverable
        mBumble.hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                                .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                                .build());
        // Start LE scanning
        mLeScanner.startScan(List.of(scanFilter), scanSettings, scanCallback);
        // Wait for the LE scan completion
        ScanResult result =
                future.completeOnTimeout(null, DISCOVERY_TIMEOUT, TimeUnit.MILLISECONDS).join();
        // Stop LE Scan
        mLeScanner.stopScan(scanCallback);
        // Verify that the result list is not empty
        assertThat(result).isNotNull();
        // Get the first device from the list
        BluetoothDevice leDevice = result.getDevice();
        // Create Bond over classic
        testStep_Bond(intentReceiver, leDevice, BluetoothDevice.TRANSPORT_LE);
        // Verify the address type
        assertThat(leDevice.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM);
        // Restart Bluetooth
        testStep_restartBt();
        // Verify address type after restart
        assertThat(mAdapter.getBondedDevices()).contains(leDevice);
        // Verify the address type
        assertThat(leDevice.getAddressType()).isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM);

        intentReceiver.close();
    }

    /**
     * Test that two separate Bumble devices are discovered by the Android device during a single
     * discovery scan.
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Make the first Bumble device ({@code mBumble}) discoverable in general mode.
     *   <li>Make the second Bumble device ({@code mSecondBumble}) discoverable in general mode.
     *   <li>Start device discovery on the Android adapter.
     *   <li>Wait for both Bumble devices to be discovered within a timeout period.
     *   <li>Cancel the device discovery on the Android adapter.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>Both {@code mBumbleDevice} and {@code mSecondBumbleDevice} are not null, indicating
     *       that both Bumble devices were successfully discovered during the scan.
     * </ul>
     */
    @Test
    public void testSecondBumbleDevice_onScan() throws Exception {
        registerIntentActions(BluetoothDevice.ACTION_FOUND);

        // Make Bumble discoverable
        mBumble.hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                                .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                                .build());
        // Make Second Bumble device discoverable
        mSecondBumble
                .hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                                .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                                .build());

        mBumbleDevice = null;
        mSecondBumbleDevice = null;
        // Start device discovery from Android
        mDeviceFound = new CompletableFuture<>();
        mSecondDeviceFound = new CompletableFuture<>();
        assertThat(mAdapter.startDiscovery()).isTrue();
        mBumbleDevice =
                mDeviceFound
                        .completeOnTimeout(null, DISCOVERY_TIMEOUT, TimeUnit.MILLISECONDS)
                        .join();
        mSecondBumbleDevice =
                mSecondDeviceFound
                        .completeOnTimeout(null, DISCOVERY_TIMEOUT, TimeUnit.MILLISECONDS)
                        .join();

        assertThat(mBumbleDevice).isNotNull();
        assertThat(mSecondBumbleDevice).isNotNull();
        assertThat(mAdapter.cancelDiscovery()).isTrue();

        unregisterIntentActions(BluetoothDevice.ACTION_FOUND);
    }

    /**
     * Test a failure scenario for Cross-Transport Key Derivation (CTKD) where an initial LE pairing
     * attempt from the DUT is interrupted, followed by a successful BR/EDR pairing.
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble is configured for MITM protection, Secure Connections (SC), and bonding.
     *   <li>Bumble is set to use a public identity address.
     *   <li>Bumble is configured to distribute all key types (Encryption Key, Identity Key, Signing
     *       Key, Link Key) for both initiator and responder roles.
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Start LE advertising on Bumble with a public address type.
     *   <li>Initiate bonding with the Bumble device over LE transport from the DUT.
     *   <li>Confirm the pairing on the Android side.
     *   <li>Disconnect Bumble to simulate a CTKD failure scenario.
     *   <li>Restart device discovery on the Android side.
     *   <li>Initiate bonding with the Bumble device over BR/EDR transport from the DUT.
     *   <li>Confirm the pairing on the Android side.
     *   <li>Verify the bonding success over BREDR.
     * </ol>
     *
     * <p>Expectation:
     *
     * <ul>
     *   <li>The initial LE pairing attempt fails due to intentional disconnection.
     *   <li>The subsequent BR/EDR pairing attempt from the DUT successfully bonds with Bumble,
     *       demonstrating that the BR/EDR pairing can proceed independently after a failed LE CTKD
     *       attempt.
     * </ul>
     */
    @Test
    public void testCtkd_FailureScenario() throws Exception {
        registerIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST,
                BluetoothDevice.ACTION_FOUND);

        mBumble.bumbleConfigBlocking()
                .override(
                        BumbleConfigProto.OverrideRequest.newBuilder()
                                .setIoCapability(BumbleConfigProto.IoCapability.NO_OUTPUT_NO_INPUT)
                                .setPairingConfig(
                                        BumbleConfigProto.PairingConfig.newBuilder()
                                                .setSc(true)
                                                .setMitm(true)
                                                .setBonding(true)
                                                .setIdentityAddressType(OwnAddressType.PUBLIC)
                                                .build())
                                .addInitiatorKeyDistribution(
                                        BumbleConfigProto.KeyDistribution.ENCRYPTION_KEY)
                                .addInitiatorKeyDistribution(
                                        BumbleConfigProto.KeyDistribution.IDENTITY_KEY)
                                .addInitiatorKeyDistribution(
                                        BumbleConfigProto.KeyDistribution.SIGNING_KEY)
                                .addInitiatorKeyDistribution(
                                        BumbleConfigProto.KeyDistribution.LINK_KEY)
                                .addResponderKeyDistribution(
                                        BumbleConfigProto.KeyDistribution.ENCRYPTION_KEY)
                                .addResponderKeyDistribution(
                                        BumbleConfigProto.KeyDistribution.IDENTITY_KEY)
                                .addResponderKeyDistribution(
                                        BumbleConfigProto.KeyDistribution.SIGNING_KEY)
                                .addResponderKeyDistribution(
                                        BumbleConfigProto.KeyDistribution.LINK_KEY)
                                .build());

        AdvertiseRequest.Builder requestBuilder =
                AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(OwnAddressType.PUBLIC);

        StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> responseObserver =
                new StreamObserverSpliterator<>();
        // Start advertising from bumble side with address type PUBLIC
        mBumble.host().advertise(requestBuilder.build(), responseObserver);

        // Make Bumble discoverable
        mBumble.hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                                .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                                .build());
        testStepStartDiscovery();

        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_LE)).isTrue();

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));
        Iterator<AdvertiseResponse> responseObserverIterator = responseObserver.iterator();
        AdvertiseResponse advertiseResponse = responseObserverIterator.next();
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        // Disconnect from Bumble
        mBumble.hostBlocking()
                .disconnect(
                        DisconnectRequest.newBuilder()
                                .setConnection(advertiseResponse.getConnection())
                                .build());

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        testStepStartDiscovery();

        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_BREDR)).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        mBumbleDevice.setPairingConfirmation(true);

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        unregisterIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_PAIRING_REQUEST,
                BluetoothDevice.ACTION_FOUND);
    }

    /** Helper/testStep functions go here */
    /**
     * Helper function to start device discovery
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Android starts discovery of remote devices
     * </ol>
     *
     * <p>Expectation:
     *
     * <ol>
     *   <li>Android receives discovery started intent
     *   <li>Android receives discovery finished intent
     *   <li>Checks whether Bumble device was found
     * </ol>
     */
    private void testStepStartDiscovery() throws Exception {
        registerIntentActions(BluetoothDevice.ACTION_FOUND);
        mBumbleDevice = null;

        // Start device discovery from Android
        mDeviceFound = new CompletableFuture<>();
        assertThat(mAdapter.startDiscovery()).isTrue();
        mBumbleDevice =
                mDeviceFound
                        .completeOnTimeout(null, DISCOVERY_TIMEOUT, TimeUnit.MILLISECONDS)
                        .join();
        assertThat(mBumbleDevice).isNotNull();
        assertThat(mAdapter.cancelDiscovery()).isTrue();

        unregisterIntentActions(BluetoothDevice.ACTION_FOUND);
    }

    private void testStep_Bond(
            IntentReceiver parentIntentReceiver, BluetoothDevice device, int transport) {
        IntentReceiver intentReceiver =
                IntentReceiver.update(
                        parentIntentReceiver,
                        new IntentReceiver.Builder(
                                mContext,
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                                BluetoothDevice.ACTION_ACL_CONNECTED,
                                BluetoothDevice.ACTION_PAIRING_REQUEST));

        // Create bond
        assertThat(device.createBond(transport)).isTrue();

        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, transport));

        intentReceiver.verifyReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Approve pairing from Android
        assertThat(device.setPairingConfirmation(true)).isTrue();

        // Ensure that pairing succeeds
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        intentReceiver.close();
    }

    private void testStep_Advertise(OwnAddressType ownAddressType) {
        // Start advertising
        int addrType =
                (ownAddressType == OwnAddressType.RANDOM)
                        ? AdvertisingSetParameters.ADDRESS_TYPE_RANDOM
                        : AdvertisingSetParameters.ADDRESS_TYPE_PUBLIC;
        BluetoothLeAdvertiser leAdvertiser = mAdapter.getBluetoothLeAdvertiser();
        AdvertisingSetParameters parameters =
                new AdvertisingSetParameters.Builder()
                        .setOwnAddressType(addrType)
                        .setConnectable(true)
                        .build();
        AdvertiseData advertiseData =
                new AdvertiseData.Builder().setIncludeDeviceName(true).build();
        AdvertisingSetCallback advertisingSetCallback = new AdvertisingSetCallback() {};
        leAdvertiser.startAdvertisingSet(
                parameters, advertiseData, null, null, null, 0, 0, advertisingSetCallback);
    }

    private static void testStep_restartBt() {
        assertThat(BlockingBluetoothAdapter.disable(true)).isTrue();
        assertThat(BlockingBluetoothAdapter.enable()).isTrue();
    }

    @SafeVarargs
    private void verifyIntentReceived(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(BOND_INTENT_TIMEOUT.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    private void removeBond(BluetoothDevice device) {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        assertThat(device.removeBond()).isTrue();
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        mContext.unregisterReceiver(mReceiver);
    }

    /**
     * Helper function to add reference count to registered intent actions
     *
     * @param actions new intent actions to add. If the array is empty, it is a no-op.
     */
    private void registerIntentActions(String... actions) {
        if (actions.length == 0) {
            return;
        }
        if (getTotalActionRegistrationCounts() > 0) {
            Log.d(TAG, "registerIntentActions(): unregister ALL intents");
            mContext.unregisterReceiver(mReceiver);
        }
        for (String action : actions) {
            mActionRegistrationCounts.merge(action, 1, Integer::sum);
        }
        IntentFilter filter = new IntentFilter();
        mActionRegistrationCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .forEach(
                        entry -> {
                            Log.d(
                                    TAG,
                                    "registerIntentActions(): Registering action = "
                                            + entry.getKey());
                            filter.addAction(entry.getKey());
                        });
        mContext.registerReceiver(mReceiver, filter);
    }

    /**
     * Helper function to reduce reference count to registered intent actions If total reference
     * count is zero after removal, no broadcast receiver will be registered.
     *
     * @param actions intent actions to be removed. If some action is not registered, it is no-op
     *     for that action. If the actions array is empty, it is also a no-op.
     */
    private void unregisterIntentActions(String... actions) {
        if (actions.length == 0) {
            return;
        }
        if (getTotalActionRegistrationCounts() <= 0) {
            return;
        }
        Log.d(TAG, "unregisterIntentActions(): unregister ALL intents");
        mContext.unregisterReceiver(mReceiver);
        for (String action : actions) {
            if (!mActionRegistrationCounts.containsKey(action)) {
                continue;
            }
            mActionRegistrationCounts.put(action, mActionRegistrationCounts.get(action) - 1);
            if (mActionRegistrationCounts.get(action) <= 0) {
                mActionRegistrationCounts.remove(action);
            }
        }
        if (getTotalActionRegistrationCounts() > 0) {
            IntentFilter filter = new IntentFilter();
            mActionRegistrationCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .forEach(
                            entry -> {
                                Log.d(
                                        TAG,
                                        "unregisterIntentActions(): Registering action = "
                                                + entry.getKey());
                                filter.addAction(entry.getKey());
                            });
            mContext.registerReceiver(mReceiver, filter);
        }
    }

    /**
     * Get sum of reference count from all registered actions
     *
     * @return sum of reference count from all registered actions
     */
    private int getTotalActionRegistrationCounts() {
        return mActionRegistrationCounts.values().stream().reduce(0, Integer::sum);
    }
}
