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

package android.bluetooth.pairing.utils

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.Utils
import android.content.Context
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import com.google.common.truth.Truth.assertThat
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.time.Duration
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.SecretKeySpec
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.kotlin.eq

class TestUtil
private constructor(
    private val context: Context,
    private val profileServiceListener: BluetoothProfile.ServiceListener?,
    private val adapter: BluetoothAdapter?,
) {

    class Builder(private val context: Context) {
        private var profileServiceListener: BluetoothProfile.ServiceListener? = null
        private var adapter: BluetoothAdapter? = null

        fun setProfileServiceListener(profileServiceListener: BluetoothProfile.ServiceListener) =
            apply {
                this.profileServiceListener = profileServiceListener
            }

        fun setBluetoothAdapter(adapter: BluetoothAdapter) = apply { this.adapter = adapter }

        fun build() = TestUtil(context, profileServiceListener, adapter)
    }

    /**
     * Helper function to remove the bond for the given device
     *
     * @param parentIntentReceiver IntentReceiver instance from the parent test caller This should
     *   be `null` if there is no parent IntentReceiver instance.
     * @param device The device to remove the bond for
     */
    fun removeBond(parentIntentReceiver: IntentReceiver?, device: BluetoothDevice) {
        val intentReceiver =
            IntentReceiver.update(
                parentIntentReceiver,
                IntentReceiver.Builder(context, BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            )

        assertThat(device.removeBond()).isTrue()
        intentReceiver.verifyReceivedOrdered(
            hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )

        intentReceiver.close()
    }

    /**
     * Get the profile proxy for the given profile
     *
     * @param profile The profile to get the proxy for
     * @return The profile proxy
     * @throws RuntimeException if profileServiceListener || adapter is null (passed during instance
     *   creation)
     */
    fun getProfileProxy(profile: Int): BluetoothProfile {
        if (profileServiceListener == null || adapter == null) {
            throw RuntimeException(
                "TestUtil: ServiceListener or BluetoothAdapter in getProfileProxy() is NULL"
            )
        }

        adapter.getProfileProxy(context, profileServiceListener, profile)
        val proxyCaptor = ArgumentCaptor.forClass(BluetoothProfile::class.java)
        verify(profileServiceListener, timeout(BOND_INTENT_TIMEOUT.toMillis()))
            .onServiceConnected(eq(profile), proxyCaptor.capture())
        return proxyCaptor.value
    }

    companion object {
        private val BOND_INTENT_TIMEOUT = Duration.ofSeconds(10)

        /**
         * Generate Resolvable Private Address with IRK
         *
         * @param irk irk to be used for generating RPA
         * @return RPA address string
         */
        @Throws(Exception::class)
        fun generateRpa(irk: ByteArray?): String {
            if (irk == null) {
                return ""
            }

            val prand = generatePrand()
            val addressHash = generateAddressHash(irk, prand)
            val addressByte = ByteArray(6)
            System.arraycopy(addressHash, 0, addressByte, 0, addressHash.size)
            System.arraycopy(prand, 0, addressByte, addressHash.size, prand.size)
            return Utils.addresStringFromBytes(addressByte)
        }

        /**
         * Generates a random 3-byte PRAND, with the 2 most significant bits of the third byte set
         * to 0b01. As per Bluetooth spec, Vol 6, Part E - Table 1.2.
         *
         * @return A 3-byte array representing the PRAND.
         */
        fun generatePrand(): ByteArray {
            val secureRandom = SecureRandom()
            val prandBytesFull = ByteArray(6)
            val prand = ByteArray(3)
            secureRandom.nextBytes(prandBytesFull)
            System.arraycopy(prandBytesFull, 0, prand, 0, 2)

            // Apply the bitwise operation to the third byte
            // prand_bytes[2] & 0b01111111: Clears the two most significant bits
            // | 0b01000000: Sets the two most significant bits to 01 (decimal 64)
            prand[2] = ((prandBytesFull[2].toInt() and 0x7F) or 0x40).toByte()

            return prand
        }

        /**
         * As per Bluetooth spec Vol 3, Part H - 2.2.2 Random Address Hash function
         *
         * @param irk IRK
         * @param prand random bytes
         * @return Address hash byte array
         */
        @Throws(Exception::class)
        fun generateAddressHash(irk: ByteArray, prand: ByteArray): ByteArray {
            // Padding of 13 zero bytes
            val padding = ByteArray(13)

            // Concatenate r and padding to form r_prime
            val rPrime = prand + padding

            val eResult = encryptWithIrk(irk, rPrime)

            // Extract the first 3 bytes from the result of 'encryptWithIrk'
            return eResult.copyOfRange(0, 3)
        }

        /**
         * AES-128 ECB, expecting byte-swapped inputs and producing a byte-swapped output. As per
         * Bluetooth spec Vol 3, Part H - 2.2.1 Security function
         *
         * @param key IRK
         * @param data Random data
         * @return Byte Array of encrypted data
         */
        @Throws(
            NoSuchPaddingException::class,
            NoSuchAlgorithmException::class,
            InvalidKeyException::class,
            BadPaddingException::class,
            IllegalBlockSizeException::class,
        )
        private fun encryptWithIrk(key: ByteArray, data: ByteArray): ByteArray {
            val swappedKey = reverseByteArray(key)
            val swappedData = reverseByteArray(data)

            // Initialize AES cipher in ECB mode
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val secretKey = SecretKeySpec(swappedKey, "AES")

            // ECB mode does not use an IV, so we don't need IvParameterSpec here.
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            // Encrypt the byte-swapped data
            val encryptedData = cipher.doFinal(swappedData)

            // Byte-swap the encrypted output
            return reverseByteArray(encryptedData)!!
        }

        /**
         * Helper method to reverse a byte array
         *
         * @param array input byte array
         * @return reversed byte array
         */
        private fun reverseByteArray(array: ByteArray?): ByteArray? {
            return array?.reversedArray()
        }
    }
}
