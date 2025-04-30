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

import io.grpc.stub.StreamObserver;

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

import pandora.HostProto;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.AdvertiseResponse;
import pandora.HostProto.ConnectabilityMode;
import pandora.HostProto.DiscoverabilityMode;
import pandora.HostProto.OwnAddressType;
import pandora.HostProto.SetConnectabilityModeRequest;
import pandora.HostProto.SetDiscoverabilityModeRequest;
import pandora.SecurityProto.PairingEvent;
import pandora.SecurityProto.PairingEventAnswer;

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
    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DISCOVERY_TIMEOUT = 2000; // 2 seconds
    private static final int LE_GENERAL_DISCOVERABLE = 2;

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mManager = mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mAdapter = mManager.getAdapter();

    private final Map<String, Integer> mActionRegistrationCounts = new HashMap<>();
    private final StreamObserverSpliterator<Void, PairingEvent> mPairingEventStreamObserver =
            new StreamObserverSpliterator<>();

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    private final BluetoothLeScanner mLeScanner = mAdapter.getBluetoothLeScanner();

    private BluetoothDevice mBumbleDevice;
    private BluetoothDevice mRemoteLeDevice;
    private InOrder mInOrder = null;
    private CompletableFuture<BluetoothDevice> mDeviceFound;
    private String mCfName;
    @Mock private BroadcastReceiver mReceiver;

    @SuppressLint("MissingPermission")
    private final Answer<Void> mIntentHandler =
            inv -> {
                Log.i(TAG, "onReceive(): intent=" + Arrays.toString(inv.getArguments()));
                Intent intent = inv.getArgument(1);
                String action = intent.getAction();
                switch (action) {
                    case BluetoothDevice.ACTION_FOUND:
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
                        }
                        break;
                    default:
                        Log.i(TAG, "onReceive(): unknown intent action " + action);
                        break;
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
     * Test LE pairing flow with Auto transport
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is non discoverable over BR/EDR and discoverable over LE
     *   <li>Bumble LE AD Flags in advertisement support dual mode
     *   <li>Android starts discovery of remote devices
     *   <li>Android initiates pairing with Bumble using Auto transport
     * </ol>
     *
     * <p>Expectation: Pairing succeeds over LE Transport
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_AUTO_TRANSPORT_PAIRING})
    public void testBondLe_AutoTransport() throws Exception {
        registerIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);

        // Make Bumble Non discoverable over BR/EDR
        mBumble.hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                        .setMode(DiscoverabilityMode.NOT_DISCOVERABLE)
                        .build());

        // Make Bumble Non connectable over BR/EDR
        SetConnectabilityModeRequest request =
                SetConnectabilityModeRequest.newBuilder()
                        .setMode(ConnectabilityMode.NOT_CONNECTABLE)
                        .build();
        mBumble.hostBlocking().setConnectabilityMode(request);

        // Start LE advertisement from Bumble
        AdvertiseRequest.Builder requestBuilder =
                AdvertiseRequest.newBuilder().setLegacy(true)
                .setConnectable(true)
                .setOwnAddressType(OwnAddressType.PUBLIC);

        HostProto.DataTypes.Builder dataTypeBuilder = HostProto.DataTypes.newBuilder();
        dataTypeBuilder.setCompleteLocalName(BUMBLE_DEVICE_NAME);
        dataTypeBuilder.setIncludeCompleteLocalName(true);
        //Set LE AD Flags to be LE General discoverable, also supports dual mode
        dataTypeBuilder.setLeDiscoverabilityModeValue(LE_GENERAL_DISCOVERABLE);
        requestBuilder.setData(dataTypeBuilder.build());

        StreamObserverSpliterator<AdvertiseRequest, AdvertiseResponse> responseObserver =
                new StreamObserverSpliterator<>();
        mBumble.host().advertise(requestBuilder.build(), responseObserver);

        // Start Device Discovery from Android
        testStepStartDiscovery();

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(),
                            TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        // Start pairing from Android with Auto transport
        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_AUTO)).isTrue();

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent)
                        .setConfirm(true).build());

        // Ensure that pairing succeeds
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        unregisterIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);
    }

    /**
     * Test BR/EDR pairing flow with Auto transport
     *
     * <p>Prerequisites:
     *
     * <ol>
     *   <li>Bumble and Android are not bonded
     * </ol>
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Bumble is discoverable over BR/EDR and non discoverable over LE
     *   <li>Android starts discovery of remote devices
     *   <li>Android initiates pairing with Bumble using Auto transport
     * </ol>
     *
     * <p>Expectation: Pairing succeeds over BR/EDR Transport
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_AUTO_TRANSPORT_PAIRING})
    public void testBondBrEdr_AutoTransport() throws Exception {
        registerIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);

        // Make Bumble discoverable over BR/EDR
        mBumble.hostBlocking()
                .setDiscoverabilityMode(
                        SetDiscoverabilityModeRequest.newBuilder()
                        .setMode(DiscoverabilityMode.DISCOVERABLE_GENERAL)
                        .build());

        SetConnectabilityModeRequest request =
                SetConnectabilityModeRequest.newBuilder()
                        .setMode(ConnectabilityMode.CONNECTABLE)
                        .build();
        mBumble.hostBlocking().setConnectabilityMode(request);

        // Start Device Discovery from Android
        testStepStartDiscovery();

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(),
                            TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);

        // Start pairing from Android with Auto transport
        assertThat(mBumbleDevice.createBond(BluetoothDevice.TRANSPORT_AUTO)).isTrue();

        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_ACL_CONNECTED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR));
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_PAIRING_REQUEST),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.PAIRING_VARIANT_CONSENT));

        // Approve pairing from Android
        assertThat(mBumbleDevice.setPairingConfirmation(true)).isTrue();

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

        // Ensure that pairing succeeds
        verifyIntentReceived(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mBumbleDevice),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED));

        unregisterIntentActions(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_PAIRING_REQUEST);
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

        StreamObserver<PairingEventAnswer> pairingEventAnswerObserver =
                mBumble.security()
                        .withDeadlineAfter(BOND_INTENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .onPairing(mPairingEventStreamObserver);
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

        PairingEvent pairingEvent = mPairingEventStreamObserver.iterator().next();
        assertThat(pairingEvent.hasJustWorks()).isTrue();
        pairingEventAnswerObserver.onNext(
                PairingEventAnswer.newBuilder().setEvent(pairingEvent).setConfirm(true).build());

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
