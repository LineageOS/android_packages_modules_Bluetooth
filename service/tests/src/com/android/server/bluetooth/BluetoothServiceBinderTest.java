/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.bluetooth;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE;
import static android.Manifest.permission.LOCAL_MAC_ADDRESS;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.quality.Strictness.STRICT_STUBS;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.IBinder;
import android.os.Process;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.flags.Flags;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import kotlin.Unit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;
import java.util.function.BooleanSupplier;

@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
public class BluetoothServiceBinderTest {
    private static final String TAG = BluetoothServiceBinderTest.class.getSimpleName();
    private static final String LOG_COMPAT_CHANGE = "android.permission.LOG_COMPAT_CHANGE";
    private static final String READ_COMPAT_CHANGE_CONFIG =
            "android.permission.READ_COMPAT_CHANGE_CONFIG";

    @Rule public final MockitoRule mMockitoRule = new MockitoRule().strictness(STRICT_STUBS);
    @Rule public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private BluetoothManagerServiceApi mApi;
    @Mock private UserManager mUserManager;
    @Mock private AppOpsManager mAppOpsManager;
    @Mock private DevicePolicyManager mDevicePolicyManager;

    private final Context mContext =
            spy(
                    new ContextWrapper(
                            InstrumentationRegistry.getInstrumentation().getTargetContext()));

    private final AttributionSource mSource =
            spy(new AttributionSource.Builder(Process.myUid()).build());

    private BluetoothServiceBinder mBinder;
    private TestLooper mLooper;
    private InOrder mInOrder;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf();
    }

    public BluetoothServiceBinderTest(FlagsWrapper flagsWrapper) {
        mSetFlagsRule = new SetFlagsRule(flagsWrapper.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        mInOrder = inOrder(mUserManager);
        lenient().doReturn(TAG).when(mSource).getPackageName();
        mLooper = new TestLooper();
        mLooper.startAutoDispatch();

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(LOG_COMPAT_CHANGE, READ_COMPAT_CHANGE_CONFIG);

        final String appOps = mContext.getSystemServiceName(AppOpsManager.class);
        final String devicePolicy = mContext.getSystemServiceName(DevicePolicyManager.class);
        doReturn(mAppOpsManager).when(mContext).getSystemService(eq(appOps));
        doReturn(mDevicePolicyManager).when(mContext).getSystemService(eq(devicePolicy));
        doReturn(mUserManager).when(mContext).getSystemService(UserManager.class);

        mBinder = new BluetoothServiceBinder(mLooper.getLooper(), mApi, mContext);
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
        // Do not call verifyMock here. If the test fails the initial error will be lost
    }

    @Test
    public void getMessenger() {
        assertThat(mBinder.getServiceMessenger()).isNotNull();
        verifyMock();
    }

    @Test
    @DisableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER)
    public void registerAdapter() {
        assertThrows(NullPointerException.class, () -> mBinder.registerAdapter(null));
        mBinder.registerAdapter(mock(IBluetoothManagerCallback.class));
        verify(mApi).registerAdapter(any());
        verifyMock();
    }

    @Test
    @DisableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER)
    public void unregisterAdapter() {
        assertThrows(NullPointerException.class, () -> mBinder.unregisterAdapter(null));
        mBinder.unregisterAdapter(mock(IBluetoothManagerCallback.class));
        verify(mApi).unregisterAdapter(any());
        verifyMock();
    }

    @Test
    @DisableCompatChanges({ChangeIds.RESTRICT_ENABLE_DISABLE})
    @DisableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER)
    public void enableNoRestrictEnable() throws Exception {
        assertThrows(NullPointerException.class, () -> mBinder.enable(null));

        checkDisabled(() -> mBinder.enable(mSource));
        checkHardDenied(() -> mBinder.enable(mSource), true);
        doReturn(true).when(mApi).enable(anyInt(), any());
        checkGranted(() -> mBinder.enable(mSource), true);
        verify(mUserManager).getProfileParent(any());
        verify(mApi).enable(eq(ENABLE_DISABLE_REASON_APPLICATION_REQUEST), eq(TAG));
        verifyMock();
    }

    @Test
    @EnableCompatChanges({ChangeIds.RESTRICT_ENABLE_DISABLE})
    @DisableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER)
    public void enableWithRestrictEnable() throws Exception {
        assertThrows(NullPointerException.class, () -> mBinder.enable(null));

        checkDisabled(() -> mBinder.enable(mSource));
        checkHardDenied(() -> mBinder.enable(mSource), true);
        checkGranted(() -> mBinder.enable(mSource), false);
        verify(mUserManager).getProfileParent(any());
        verifyMock();

        // TODO(b/280518177): add more test around compatChange
    }

    @Test
    @DisableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER)
    public void enableNoAutoConnect() throws Exception {
        assertThrows(NullPointerException.class, () -> mBinder.enableNoAutoConnect(null));

        checkDisabled(() -> mBinder.enableNoAutoConnect(mSource));
        checkHardDenied(() -> mBinder.enableNoAutoConnect(mSource), false);

        // enableNoAutoConnect is only available for Nfc and will fail otherwise
        assertThrows(SecurityException.class, () -> mBinder.enableNoAutoConnect(mSource));

        verify(mAppOpsManager).checkPackage(anyInt(), eq(TAG));
        verifyMock();

        // TODO(b/280518177): add test that simulate NFC caller to have a successful case
    }

    @Test
    @DisableCompatChanges({ChangeIds.RESTRICT_ENABLE_DISABLE})
    @DisableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER)
    public void disableNoRestrictEnable() throws Exception {
        assertThrows(NullPointerException.class, () -> mBinder.disable(null, true));

        assertThrows(SecurityException.class, () -> mBinder.disable(mSource, false));

        checkDisabled(() -> mBinder.disable(mSource, true));
        checkHardDenied(() -> mBinder.disable(mSource, true), true);
        doReturn(true).when(mApi).disable(any(), anyBoolean());
        checkGranted(() -> mBinder.disable(mSource, true), true);
        verify(mUserManager).getProfileParent(any());
        verify(mApi).disable(eq(TAG), anyBoolean());
        verifyMock();
    }

    @Test
    @EnableCompatChanges({ChangeIds.RESTRICT_ENABLE_DISABLE})
    @DisableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER)
    public void disableWithRestrictEnable() throws Exception {
        assertThrows(NullPointerException.class, () -> mBinder.disable(null, true));

        assertThrows(SecurityException.class, () -> mBinder.disable(mSource, false));

        checkDisabled(() -> mBinder.disable(mSource, true));
        checkHardDenied(() -> mBinder.disable(mSource, true), true);
        checkGranted(() -> mBinder.disable(mSource, true), false);
        verify(mUserManager).getProfileParent(any());
        verifyMock();

        // TODO(b/280518177): add more test around compatChange
    }

    @Test
    public void getStateFromSystemServer() {
        mBinder.getState();
        verify(mApi).getState();
        verifyMock();
    }

    @Test
    @DisableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER)
    public void getAddress() {
        assertThrows(NullPointerException.class, () -> mBinder.getAddress(null));

        assertThrows(SecurityException.class, () -> mBinder.getAddress(mSource));
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(BLUETOOTH_CONNECT);

        // TODO(b/280518177): Throws SecurityException and remove DEFAULT_MAC_ADDRESS
        // assertThrows(SecurityException.class, () -> mBinder.getAddress(mSource));
        assertThat(mBinder.getAddress(mSource)).isEqualTo("02:00:00:00:00:00");
        verifyMockForCheckIfCallerIsForegroundUser();

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(BLUETOOTH_CONNECT, LOCAL_MAC_ADDRESS);

        // TODO(b/280518177): add more test from not System / ...
        // TODO(b/280518177): add more test when caller is not in foreground

        doReturn("foo").when(mApi).getAddress();
        assertThat(mBinder.getAddress(mSource)).isEqualTo("foo");

        verify(mApi).getAddress();
        verifyMockForCheckIfCallerIsForegroundUser();
    }

    @Test
    public void getName() {
        mSetFlagsRule.disableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER);
        assertThrows(NullPointerException.class, () -> mBinder.getName(null));

        assertThrows(SecurityException.class, () -> mBinder.getName(mSource));
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(BLUETOOTH_CONNECT);

        // TODO(b/280518177): add more test from not System / ...
        // TODO(b/280518177): add more test when caller is not in foreground

        doReturn("foo").when(mApi).getName();
        assertThat(mBinder.getName(mSource)).isEqualTo("foo");
        verify(mApi).getName();
        verifyMockForCheckIfCallerIsForegroundUser();
    }

    @Test
    public void isBleScanAvailable() {
        mSetFlagsRule.disableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER);
        // No permission needed for this call
        mBinder.isBleScanAvailable();
        verify(mApi).isBleScanAvailable();
        verifyMock();
    }

    @Test
    @DisableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER)
    public void enableBle() throws Exception {
        IBinder token = mock(IBinder.class);
        assertThrows(NullPointerException.class, () -> mBinder.enableBle(null, token));
        assertThrows(NullPointerException.class, () -> mBinder.enableBle(mSource, null));

        checkDisabled(() -> mBinder.enableBle(mSource, token));
        checkHardDenied(() -> mBinder.enableBle(mSource, token), false);
        doReturn(true).when(mApi).enableBle(eq(TAG), eq(token));
        checkGranted(() -> mBinder.enableBle(mSource, token), true);
        verify(mApi).enableBle(eq(TAG), eq(token));
        verifyMock();
    }

    @Test
    @DisableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER)
    public void disableBle() throws Exception {
        IBinder token = mock(IBinder.class);
        assertThrows(NullPointerException.class, () -> mBinder.disableBle(null, token));
        assertThrows(NullPointerException.class, () -> mBinder.disableBle(mSource, null));

        checkDisabled(() -> mBinder.disableBle(mSource, token));
        checkHardDenied(() -> mBinder.disableBle(mSource, token), false);
        doReturn(true).when(mApi).disableBle(eq(TAG), eq(token));
        checkGranted(() -> mBinder.disableBle(mSource, token), true);
        verify(mApi).disableBle(eq(TAG), eq(token));
        verifyMock();
    }

    @Test
    public void isHearingAidProfileSupported() {
        mSetFlagsRule.disableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER);
        // No permission needed for this call
        mBinder.isHearingAidProfileSupported();
        verify(mApi).isHearingAidProfileSupported();
        verifyMock();
    }

    @Test
    public void setBtHciSnoopLogMode() {
        mSetFlagsRule.disableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER);
        assertThrows(SecurityException.class, () -> mBinder.setBtHciSnoopLogMode(0));

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(BLUETOOTH_PRIVILEGED);
        assertThat(mBinder.setBtHciSnoopLogMode(0)).isEqualTo(0);
        verify(mApi).setBtHciSnoopLogMode(anyInt());
        verifyMock();
    }

    @Test
    public void getBtHciSnoopLogMode() {
        mSetFlagsRule.disableFlags(Flags.FLAG_BLUETOOTH_SYSTEM_SERVER_MESSENGER);
        assertThrows(SecurityException.class, () -> mBinder.getBtHciSnoopLogMode());

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(BLUETOOTH_PRIVILEGED);
        assertThat(mBinder.getBtHciSnoopLogMode()).isEqualTo(0);
        verify(mApi).getBtHciSnoopLogMode();
        verifyMock();
    }

    // TODO(b/280518177): Add test for `handleShellCommand` and `dump`

    // *********************************************************************************************
    // Utility method used in tests

    private static void verifyAndClearMock(Object o) {
        assertThat(mockingDetails(o).isMock() || mockingDetails(o).isSpy()).isTrue();
        verifyNoMoreInteractions(o);
        clearInvocations(o);
    }

    private void verifyMock() {
        verifyAndClearMock(mApi);
        verifyAndClearMock(mUserManager);
        verifyAndClearMock(mAppOpsManager);
        verifyAndClearMock(mDevicePolicyManager);
    }

    private void verifyMockForCheckIfCallerIsForegroundUser() {
        verify(mUserManager).getProfileParent(any());
        verifyMock();
    }

    private void checkDisabled(BooleanSupplier binderCall) throws Exception {
        setUserRestriction(false);

        assertThat(binderCall.getAsBoolean()).isFalse();

        verifyMock();
    }

    private void checkHardDenied(ThrowingRunnable binderCall, boolean requireForeground)
            throws Exception {
        setUserRestriction(true);

        assertThrows(SecurityException.class, binderCall);

        if (requireForeground) {
            verify(mUserManager).getProfileParent(any());
        }
        verify(mAppOpsManager).checkPackage(anyInt(), eq(TAG));
        verifyMock();
    }

    private void checkGranted(BooleanSupplier binderCall, boolean expectedResult) {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                        LOG_COMPAT_CHANGE, READ_COMPAT_CHANGE_CONFIG, BLUETOOTH_CONNECT);

        assertThat(binderCall.getAsBoolean()).isEqualTo(expectedResult);

        verify(mAppOpsManager).checkPackage(anyInt(), eq(TAG));
        if (!expectedResult) {
            verify(mDevicePolicyManager).getDeviceOwnerUser();
            verify(mDevicePolicyManager).getDeviceOwnerComponentOnAnyUser();
        }
    }

    private void setUserRestriction(boolean isBluetoothAllowed) {
        doReturn(!isBluetoothAllowed)
                .when(mUserManager)
                .hasUserRestriction(eq(UserManager.DISALLOW_BLUETOOTH));
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(CHANGE_COMPONENT_ENABLED_STATE);
        BluetoothRestriction.handleRestrictionChange(mContext, () -> Unit.INSTANCE);
        mInOrder.verify(mUserManager).hasUserRestriction(eq(UserManager.DISALLOW_BLUETOOTH));
    }
}
