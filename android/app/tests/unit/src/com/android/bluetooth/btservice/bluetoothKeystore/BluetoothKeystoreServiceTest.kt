/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.btservice.bluetoothkeystore

import android.os.Binder
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.security.NoSuchAlgorithmException
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock

private const val TAG = "BluetoothKeystoreServiceTest"

/** Test cases for [BluetoothKeystoreService]. */
@RunWith(AndroidJUnit4::class)
class BluetoothKeystoreServiceTest {

    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var mockNativeInterface: BluetoothKeystoreNativeInterface

    private val configTestData =
        listOf(
            "[Info]",
            "FileSource = Empty",
            "TimeCreated = XXXX-XX-XX XX:XX:XX",
            "",
            "[Metrics]",
            "Salt256Bit = aaaaaaaaaaaaaaaaaaa",
            "",
            "[Adapter]",
            "Address = 11:22:33:44:55:66",
            "LE_LOCAL_KEY_IRK = IRK1234567890",
            "LE_LOCAL_KEY_IR = IR1234567890",
            "LE_LOCAL_KEY_DHK = DHK1234567890",
            "LE_LOCAL_KEY_ER = ER1234567890",
            "ScanMode = 0",
            "DiscoveryTimeout = 120",
            "",
            "[aa:bb:cc:dd:ee:ff]",
            "Timestamp = 12345678",
            "Name = Test",
            "DevClass = 1234567",
            "LinkKey = 11223344556677889900aabbccddeeff",
            "LE_KEY_PENC = ec111111111111111111111111111111111111111111111111111111",
            "LE_KEY_PID = d222222222222222222222222222222222222222222222",
            "LE_KEY_PCSRK = c33333333333333333333333333333333333333333333333",
            "LE_KEY_LENC = eec4444444444444444444444444444444444444",
            "LE_KEY_LCSRK = aec555555555555555555555555555555555555555555555",
            "LE_KEY_LID =",
        )

    private val nameDecryptKeyResult = mutableMapOf<String, String>()

    private var configData: List<String> = ArrayList()

    private lateinit var bluetoothKeystoreService: BluetoothKeystoreService

    @Before
    fun setUp() {
        Assume.assumeTrue("Ignore test when the user is not primary.", isPrimaryUser())
        bluetoothKeystoreService = BluetoothKeystoreService(mockNativeInterface)
        bluetoothKeystoreService.init(true)
        // backup origin config data.
        try {
            configData = Files.readAllLines(Paths.get(CONFIG_FILE_PATH))
        } catch (e: IOException) {
            Log.wtf(TAG, "Read file fail", e)
        }
        // create a nameDecryptKeyResult for comparing.
        createNameDecryptKeyResult()
    }

    @After
    fun tearDown() {
        if (!isPrimaryUser()) {
            return
        }
        try {
            if (!configData.isEmpty()) {
                Files.write(Paths.get(CONFIG_FILE_PATH), configData)
            }
            bluetoothKeystoreService.cleanupAll()
        } catch (e: IOException) {
            Log.wtf(TAG, "Write back file fail or clean up encryption file", e)
        }
        bluetoothKeystoreService.stopThread()
    }

    private fun isPrimaryUser() = Binder.getCallingUid() == Process.BLUETOOTH_UID

    private fun overwriteConfigFile(data: List<String>) {
        try {
            Files.write(Paths.get(CONFIG_FILE_PATH), data)
        } catch (e: IOException) {
            Log.wtf(TAG, "Write file fail", e)
        }
    }

    private fun createNameDecryptKeyResult() {
        nameDecryptKeyResult["aa:bb:cc:dd:ee:ff-LinkKey"] = "11223344556677889900aabbccddeeff"
        nameDecryptKeyResult["aa:bb:cc:dd:ee:ff-LE_KEY_PENC"] =
            "ec111111111111111111111111111111111111111111111111111111"
        nameDecryptKeyResult["aa:bb:cc:dd:ee:ff-LE_KEY_PID"] =
            "d222222222222222222222222222222222222222222222"
        nameDecryptKeyResult["aa:bb:cc:dd:ee:ff-LE_KEY_PCSRK"] =
            "c33333333333333333333333333333333333333333333333"
        nameDecryptKeyResult["aa:bb:cc:dd:ee:ff-LE_KEY_LENC"] =
            "eec4444444444444444444444444444444444444"
        nameDecryptKeyResult["aa:bb:cc:dd:ee:ff-LE_KEY_LCSRK"] =
            "aec555555555555555555555555555555555555555555555"
    }

    private fun parseConfigFile(filePathString: String): Boolean {
        try {
            bluetoothKeystoreService.parseConfigFile(filePathString)
            return true
        } catch (e: IOException) {
            return false
        } catch (e: InterruptedException) {
            return false
        }
    }

    private fun loadEncryptionFile(filePathString: String, doDecrypt: Boolean): Boolean {
        try {
            bluetoothKeystoreService.loadEncryptionFile(filePathString, doDecrypt)
            return true
        } catch (e: InterruptedException) {
            return false
        }
    }

    private fun setEncryptKeyOrRemoveKey(prefixString: String, decryptedString: String): Boolean {
        try {
            bluetoothKeystoreService.setEncryptKeyOrRemoveKey(prefixString, decryptedString)
            return true
        } catch (e: InterruptedException) {
            return false
        } catch (e: IOException) {
            return false
        } catch (e: NoSuchAlgorithmException) {
            return false
        }
    }

    private fun compareFileHash(hashFilePathString: String): Boolean {
        try {
            return bluetoothKeystoreService.compareFileHash(hashFilePathString)
        } catch (e: InterruptedException) {
            return false
        } catch (e: IOException) {
            return false
        } catch (e: NoSuchAlgorithmException) {
            return false
        }
    }

    @Test
    fun testParserFile() {
        // over write config
        overwriteConfigFile(configTestData)
        // load config file.
        assertThat(parseConfigFile(CONFIG_FILE_PATH)).isTrue()
        // make sure it is same with createNameDecryptKeyResult
        assertThat(bluetoothKeystoreService.nameDecryptKey).isEqualTo(nameDecryptKeyResult)
    }

    @Test
    fun testEncrypt() {
        // load config file and put the unencrypted key in to queue.
        testParserFile()
        // Wait for encryption to complete
        bluetoothKeystoreService.stopThread()

        assertThat(bluetoothKeystoreService.nameDecryptKey.keys)
            .containsExactlyElementsIn(nameDecryptKeyResult.keys)
    }

    @Test
    fun testDecrypt() {
        // create an encrypted key list and save it.
        testEncrypt()
        bluetoothKeystoreService.saveEncryptedKey()
        // clear up memory.
        bluetoothKeystoreService.cleanupMemory()
        // load encryption file and do encryption.
        assertThat(loadEncryptionFile(CONFIG_FILE_ENCRYPTION_PATH, true)).isTrue()
        // Wait for encryption to complete
        bluetoothKeystoreService.stopThread()

        assertThat(bluetoothKeystoreService.nameDecryptKey).isEqualTo(nameDecryptKeyResult)
    }

    @Test
    fun testCompareHashFile() {
        // save config checksum.
        assertThat(setEncryptKeyOrRemoveKey(CONFIG_FILE_PREFIX, CONFIG_FILE_HASH)).isTrue()
        // clean up memory
        bluetoothKeystoreService.cleanupMemory()

        assertThat(loadEncryptionFile(CONFIG_CHECKSUM_ENCRYPTION_PATH, false)).isTrue()

        assertThat(compareFileHash(CONFIG_FILE_PATH)).isTrue()
    }

    @Test
    fun testParserFileAfterDisableCommonCriteriaMode() {
        // preconfiguration.
        // need to create encrypted file.
        testParserFile()
        // created encrypted file
        assertThat(setEncryptKeyOrRemoveKey(CONFIG_FILE_PREFIX, CONFIG_FILE_HASH)).isTrue()
        // clean up memory and stop thread.
        bluetoothKeystoreService.cleanupForCommonCriteriaModeEnable()

        // new bluetoothKeystoreService and the Common Criteria mode is false.
        bluetoothKeystoreService = BluetoothKeystoreService(mockNativeInterface)
        bluetoothKeystoreService.init(false)
        bluetoothKeystoreService.loadConfigData()

        // check encryption file clean up.
        assertThat(Files.exists(Paths.get(CONFIG_CHECKSUM_ENCRYPTION_PATH))).isFalse()
        assertThat(Files.exists(Paths.get(CONFIG_FILE_ENCRYPTION_PATH))).isFalse()

        // remove hash data avoid interfering result.
        bluetoothKeystoreService.nameDecryptKey.remove(CONFIG_FILE_PREFIX)

        assertThat(bluetoothKeystoreService.nameDecryptKey).isEqualTo(nameDecryptKeyResult)
    }

    @Test
    fun testParserFileAfterDisableCommonCriteriaModeWhenEnableCommonCriteriaMode() {
        testParserFileAfterDisableCommonCriteriaMode()
        bluetoothKeystoreService.cleanupForCommonCriteriaModeDisable()

        // new bluetoothKeystoreService and the Common Criteria mode is true.
        bluetoothKeystoreService = BluetoothKeystoreService(mockNativeInterface)
        bluetoothKeystoreService.init(true)
        bluetoothKeystoreService.loadConfigData()

        assertThat(bluetoothKeystoreService.compareResult).isEqualTo(0)
    }

    companion object {
        // Please also check bt_stack string configuration if you want to change the content.
        private const val CONFIG_FILE_PREFIX = "bt_config-origin"
        private const val CONFIG_FILE_HASH = "hash"
        private const val CONFIG_FILE_PATH = "/data/misc/bluedroid/bt_config.conf"
        private const val CONFIG_FILE_ENCRYPTION_PATH =
            "/data/misc/bluedroid/bt_config.conf.encrypted"
        private const val CONFIG_CHECKSUM_ENCRYPTION_PATH =
            "/data/misc/bluedroid/bt_config.checksum.encrypted"
    }
}
