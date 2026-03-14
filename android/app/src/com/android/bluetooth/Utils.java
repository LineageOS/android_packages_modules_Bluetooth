/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.bluetooth;

import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothUtils.RemoteExceptionIgnoringRunnable;
import static android.bluetooth.BluetoothUtils.USER_HANDLE_NULL;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.content.AttributionSource;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Telephony;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Utils {
    private static final String TAG = Util.BT_PREFIX + Utils.class.getSimpleName();

    public static final int BD_ADDR_LEN = 6; // bytes
    public static final int TYPED_BD_ADDR_LEN = 7; // bytes
    private static final int BD_UUID_LEN = 16; // bytes

    /** Thread pool to handle background and outgoing blocking task */
    public static final ExecutorService BackgroundExecutor = Executors.newSingleThreadExecutor();

    public static final String PAIRING_UI_PROPERTY = "bluetooth.pairing_ui_package.name";

    private static final int MICROS_PER_UNIT = 625;
    private static final String PTS_TEST_MODE_PROPERTY = "persist.bluetooth.pts";

    private static final String ENABLE_DUAL_MODE_AUDIO = "persist.bluetooth.enable_dual_mode_audio";

    private static final String MAX_TX_POWER_DBM_PROPERTY = "bluetooth.ble.max_tx_power_dbm.config";
    private static final int DEFAULT_MAX_TX_POWER_DBM = 10;
    private static final int MAX_SUPPORTED_TX_POWER_DBM = 10;

    // See https://en.wikipedia.org/wiki/Initialization-on-demand_holder_idiom
    private static class DualModeAudioSetting {
        private static boolean sEnabled =
                SystemProperties.getBoolean(ENABLE_DUAL_MODE_AUDIO, false);
    }

    private static int sSystemUiUid = USER_HANDLE_NULL.getIdentifier();

    private Utils() {}

    static int getSystemUiUid() {
        return sSystemUiUid;
    }

    public static void setSystemUiUid(int uid) {
        sSystemUiUid = uid;
    }

    /**
     * Check if dual mode audio is enabled. This is set via the system property
     * persist.bluetooth.enable_dual_mode_audio.
     *
     * <p>When set to {@code false}, we will not connect A2DP and HFP on a dual mode (BR/EDR + BLE)
     * device. We will only attempt to use BLE Audio in this scenario.
     *
     * <p>When set to {@code true}, we will connect all the supported audio profiles (A2DP, HFP, and
     * LE Audio) at the same time. In this state, we will respect calls to profile-specific APIs
     * (e.g. if a SCO API is invoked, we will route audio over HFP). If no profile-specific API is
     * invoked to route audio (e.g. Telecom routed phone calls, media, game audio, etc.), then audio
     * will be routed in accordance with the preferred audio profiles for the remote device. You can
     * get the preferred audio profiles for a remote device by calling {@link
     * BluetoothAdapter#getPreferredAudioProfiles(BluetoothDevice)}.
     *
     * @return true if dual mode audio is enabled, false otherwise
     */
    public static boolean isDualModeAudioEnabled() {
        Log.i(TAG, "Dual mode enable state is: " + DualModeAudioSetting.sEnabled);
        return DualModeAudioSetting.sEnabled;
    }

    /**
     * Only exposed for testing, do not invoke this method outside of tests.
     *
     * @param enabled true if the dual mode state is enabled, false otherwise
     */
    public static void setDualModeAudioStateForTesting(boolean enabled) {
        Log.i(TAG, "Updating dual mode audio state for testing to: " + enabled);
        DualModeAudioSetting.sEnabled = enabled;
    }

    public static String getAddressStringFromByte(byte[] address) {
        if (address == null || address.length != BD_ADDR_LEN) {
            return null;
        }

        return String.format(
                "%02X:%02X:%02X:%02X:%02X:%02X",
                address[0], address[1], address[2], address[3], address[4], address[5]);
    }

    public static int byteArrayToInt(byte[] valueBuf) {
        return byteArrayToInt(valueBuf, 0);
    }

    public static long byteArrayToLong(byte[] valueBuf) {
        return byteArrayToLong(valueBuf, 0);
    }

    public static int byteArrayToInt(byte[] valueBuf, int offset) {
        ByteBuffer converter = ByteBuffer.wrap(valueBuf);
        converter.order(ByteOrder.nativeOrder());
        return converter.getInt(offset);
    }

    public static long byteArrayToLong(byte[] valueBuf, int offset) {
        ByteBuffer converter = ByteBuffer.wrap(valueBuf);
        converter.order(ByteOrder.nativeOrder());
        return converter.getLong(offset);
    }

    /**
     * A parser to transfer a byte array to a UTF8 string
     *
     * @param valueBuf the byte array to transfer
     * @return the transferred UTF8 string
     */
    public static String byteArrayToUtf8String(byte[] valueBuf) {
        CharsetDecoder decoder = Charset.forName("UTF8").newDecoder();
        ByteBuffer byteBuffer = ByteBuffer.wrap(valueBuf);
        String valueStr = "";
        try {
            valueStr = decoder.decode(byteBuffer).toString();
        } catch (Exception ex) {
            Log.e(TAG, "Error when parsing byte array to UTF8 String. " + ex);
        }
        return valueStr;
    }

    public static byte[] intToByteArray(int value) {
        ByteBuffer converter = ByteBuffer.allocate(4);
        converter.order(ByteOrder.nativeOrder());
        converter.putInt(value);
        return converter.array();
    }

    public static byte[] uuidToByteArray(ParcelUuid pUuid) {
        int length = BD_UUID_LEN;
        ByteBuffer converter = ByteBuffer.allocate(length);
        converter.order(ByteOrder.BIG_ENDIAN);
        long msb, lsb;
        UUID uuid = pUuid.getUuid();
        msb = uuid.getMostSignificantBits();
        lsb = uuid.getLeastSignificantBits();
        converter.putLong(msb);
        converter.putLong(8, lsb);
        return converter.array();
    }

    public static byte[] uuidsToByteArray(ParcelUuid[] uuids) {
        int length = uuids.length * BD_UUID_LEN;
        ByteBuffer converter = ByteBuffer.allocate(length);
        converter.order(ByteOrder.BIG_ENDIAN);
        UUID uuid;
        long msb, lsb;
        for (int i = 0; i < uuids.length; i++) {
            uuid = uuids[i].getUuid();
            msb = uuid.getMostSignificantBits();
            lsb = uuid.getLeastSignificantBits();
            converter.putLong(i * BD_UUID_LEN, msb);
            converter.putLong(i * BD_UUID_LEN + 8, lsb);
        }
        return converter.array();
    }

    public static ParcelUuid[] byteArrayToUuid(byte[] val) {
        int numUuids = val.length / BD_UUID_LEN;
        ParcelUuid[] puuids = new ParcelUuid[numUuids];
        int offset = 0;

        ByteBuffer converter = ByteBuffer.wrap(val);
        converter.order(ByteOrder.BIG_ENDIAN);

        for (int i = 0; i < numUuids; i++) {
            puuids[i] =
                    new ParcelUuid(
                            new UUID(converter.getLong(offset), converter.getLong(offset + 8)));
            offset += BD_UUID_LEN;
        }
        return puuids;
    }

    /**
     * Enforces that a Companion Device Manager (CDM) association exists between the calling
     * application and the Bluetooth Device.
     *
     * @param cdm the CompanionDeviceManager object
     * @param context the Bluetooth AdapterService context
     * @param callingPackage the calling package
     * @param device the remote BluetoothDevice
     * @return {@code true} if there is a CDM association
     * @throws SecurityException if the package name does not match the uid or the association
     *     doesn't exist
     */
    public static boolean enforceCdmAssociation(
            CompanionDeviceManager cdm,
            Context context,
            String callingPackage,
            BluetoothDevice device) {
        int callingUid = Binder.getCallingUid();
        if (!Util.isPackageNameAccurate(context, callingPackage, callingUid)) {
            throw new SecurityException(
                    "hasCdmAssociation: Package name "
                            + callingPackage
                            + " is inaccurate for calling uid "
                            + callingUid);
        }

        for (AssociationInfo association : cdm.getAllAssociations()) {
            if (association.getPackageName().equals(callingPackage)
                    && !association.isSelfManaged()
                    && device.getAddress() != null
                    && association.getDeviceMacAddress() != null
                    && device.getAddress()
                            .equalsIgnoreCase(association.getDeviceMacAddress().toString())) {
                return true;
            }
        }
        throw new SecurityException(
                "The application with package name "
                        + callingPackage
                        + " does not have a CDM association with the Bluetooth Device");
    }

    @RequiresPermission(value = BLUETOOTH_PRIVILEGED, conditional = true)
    public static void enforceCdmAssociationIfNotBluetoothPrivileged(
            Context context,
            CompanionDeviceManager cdm,
            AttributionSource source,
            BluetoothDevice device) {
        if (context.checkCallingOrSelfPermission(BLUETOOTH_PRIVILEGED) != PERMISSION_GRANTED) {
            enforceCdmAssociation(cdm, context, source.getPackageName(), device);
        }
    }

    /** Converts {@code milliseconds} to unit. Each unit is 0.625 millisecond. */
    public static int millsToUnit(int milliseconds) {
        return (int) (TimeUnit.MILLISECONDS.toMicros(milliseconds) / MICROS_PER_UNIT);
    }

    /**
     * Check if we are running in PTS test mode. To enable/disable PTS test mode, invoke {@code adb
     * shell setprop persist.bluetooth.pts true/false}
     *
     * @return true if in PTS Test mode, false otherwise
     */
    public static boolean isPtsTestMode() {
        return SystemProperties.getBoolean(PTS_TEST_MODE_PROPERTY, false);
    }

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /**
     * Get the current system local time as a formatted string.
     *
     * @return A formatted string representing the current time ("MM-dd HH:mm:ss.SSS")
     */
    public static String getLocalTimeString() {
        return formatInstant(Instant.now());
    }

    /**
     * Converts a time value from {@link android.os.SystemClock#elapsedRealtime()} to a
     * human-readable string.
     *
     * <p>To get a `long` time value, see {@link SystemClockTimeProvider#elapsedRealtime()}
     *
     * @param elapsedRealtimeMillis The timestamp from elapsedRealtime() to convert.
     * @return A formatted string representing the given time ("MM-dd HH:mm:ss.SSS").
     */
    public static String formatElapsedRealtime(long elapsedRealtimeMillis) {
        final long timeDeltaMillis = elapsedRealtimeMillis - SystemClock.elapsedRealtime();
        final long eventTimeEpochMillis = System.currentTimeMillis() + timeDeltaMillis;
        return formatInstant(Instant.ofEpochMilli(eventTimeEpochMillis));
    }

    /**
     * Formats a specific Instant into a system local time string.
     *
     * @param instant The Instant to format
     * @return A formatted string representing the given Instant ("MM-dd HH:mm:ss.SSS")
     */
    public static String formatInstant(Instant instant) {
        return DATE_TIME_FORMATTER.format(instant);
    }

    public static void skipCurrentTag(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {}
    }

    /**
     * Converts pause and tonewait pause characters to Android representation. RFC 3601 says pause
     * is 'p' and tonewait is 'w'.
     */
    public static String convertPreDial(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        int len = phoneNumber.length();
        StringBuilder ret = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c = phoneNumber.charAt(i);

            if (c == 'p' || c == 'P') {
                c = ',';
            } else if (c == 'w' || c == 'W') {
                c = ';';
            }
            ret.append(c);
        }
        return ret.toString();
    }

    /**
     * Move a message to the given folder.
     *
     * @param context the context to use
     * @param uri the message to move
     * @param messageSent if the message is SENT or FAILED
     * @return true if the operation succeeded
     */
    public static boolean moveMessageToFolder(Context context, Uri uri, boolean messageSent) {
        if (uri == null) {
            return false;
        }

        ContentValues values = new ContentValues(3);
        if (messageSent) {
            values.put(Telephony.Sms.READ, 1);
            values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT);
        } else {
            values.put(Telephony.Sms.READ, 0);
            values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED);
        }
        values.put(Telephony.Sms.ERROR_CODE, 0);

        return 1
                == BluetoothMethodProxy.getInstance()
                        .contentResolverUpdate(
                                context.getContentResolver(), uri, values, null, null);
    }

    /**
     * CCC descriptor short integer value to string.
     *
     * @param cccValue the short value of CCC descriptor
     * @return String value representing CCC state
     */
    public static String cccIntToStr(Short cccValue) {
        if (cccValue == 0) {
            return "NO SUBSCRIPTION";
        }

        final boolean isBit0Set = BigInteger.valueOf(cccValue).testBit(0);
        final boolean isBit1Set = BigInteger.valueOf(cccValue).testBit(1);
        if (isBit0Set && isBit1Set) {
            return "NOTIFICATION|INDICATION";
        }
        if (isBit0Set) {
            return "NOTIFICATION";
        }
        if (isBit1Set) {
            return "INDICATION";
        }
        return "";
    }

    /**
     * Reverses the elements of {@code array}. This is equivalent to {@code
     * Collections.reverse(Bytes.asList(array))}, but is likely to be more efficient.
     */
    public static void reverse(byte[] array) {
        requireNonNull(array);
        for (int i = 0, j = array.length - 1; i < j; i++, j--) {
            byte tmp = array[i];
            array[i] = array[j];
            array[j] = tmp;
        }
    }

    /**
     * @see android.bluetooth.BluetoothUtils.formatSimple
     */
    public static @NonNull String formatSimple(@NonNull String format, Object... args) {
        return android.bluetooth.BluetoothUtils.formatSimple(format, args);
    }

    /** Execute a remote callback without propagating the RemoteException of a dead app */
    public static void callbackToApp(RemoteExceptionIgnoringRunnable callback) {
        callback.run();
    }

    /** Invokes {@code toJoin.}{@link Thread#join() join()} uninterruptibly. */
    public static void joinUninterruptibly(Thread toJoin) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    toJoin.join();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void enforceMainLooperIsUsed() {
        if (Util.isInstrumentationTestMode()) {
            return;
        }
        if (!Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalThreadStateException("Must be called on main thread");
        }
    }

    public static void enforceMainLooperIsNotUsed() {
        if (Util.isInstrumentationTestMode()) {
            return;
        }
        if (Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalThreadStateException("Must NOT be called on main thread");
        }
    }

    public static boolean isAutonomousRepairingSupported() {
        // TODO (b/440298497): Change this to flag and android check once the SDK check CL is in.
        return com.android.bluetooth.flags.Flags.autonomousRepairingInitiation()
                && android.bluetooth.platform.flags.Flags.autonomousRepairingInitiation();
    }

    public static boolean isBluetoothPairingHardeningSupported() {
        return com.android.bluetooth.flags.Flags.apairing26q2PermissionImprovements()
                && android.bluetooth.platform.flags.Flags.bluetoothPairingHardening();
    }

    /** Determines the maximum TX power (in dBm) that's allowed for the system. */
    public static int getMaxTxPowerDbm() {
        if (!com.android.bluetooth.flags.Flags.allowMoreTxPower()) {
            return 1;
        }
        return Math.min(
                SystemProperties.getInt(MAX_TX_POWER_DBM_PROPERTY, DEFAULT_MAX_TX_POWER_DBM),
                MAX_SUPPORTED_TX_POWER_DBM);
    }
}
