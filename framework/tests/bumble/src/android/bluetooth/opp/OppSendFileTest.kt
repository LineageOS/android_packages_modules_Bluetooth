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

package android.bluetooth.opp

import android.Manifest
import android.app.KeyguardManager
import android.bluetooth.Host
import android.bluetooth.PandoraDevice
import android.bluetooth.adapter
import android.bluetooth.test_utils.EnableBluetoothRule
import android.content.ClipData
import android.content.Intent
import android.content.Intent.EXTRA_STREAM
import android.net.Uri
import android.os.Build
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test cases for sending a file via OPP using a share intent. Uses the Bumble helper app as a
 * separate package to launch [Intent.ACTION_SEND] or [Intent.ACTION_SEND_MULTIPLE] intents with
 * content URIs that it may (not) have permissions to access.
 */
@RunWith(TestParameterInjector::class)
@ExperimentalCoroutinesApi
class OppSendFileTest {

    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule(order = 1)
    val permissionRule =
        AdoptShellPermissionsRule(
            instrumentation.uiAutomation,
            Manifest.permission.BLUETOOTH_PRIVILEGED,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.INTERACT_ACROSS_USERS,
        )

    @get:Rule(order = 2) val bumble = PandoraDevice()

    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    val context = instrumentation.targetContext
    val device
        get() = UiDevice.getInstance(instrumentation)

    val remoteDevice
        get() = bumble.remoteDevice

    lateinit var host: Host

    @Before
    fun setUp() {
        host = Host(context)
        navigateToUnlockedHomeScreen()
        adapter.bondedDevices.forEach(host::removeBondAndVerify)
        host.createBondAndVerify(remoteDevice)
    }

    private fun navigateToUnlockedHomeScreen() {
        val keyguardManager: KeyguardManager = context.getSystemService(KeyguardManager::class.java)
        if (keyguardManager.isKeyguardLocked) {
            dismissKeyguard()
        }
        device.pressHome()
    }

    private fun dismissKeyguard() {
        val keyguardManager: KeyguardManager = context.getSystemService(KeyguardManager::class.java)
        retryUntil(condition = { !keyguardManager.isKeyguardLocked }) {
            device.executeShellCommand("wm dismiss-keyguard")
        }
    }

    private fun retryUntil(
        times: Int = DEFAULT_MAX_RETRIES,
        delay: Duration = DEFAULT_RETRY_DELAY,
        condition: () -> Boolean,
        block: () -> Unit,
    ) {
        for (i in 1..times) {
            block()
            if (!condition()) {
                Thread.sleep(delay.inWholeMilliseconds)
            }
            if (condition()) {
                break
            }
            assertThat(i).isAtMost(times)
        }
    }

    @After
    fun tearDown() {
        remoteDevice.removeBond()
        device.pressBack()
        device.pressHome()
    }

    @Test
    @Throws(Exception::class)
    @RequiresFlagsEnabled("com.android.bluetooth.flags.opp_check_content_uri_permissions")
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun sendViaBluetoothShare(
        @TestParameter crossUser: Boolean,
        @TestParameter hasPermission: Boolean,
        @TestParameter hasGrant: Boolean,
    ) =
        contentUriTest("content://${authority(crossUser, hasPermission)}/test_image.png") { uris ->
            val intent = createSendIntent(uris.single(), hasGrant)

            startIntentAndAssertDevicePicker(
                intent,
                shouldSucceed = !crossUser && (hasPermission || hasGrant),
            )
        }

    private fun createSendIntent(uri: Uri, grantUriPermission: Boolean): Intent =
        Intent(Intent.ACTION_MAIN)
            .putExtra(EXTRA_STREAM, uri)
            .setFlags(flags(grantUriPermission))
            .setClassName(HELPER_PACKAGE, SEND_ACTIVITY)
            .setData(uri)

    @Test
    @Throws(Exception::class)
    @RequiresFlagsEnabled("com.android.bluetooth.flags.opp_check_content_uri_permissions")
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun sendMultipleViaBluetoothShare(
        @TestParameter crossUser: Boolean,
        @TestParameter hasPermission: Boolean,
        @TestParameter hasGrant: Boolean,
    ) =
        contentUriTest(
            "content://${authority(crossUser, hasPermission)}/test_image.png",
            "content://${authority(crossUser, hasPermission)}/test_image2.png",
        ) { uris ->
            val intent = createSendMultipleIntent(uris, hasGrant)

            startIntentAndAssertDevicePicker(
                intent,
                shouldSucceed = !crossUser && (hasPermission || hasGrant),
            )
        }

    @Test
    @Throws(Exception::class)
    @RequiresFlagsEnabled("com.android.bluetooth.flags.opp_check_content_uri_permissions")
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun sendMultipleViaBluetoothShare_oneAlwaysAllowed(
        @TestParameter crossUser: Boolean,
        @TestParameter hasPermission: Boolean,
        @TestParameter hasGrant: Boolean,
    ) =
        contentUriTest(
            "content://${authority(crossUser, hasPermission)}/test_image.png",
            "content://$PERMITTED_AUTHORITY/test_image2.png",
        ) { uris ->
            val intent = createSendMultipleIntent(uris, hasGrant)

            startIntentAndAssertDevicePicker(intent, shouldSucceed = true)
        }

    private fun createSendMultipleIntent(
        uris: ArrayList<Uri>,
        grantUriPermission: Boolean,
    ): Intent =
        Intent(Intent.ACTION_MAIN)
            .putExtra(EXTRA_STREAM, uris)
            .setType(MIME_TYPE_IMAGE)
            .setFlags(flags(grantUriPermission))
            .setClassName(HELPER_PACKAGE, SEND_MULTIPLE_ACTIVITY)
            .apply { clipData = uris.toClipData() }

    private fun ArrayList<Uri>.toClipData(): ClipData {
        val items = map(ClipData::Item)
        return ClipData("URIs", arrayOf(MIME_TYPE_IMAGE), items.first()).apply {
            items.drop(1).forEach(::addItem)
        }
    }

    private fun startIntentAndAssertDevicePicker(intent: Intent, shouldSucceed: Boolean) {
        context.startActivity(intent)

        val devicePickerText =
            device.wait(Until.findObject(By.text("Available devices")), TIMEOUT_MS)
        if (shouldSucceed) {
            assertThat(devicePickerText).isNotNull()

            val name = remoteDevice.name

            val shareToBumbleButton =
                device.wait(Until.findObject(By.textStartsWith(name)), ASSERT_TIMEOUT_MS)

            assertThat(shareToBumbleButton).isNotNull()
        } else {
            assertThat(devicePickerText).isNull()
        }
    }

    private fun authority(crossUser: Boolean, hasPermission: Boolean): String {
        val hostPart =
            if (hasPermission) {
                PERMITTED_AUTHORITY
            } else {
                RESTRICTED_AUTHORITY
            }
        return if (crossUser) {
            "$OTHER_USER_ID@$hostPart"
        } else {
            hostPart
        }
    }

    private fun flags(grantUriPermission: Boolean) =
        Intent.FLAG_ACTIVITY_NEW_TASK or
            if (grantUriPermission) {
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            } else {
                0
            }

    /** Provides parsed content URIs to the test block. Cleans up URI grants before and after. */
    private fun contentUriTest(vararg uriString: String, test: (uris: ArrayList<Uri>) -> Unit) {
        val uris = uriString.map(Uri::parse).let(::ArrayList)
        AutoCloseable { uris.revokePermissions() }
            .use {
                uris.revokePermissions()
                test(uris)
            }
    }

    private fun List<Uri>.revokePermissions() = forEach {
        context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        private const val MIME_TYPE_IMAGE = "image/*"
        private const val TIMEOUT_MS = 5000L
        private const val ASSERT_TIMEOUT_MS = 10000L
        private const val PERMITTED_AUTHORITY = "android.bluetooth.opp"
        private const val RESTRICTED_AUTHORITY = "android.bluetooth.opp.restricted"
        private const val HELPER_PACKAGE = "android.bluetooth.helper"
        private const val SEND_ACTIVITY =
            "android.bluetooth.helper.opp.SendToBluetoothShareActivity"
        private const val SEND_MULTIPLE_ACTIVITY =
            "android.bluetooth.helper.opp.SendMultipleToBluetoothShareActivity"
        private const val OTHER_USER_ID = 10
        private const val DEFAULT_MAX_RETRIES = 8
        private val DEFAULT_RETRY_DELAY = 250.milliseconds
    }
}
