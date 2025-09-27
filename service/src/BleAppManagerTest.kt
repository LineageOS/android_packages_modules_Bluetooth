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

package com.android.server.bluetooth.test

import android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_DIED
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.android.server.bluetooth.BleAppManager
import com.google.common.truth.Truth.assertThat
import java.io.FileDescriptor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BleAppManagerTest {

    private class FakeBinder(private val throwOnLinkToDeath: Boolean = false) : IBinder {
        var deathRecipient: IBinder.DeathRecipient? = null

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (throwOnLinkToDeath) {
                throw android.os.RemoteException("Binder is dead")
            }
            deathRecipient = recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            if (deathRecipient == recipient) {
                deathRecipient = null
                return true
            }
            return false
        }

        override fun getInterfaceDescriptor(): String? = null

        override fun isBinderAlive(): Boolean = true

        override fun pingBinder(): Boolean = true

        override fun queryLocalInterface(descriptor: String): IInterface? = null

        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false

        override fun dump(fd: FileDescriptor, args: Array<out String>?) {}

        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}
    }

    private val token = FakeBinder()
    private val packageName = "com.test.app"
    private val bleAppManager =
        BleAppManager({ r -> r.run() }) { reason, packageName ->
            callbackReason = reason
            callbackPackageName = packageName
            callbackTriggered = true
        }

    private var callbackReason = -1
    private var callbackPackageName = ""
    private var callbackTriggered = false

    @Before
    fun setUp() {
        bleAppManager.clearBleApps()
        callbackTriggered = false
        callbackReason = -1
        callbackPackageName = ""
    }

    @Test
    fun addBleApp_addsAppAndLinksToDeath() {
        assertThat(bleAppManager.isBleAppPresent()).isFalse()

        assertThat(bleAppManager.addBleApp(token, packageName)).isTrue()

        assertThat(bleAppManager.isBleAppPresent()).isTrue()
        assertThat(token.deathRecipient).isNotNull()
    }

    @Test
    fun addBleApp_forSameApp_doesNothing() {
        bleAppManager.addBleApp(token, packageName)
        val deathRecipient = token.deathRecipient
        assertThat(bleAppManager.isBleAppPresent()).isTrue()
        assertThat(bleAppManager.toString()).isEqualTo("{$packageName}")

        // Add same app again
        assertThat(bleAppManager.addBleApp(token, packageName)).isTrue()

        // No change
        assertThat(token.deathRecipient).isEqualTo(deathRecipient)
        assertThat(bleAppManager.isBleAppPresent()).isTrue()
        assertThat(bleAppManager.toString()).isEqualTo("{$packageName}")
    }

    @Test
    fun removeBleApp_removesAppAndUnlinks() {
        bleAppManager.addBleApp(token, packageName)
        assertThat(bleAppManager.isBleAppPresent()).isTrue()
        assertThat(token.deathRecipient).isNotNull()

        val reason = 1
        bleAppManager.removeBleApp(reason, token, packageName)

        assertThat(bleAppManager.isBleAppPresent()).isFalse()
        assertThat(token.deathRecipient).isNull()
        assertThat(callbackTriggered).isTrue()
        assertThat(callbackReason).isEqualTo(reason)
        assertThat(callbackPackageName).isEqualTo(packageName)
    }

    @Test
    fun removeBleApp_whenOtherAppsPresent_doesNotTriggerCallback() {
        bleAppManager.addBleApp(token, packageName)

        val token2 = FakeBinder()
        val packageName2 = "com.test.secondApp"
        bleAppManager.addBleApp(token2, packageName2)

        assertThat(bleAppManager.isBleAppPresent()).isTrue()
        assertThat(bleAppManager.toString()).contains(packageName)
        assertThat(bleAppManager.toString()).contains(packageName2)

        val reason = 1
        bleAppManager.removeBleApp(reason, token, packageName)

        assertThat(bleAppManager.isBleAppPresent()).isTrue()
        assertThat(bleAppManager.toString()).doesNotContain(packageName)
        assertThat(bleAppManager.toString()).contains(packageName2)
        assertThat(callbackTriggered).isFalse()
    }

    @Test
    fun removeBleApp_forNonExistentApp_doesNothing() {
        bleAppManager.removeBleApp(1, token, packageName)

        assertThat(bleAppManager.isBleAppPresent()).isFalse()
        assertThat(callbackTriggered).isFalse()
        assertThat(token.deathRecipient).isNull()
    }

    @Test
    fun clearBleApps_removesAllApps() {
        bleAppManager.addBleApp(token, packageName)

        val token2 = FakeBinder()
        val packageName2 = "com.test.secondApp"
        bleAppManager.addBleApp(token2, packageName2)

        assertThat(bleAppManager.isBleAppPresent()).isTrue()

        bleAppManager.clearBleApps()

        assertThat(bleAppManager.isBleAppPresent()).isFalse()
        assertThat(token.deathRecipient).isNull()
        assertThat(token2.deathRecipient).isNull()
    }

    @Test
    fun binderDied_removesAppAndTriggersCallback() {
        bleAppManager.addBleApp(token, packageName)
        assertThat(bleAppManager.isBleAppPresent()).isTrue()
        val deathRecipient = token.deathRecipient
        assertThat(deathRecipient).isNotNull()

        deathRecipient!!.binderDied()

        assertThat(bleAppManager.isBleAppPresent()).isFalse()
        assertThat(token.deathRecipient).isNull()
        assertThat(callbackTriggered).isTrue()
        assertThat(callbackReason).isEqualTo(ENABLE_DISABLE_REASON_APPLICATION_DIED)
        assertThat(callbackPackageName).isEqualTo(packageName)
    }

    @Test
    fun addBleApp_forDeadBinder_returnsFalse() {
        val token = FakeBinder(throwOnLinkToDeath = true)
        val packageName = "com.test.app"

        assertThat(bleAppManager.addBleApp(token, packageName)).isFalse()
        assertThat(bleAppManager.isBleAppPresent()).isFalse()
    }

    @Test
    fun isBleAppPresent() {
        assertThat(bleAppManager.isBleAppPresent()).isFalse()
        bleAppManager.addBleApp(token, packageName)
        assertThat(bleAppManager.isBleAppPresent()).isTrue()
    }
}
