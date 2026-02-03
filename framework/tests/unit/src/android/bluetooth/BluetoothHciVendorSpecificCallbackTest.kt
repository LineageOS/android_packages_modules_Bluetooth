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

package android.bluetooth

import android.bluetooth.BluetoothAdapter.BluetoothHciVendorSpecificCallback
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.Context
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.flags.Flags
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import org.junit.After
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.after
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify

/**
 * Test cases for {@link BluetoothHciVendorSpecificCallback}. The test implementation relies on
 * RootCanal test commands, the tests are skipped when running on a different platform.
 */
@RunWith(AndroidJUnit4::class)
class BluetoothHciVendorSpecificCallbackTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val enableBluetoothRule = EnableBluetoothRule(false, true)

    @Mock private lateinit var callback: BluetoothHciVendorSpecificCallback

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    companion object {
        private const val OCF_ROOTCANAL_VENDOR = 0x01
        private const val SUB_OPCODE_SEND_HCI_EVENT = 0x01
        private const val SUB_OPCODE_SEND_HCI_ACL_DATA = 0x02
    }

    @After
    fun tearDown() {
        adapter.unregisterBluetoothHciVendorSpecificCallback(callback)
    }

    /**
     * Verify that a vendor event received with a registered subevent code is reported to the
     * BluetoothHciVendorSpecificCallback API.
     */
    @Test
    fun onEvent_registeredEventCode() {
        adapter.registerBluetoothHciVendorSpecificCallback(setOf(0x60), { it.run() }, callback)

        val eventParameters = byteArrayOf(0x01, 0x02, 0x03)
        val commandParameters = ByteArray(eventParameters.size + 4)
        commandParameters[0] = SUB_OPCODE_SEND_HCI_EVENT.toByte()
        commandParameters[1] = 0xFF.toByte() // Event Code : Vendor Specific Event
        commandParameters[2] = (1 + eventParameters.size).toByte() // Parameters Length
        commandParameters[3] = 0x60.toByte() // SubEvent Code : 0x60
        System.arraycopy(eventParameters, 0, commandParameters, 4, eventParameters.size)

        adapter.sendBluetoothHciVendorSpecificCommand(OCF_ROOTCANAL_VENDOR, commandParameters)
        assumeRootCanalSupport(OCF_ROOTCANAL_VENDOR)

        verify(callback, timeout(200)).onEvent(eq(0x60), eq(eventParameters))
    }

    /**
     * Verify that a vendor event received with an unregistered subevent code is not reported to the
     * BluetoothHciVendorSpecificCallback API.
     */
    @Test
    fun onEvent_ignoredEventCode() {
        adapter.registerBluetoothHciVendorSpecificCallback(setOf(0x60), { it.run() }, callback)

        val eventParameters = byteArrayOf(0x01, 0x02, 0x03)
        val commandParameters = ByteArray(eventParameters.size + 4)
        commandParameters[0] = SUB_OPCODE_SEND_HCI_EVENT.toByte()
        commandParameters[1] = 0xFF.toByte() // Event Code : Vendor Specific Event
        commandParameters[2] = (1 + eventParameters.size).toByte() // Parameters Length
        commandParameters[3] = 0x61.toByte() // SubEvent Code : 0x61
        System.arraycopy(eventParameters, 0, commandParameters, 4, eventParameters.size)

        adapter.sendBluetoothHciVendorSpecificCommand(OCF_ROOTCANAL_VENDOR, commandParameters)
        assumeRootCanalSupport(OCF_ROOTCANAL_VENDOR)

        verify(callback, after(200).never()).onEvent(any(), any())
    }

    /**
     * Verify that an ACL vendor event received with a registered handle is reported to the
     * BluetoothHciVendorSpecificCallback API.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_REPORT_VENDOR_EVENTS_FROM_ACL)
    fun onAclEvent_registeredAclHandle() {
        val vendorAclHandle =
            0x300 // The range of vendor ACL handles reported by RootCanal is 0x300-0x3ff
        adapter.registerBluetoothHciVendorSpecificCallback(
            emptySet(),
            setOf(vendorAclHandle),
            { it.run() },
            callback,
        )

        val aclData = byteArrayOf(0x01, 0x02, 0x03)
        val commandParameters = ByteArray(aclData.size + 5)
        commandParameters[0] = SUB_OPCODE_SEND_HCI_ACL_DATA.toByte()
        commandParameters[1] = (vendorAclHandle and 0xFF).toByte() // Connection Handle
        commandParameters[2] = ((vendorAclHandle shr 8) and 0xFF).toByte()
        commandParameters[3] = (aclData.size and 0xFF).toByte() // Data Length
        commandParameters[4] = ((aclData.size shr 8) and 0xFF).toByte()
        System.arraycopy(aclData, 0, commandParameters, 5, aclData.size)

        adapter.sendBluetoothHciVendorSpecificCommand(OCF_ROOTCANAL_VENDOR, commandParameters)
        assumeRootCanalSupport(OCF_ROOTCANAL_VENDOR)

        verify(callback, timeout(200)).onAclEvent(eq(vendorAclHandle), eq(aclData))
    }

    /**
     * Verify that an ACL vendor event received with an unregistered handle is not reported to the
     * BluetoothHciVendorSpecificCallback API.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_REPORT_VENDOR_EVENTS_FROM_ACL)
    fun onAclEvent_ignoredAclHandle() {
        val vendorAclHandle =
            0x300 // The range of vendor ACL handles reported by RootCanal is 0x300-0x3ff
        adapter.registerBluetoothHciVendorSpecificCallback(
            emptySet(),
            setOf(0x301),
            { it.run() },
            callback,
        )

        val aclData = byteArrayOf(0x01, 0x02, 0x03)
        val commandParameters = ByteArray(aclData.size + 5)
        commandParameters[0] = SUB_OPCODE_SEND_HCI_ACL_DATA.toByte()
        commandParameters[1] = (vendorAclHandle and 0xFF).toByte() // Connection Handle
        commandParameters[2] = ((vendorAclHandle shr 8) and 0xFF).toByte()
        commandParameters[3] = (aclData.size and 0xFF).toByte() // Data Length
        commandParameters[4] = ((aclData.size shr 8) and 0xFF).toByte()
        System.arraycopy(aclData, 0, commandParameters, 5, aclData.size)

        adapter.sendBluetoothHciVendorSpecificCommand(OCF_ROOTCANAL_VENDOR, commandParameters)
        assumeRootCanalSupport(OCF_ROOTCANAL_VENDOR)

        verify(callback, after(200).never()).onAclEvent(any(), any())
    }

    private fun assumeRootCanalSupport(ocf: Int) {
        try {
            // We expect onCommandComplete for the RootCanal vendor commands.
            // Receiving onCommandStatus is equally a failure.
            val returnParametersCaptor = argumentCaptor<ByteArray>()
            verify(callback, timeout(500))
                .onCommandComplete(eq(ocf), returnParametersCaptor.capture())
            val returnParameters = returnParametersCaptor.firstValue
            Assume.assumeTrue(
                "RootCanal command failed with empty return parameters",
                returnParameters.isNotEmpty(),
            )
            Assume.assumeTrue(
                "RootCanal command failed with status: ${returnParameters[0]}",
                returnParameters[0].toInt() == 0,
            )
        } catch (t: Throwable) {
            Assume.assumeNoException("RootCanal command $ocf not supported or failed", t)
        }
    }
}
