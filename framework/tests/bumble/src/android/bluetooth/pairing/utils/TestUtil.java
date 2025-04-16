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

package android.bluetooth.pairing.utils;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.Utils;
import android.content.Context;

import org.mockito.ArgumentCaptor;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

public class TestUtil {
    private static final String TAG = TestUtil.class.getSimpleName();

    private static final Duration BOND_INTENT_TIMEOUT = Duration.ofSeconds(10);

    private final Context mTargetContext;
    private final BluetoothProfile.ServiceListener mProfileServiceListener;
    private final BluetoothAdapter mAdapter;

    private TestUtil(Builder builder) {
        mTargetContext = builder.mTargetContext;
        mProfileServiceListener = builder.mProfileServiceListener;
        mAdapter = builder.mAdapter;
    }

    public static class Builder {
        /* Target context is required for all the test functions */
        private final Context mTargetContext;

        private BluetoothProfile.ServiceListener mProfileServiceListener;
        private BluetoothAdapter mAdapter;

        public Builder(@NonNull Context context) {
            mTargetContext = context;
            mProfileServiceListener = null;
            mAdapter = null;
        }

        public Builder setProfileServiceListener(
                BluetoothProfile.ServiceListener profileServiceListener) {
            mProfileServiceListener = profileServiceListener;
            return this;
        }

        public Builder setBluetoothAdapter(BluetoothAdapter adapter) {
            mAdapter = adapter;
            return this;
        }

        public TestUtil build() {
            return new TestUtil(this);
        }
    }

    /**
     * Helper function to remove the bond for the given device
     *
     * @param parentIntentReceiver IntentReceiver instance from the parent test caller This should
     *     be `null` if there is no parent IntentReceiver instance.
     * @param device The device to remove the bond for
     */
    public void removeBond(IntentReceiver parentIntentReceiver, BluetoothDevice device) {
        IntentReceiver intentReceiver =
                IntentReceiver.update(
                        parentIntentReceiver,
                        new IntentReceiver.Builder(
                                mTargetContext, BluetoothDevice.ACTION_BOND_STATE_CHANGED));

        assertThat(device.removeBond()).isTrue();
        intentReceiver.verifyReceivedOrdered(
                hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE));

        intentReceiver.close();
    }

    /**
     * Get the profile proxy for the given profile
     *
     * @param profile The profile to get the proxy for
     * @throws RuntimeException if mProfileServiceListener || mAdapter is null (passed during
     *     instance creation)
     * @return The profile proxy
     */
    public BluetoothProfile getProfileProxy(int profile) {
        if (mProfileServiceListener == null || mAdapter == null) {
            throw new RuntimeException(
                    "TestUtil: ServiceListener or BluetoothAdapter in getProfileProxy() is NULL");
        }

        mAdapter.getProfileProxy(mTargetContext, mProfileServiceListener, profile);
        ArgumentCaptor<BluetoothProfile> proxyCaptor =
                ArgumentCaptor.forClass(BluetoothProfile.class);
        verify(mProfileServiceListener, timeout(BOND_INTENT_TIMEOUT.toMillis()))
                .onServiceConnected(eq(profile), proxyCaptor.capture());
        return proxyCaptor.getValue();
    }

    /**
     * Generate Resolvable Private Address with IRK
     *
     * @param irk irk to be used for generating RPA
     * @return RPA address string
     */
    public static String generateRpa(byte[] irk) throws Exception {
        if (irk != null) {
            byte[] prand = generatePrand();
            byte[] addressHash = generateAddressHash(irk, prand);
            byte[] addressByte = new byte[6];
            System.arraycopy(addressHash, 0, addressByte, 0, addressHash.length);
            System.arraycopy(prand, 0, addressByte, addressHash.length, prand.length);
            return Utils.addresStringFromBytes(addressByte);
        } else {
            return "";
        }
    }

    /**
     * Generates a random 3-byte PRAND, with the 2 most significant bits of the third byte set to
     * 0b01. As per Bluetooth spec, Vol 6, Part E - Table 1.2.
     *
     * @return A 3-byte array representing the PRAND.
     */
    public static byte[] generatePrand() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] prandBytesFull = new byte[6];
        byte[] prand = new byte[3];
        secureRandom.nextBytes(prandBytesFull);
        System.arraycopy(prandBytesFull, 0, prand, 0, 2);

        // Apply the bitwise operation to the third byte
        // prand_bytes[2] & 0b01111111: Clears the two most significant bits
        // | 0b01000000: Sets the two most significant bits to 01 (decimal 64)
        prand[2] = (byte) ((prandBytesFull[2] & 0x7F) | 0x40);

        return prand;
    }

    /**
     * As per Bluetooth spec Vol 3, Part H - 2.2.2 Random Address Hash function
     *
     * @param irk IRK
     * @param prand random bytes
     * @return Address hash byte array
     */
    public static byte[] generateAddressHash(byte[] irk, byte[] prand) throws Exception {
        // Padding of 13 zero bytes
        byte[] padding = new byte[13];

        // Concatenate r and padding to form r_prime
        byte[] r_prime = new byte[prand.length + padding.length];
        System.arraycopy(prand, 0, r_prime, 0, prand.length);
        System.arraycopy(padding, 0, r_prime, prand.length, padding.length);

        byte[] eResult = encryptWithIrk(irk, r_prime);

        // Extract the first 3 bytes from the result of 'encryptWithIrk'
        byte[] hash = new byte[3];
        System.arraycopy(eResult, 0, hash, 0, 3);

        return hash;
    }

    /**
     * AES-128 ECB, expecting byte-swapped inputs and producing a byte-swapped output. As per
     * Bluetooth spec Vol 3, Part H - 2.2.1 Security function
     *
     * @param key IRK
     * @param data Random data
     * @return Byte Array of encrypted data
     */
    private static byte[] encryptWithIrk(byte[] key, byte[] data)
            throws NoSuchPaddingException,
                    NoSuchAlgorithmException,
                    InvalidKeyException,
                    BadPaddingException,
                    IllegalBlockSizeException {
        byte[] swappedKey = reverseByteArray(key);
        byte[] swappedData = reverseByteArray(data);

        // Initialize AES cipher in ECB mode
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        SecretKeySpec secretKey = new SecretKeySpec(swappedKey, "AES");

        // ECB mode does not use an IV, so we don't need IvParameterSpec here.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        // Encrypt the byte-swapped data
        byte[] encryptedData = cipher.doFinal(swappedData);

        // Byte-swap the encrypted output
        return reverseByteArray(encryptedData);
    }

    /**
     * Helper method to reverse a byte array
     *
     * @param array input byte array
     * @return reversed byte array
     */
    private static byte[] reverseByteArray(byte[] array) {
        if (array == null) {
            return null;
        }
        byte[] reversedArray = new byte[array.length];
        for (int i = 0; i < array.length; i++) {
            reversedArray[i] = array[array.length - 1 - i];
        }
        return reversedArray;
    }
}
