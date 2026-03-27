/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.le_audio;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;

import static com.android.bluetooth.BluetoothStatsLog.BROADCAST_AUDIO_SESSION_REPORTED__AUDIO_QUALITY__QUALITY_HIGH;
import static com.android.bluetooth.BluetoothStatsLog.BROADCAST_AUDIO_SESSION_REPORTED__AUDIO_QUALITY__QUALITY_STANDARD;
import static com.android.bluetooth.BluetoothStatsLog.BROADCAST_AUDIO_SESSION_REPORTED__AUDIO_QUALITY__QUALITY_UNKNOWN;
import static com.android.bluetooth.le_audio.LeAudioConstants.INVALID_BROADCAST_ID;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioCodecStatus;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastSettings;
import android.bluetooth.BluetoothLeBroadcastSubgroupSettings;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeAudio;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.bluetooth.IBluetoothLeBroadcastCallback;
import android.bluetooth.IBluetoothVolumeControl;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.sysprop.BluetoothProperties;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.BluetoothEventLogger;
import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.bass_client.BassClientService.SetBigChannelMapClassificationAction;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.Config;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_scan.ScanController;
import com.android.bluetooth.metrics.MetricsLogger;
import com.android.bluetooth.profile.ConnectableProfile;
import com.android.bluetooth.profile.ProfileService;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.bluetooth.tbs.TbsGatt;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/** Provides Bluetooth LeAudio profile, as a service in the Bluetooth application. */
public class LeAudioService extends ConnectableProfile {
    private static final String TAG = LeAudioService.class.getSimpleName();

    // Timeout for state machine thread join, to prevent potential ANR.
    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;

    /* 5 seconds timeout for Broadcast streaming state transition */
    @VisibleForTesting static final int CREATE_BROADCAST_TIMEOUT_MS = 5000;

    /** Indicates group audio support for none direction */
    private static final int AUDIO_DIRECTION_NONE = 0x00;

    /** Indicates group audio support for output direction */
    private static final int AUDIO_DIRECTION_OUTPUT_BIT = 0x01;

    /** Indicates group audio support for input direction */
    private static final int AUDIO_DIRECTION_INPUT_BIT = 0x02;

    /** Indicates group is not active */
    private static final int ACTIVE_STATE_INACTIVE = 0x00;

    /** Indicates group is going to be active */
    private static final int ACTIVE_STATE_GETTING_ACTIVE = 0x01;

    /** Indicates group is active */
    private static final int ACTIVE_STATE_ACTIVE = 0x02;

    /** Filter for Targeted Announcements */
    static final byte[] CAP_TARGETED_ANNOUNCEMENT_PAYLOAD = new byte[] {0x01};

    /**
     * Per PBP 1.0 4.3. High Quality Public Broadcast Audio, Broadcast HIGH quality audio configs
     * are with sampling frequency 48khz
     */
    private static final BluetoothLeAudioCodecConfig BROADCAST_HIGH_QUALITY_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                    .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_48000)
                    .build();

    private final ReentrantReadWriteLock mGroupReadWriteLock = new ReentrantReadWriteLock();
    private final Lock mGroupReadLock = mGroupReadWriteLock.readLock();
    private final Lock mGroupWriteLock = mGroupReadWriteLock.writeLock();
    private final AudioServerScanCallback mScanCallback = new AudioServerScanCallback();
    private final ArrayDeque<BluetoothLeBroadcastSettings> mCreateBroadcastQueue =
            new ArrayDeque<>();

    private final LeAudioNativeInterface mNativeInterface;
    private final Optional<LeAudioBroadcasterNativeInterface> mLeAudioBroadcasterNativeInterface;
    private final ActiveDeviceManager mActiveDeviceManager;
    private final ScanController mScanController;
    private final HandlerThread mStateMachinesThread;
    private final LeAudioCodecConfig mLeAudioCodecConfig;
    private final AudioManager mAudioManager;
    private final int mTmapRoleMask;

    private volatile BluetoothDevice mActiveAudioOutDevice;
    private volatile BluetoothDevice mActiveAudioInDevice;
    private volatile BluetoothDevice mActiveBroadcastAudioDevice;
    private BluetoothDevice mExposedActiveDevice;
    private CreateBroadcastTimeoutEvent mCreateBroadcastTimeoutEvent;

    boolean mLeAudioNativeIsInitialized = false;
    boolean mLeAudioInbandRingtoneSupportedByPlatform = true;
    boolean mBluetoothEnabled = false;

    private boolean mSystemSuspended = false;

    private final ActivityManager mActivityManager;
    private final PackageManager mPackageManager;

    /**
     * During a call that has LE Audio -> HFP handover, the HFP device that is going to connect SCO
     * after LE Audio group becomes idle
     */
    BluetoothDevice mHfpHandoverDevice = null;

    /** LE audio active device that was removed from active because of HFP handover */
    BluetoothDevice mLeAudioDeviceInactivatedForHfpHandover = null;

    LeAudioTmapGattServer mTmapGattServer;
    int mUnicastGroupIdDeactivatedForBroadcastTransition = LE_AUDIO_GROUP_ID_INVALID;
    int mBroadcastToUnicastFallbackGroup = LE_AUDIO_GROUP_ID_INVALID;
    int mCurrentAudioMode = AudioManager.MODE_NORMAL;

    @VisibleForTesting
    class GameTracker {
        private int mUid;
        private Handler mHandler;
        static final int GAME_BACKGROUND_MONITOR_MS = 120000;

        GameTracker(int uid) {
            mUid = uid;
            mHandler = null;
        }

        void backgroundTimeoutHandler() {
            processGameImportanceChange(
                    mUid, ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE);
        }

        void startBackgroundMonitor(Looper looper) {
            if (mHandler != null) {
                Log.e(TAG, "startBackgroundMonitor: already started");
                return;
            }
            mHandler = new Handler(looper);
            mHandler.postDelayed(() -> backgroundTimeoutHandler(), GAME_BACKGROUND_MONITOR_MS);
        }

        void stopBackgroundMonitor() {
            if (mHandler == null) {
                Log.e(TAG, "stopBackgroundMonitor: already stopped");
                return;
            }
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }

        int getUid() {
            return mUid;
        }

        boolean isBackgroundMonitorActive() {
            return mHandler != null;
        }
    }

    @VisibleForTesting final List<GameTracker> mGameTrackingList = new ArrayList<>();

    boolean mCurrentRecordingMode = false;
    Optional<Integer> mBroadcastIdDeactivatedForUnicastTransition = Optional.empty();
    Optional<Boolean> mQueuedInCallValue = Optional.empty();
    boolean mTmapStarted = false;
    private boolean mAwaitingBroadcastCreateResponse = false;
    boolean mIsSourceStreamMonitorModeEnabled = false;
    boolean mIsBroadcastPausedFromOutside = false;
    private final Looper mStateMachinesLooper;

    private static final int LOG_NB_EVENTS = 150;
    private final BluetoothEventLogger mEventLogger =
            new BluetoothEventLogger(LOG_NB_EVENTS, TAG + " event log");

    @VisibleForTesting
    @GuardedBy("mBroadcastCallbacks")
    final RemoteCallbackList<IBluetoothLeBroadcastCallback> mBroadcastCallbacks =
            new RemoteCallbackList<>();

    @VisibleForTesting
    @GuardedBy("mLeAudioCallbacks")
    final RemoteCallbackList<IBluetoothLeAudioCallback> mLeAudioCallbacks =
            new RemoteCallbackList<>();

    public LeAudioService(
            AdapterService adapterService,
            BluetoothStorageManager storage,
            ActiveDeviceManager activeDeviceManager,
            ScanController scanController) {
        this(
                adapterService,
                storage,
                null /* nativeInterface */,
                null /* leAudioBroadcasterNativeInterface */,
                activeDeviceManager,
                scanController,
                null /* looper */,
                null /* activityManager */,
                null /* packageManager */);
    }

    @VisibleForTesting
    LeAudioService(
            AdapterService adapterService,
            BluetoothStorageManager storage,
            LeAudioNativeInterface nativeInterface,
            LeAudioBroadcasterNativeInterface leAudioBroadcasterNativeInterface,
            ActiveDeviceManager activeDeviceManager,
            ScanController scanController,
            Looper looper,
            ActivityManager activityManager,
            PackageManager packageManager) {
        super(BluetoothProfile.LE_AUDIO, adapterService, storage);
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface, () -> new LeAudioNativeInterface(adapterService, this));
        mAudioManager = requireNonNull(obtainSystemService(AudioManager.class));
        mActiveDeviceManager = activeDeviceManager;
        mScanController = requireNonNull(scanController);

        if (looper == null) {
            mHandler = new Handler(Looper.getMainLooper());
            // Start handler thread for state machines
            mStateMachinesThread = new HandlerThread("LeAudioService.StateMachines");
            mStateMachinesThread.start();
            mStateMachinesLooper = mStateMachinesThread.getLooper();
        } else {
            mHandler = new Handler(looper);
            mStateMachinesThread = null;
            mStateMachinesLooper = looper;
        }

        // Initialize Broadcast native interface
        if (Flags.leaudioCentralizeTmap()) {
            mTmapRoleMask = 0;

            if (Config.isProfileSupported(BluetoothProfile.LE_AUDIO_BROADCAST)) {
                Log.i(TAG, "Init Le Audio broadcaster");
                final var broadcastNativeInterface =
                        requireNonNullElseGet(
                                leAudioBroadcasterNativeInterface,
                                () ->
                                        new LeAudioBroadcasterNativeInterface(
                                                getAdapterService(), this));
                broadcastNativeInterface.init();
                mLeAudioBroadcasterNativeInterface = Optional.of(broadcastNativeInterface);
            } else {
                mLeAudioBroadcasterNativeInterface = Optional.empty();
                Log.w(TAG, "Le Audio Broadcasts not supported.");
            }
        } else {
            int mask = 0;
            if (Config.isProfileSupported(BluetoothProfile.LE_CALL_CONTROL)) {
                // Table 3.5 of TMAP v1.0: CCP Server is mandatory for the TMAP CG role.
                mask |= LeAudioTmapGattServer.TMAP_ROLE_FLAG_CG;
            }
            if (Config.isProfileSupported(BluetoothProfile.MCP_SERVER)) {
                // Table 3.5 of TMAP v1.0: MCP Server is mandatory for the TMAP UMS role.
                mask |= LeAudioTmapGattServer.TMAP_ROLE_FLAG_UMS;
            }
            if (Config.isProfileSupported(BluetoothProfile.LE_AUDIO_BROADCAST)) {
                Log.i(TAG, "Init Le Audio broadcaster");
                final var broadcastNativeInterface =
                        requireNonNullElseGet(
                                leAudioBroadcasterNativeInterface,
                                () ->
                                        new LeAudioBroadcasterNativeInterface(
                                                getAdapterService(), this));
                broadcastNativeInterface.init();
                mLeAudioBroadcasterNativeInterface = Optional.of(broadcastNativeInterface);

                mask |= LeAudioTmapGattServer.TMAP_ROLE_FLAG_BMS;
            } else {
                mLeAudioBroadcasterNativeInterface = Optional.empty();
                Log.w(TAG, "Le Audio Broadcasts not supported.");
            }
            if (Config.isProfileSupported(BluetoothProfile.LE_AUDIO_PERIPHERAL)) {
                Log.i(TAG, "Check Le Audio server TMAP role");
                if (SystemProperties.getBoolean(
                        "bluetooth.profile.tmap.call_terminal.enabled", false)) {
                    mask |= LeAudioTmapGattServer.TMAP_ROLE_FLAG_CT;
                }

                if (SystemProperties.getBoolean(
                        "bluetooth.profile.tmap.unicast_media_receiver.enabled", false)) {
                    mask |= LeAudioTmapGattServer.TMAP_ROLE_FLAG_UMR;
                }
            } else {
                Log.i(TAG, "Le Audio Peripheral not supported - initialization skipped");
            }
            mTmapRoleMask = mask;
            mTmapStarted = registerTmap();
        }

        mLeAudioInbandRingtoneSupportedByPlatform =
                BluetoothProperties.isLeAudioInbandRingtoneSupported().orElse(true);

        if (!Flags.admCentralizeActiveDeviceHandling()) {
            mAudioManager.registerAudioDeviceCallback(mAudioManagerAudioDeviceCallback, mHandler);
        }

        // Setup codec config
        mLeAudioCodecConfig = new LeAudioCodecConfig(this);
        mNativeInterface.init(mLeAudioCodecConfig.getCodecConfigOffloading());

        mAudioManager.addOnModeChangedListener(getMainExecutor(), mAudioModeChangeListener);

        mAudioManager.registerAudioRecordingCallback(mAudioRecordingCallback, null);

        if (activityManager != null) {
            mActivityManager = activityManager;
        } else {
            mActivityManager = getAdapterService().getSystemService(ActivityManager.class);
        }

        if (packageManager != null) {
            mPackageManager = packageManager;
        } else {
            mPackageManager = getAdapterService().getPackageManager();
        }
        registerOnUidImportanceListener();
    }

    private boolean isGameApplication(int uid) {
        String[] packageNames = mPackageManager.getPackagesForUid(uid);
        if (packageNames == null) {
            return false;
        }

        for (String packageName : packageNames) {
            if (packageName == null || packageName.isEmpty()) {
                continue;
            }

            try {
                ApplicationInfo info = mPackageManager.getApplicationInfo(packageName, 0);
                boolean isGameApp = info.category == ApplicationInfo.CATEGORY_GAME;
                if (isGameApp) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.d(TAG, "isGameApplication, NameNotFoundException: " + packageName);
            }
        }

        return false;
    }

    private GameTracker getGameTracker(int uid) {
        for (GameTracker g : mGameTrackingList) {
            if (g.getUid() == uid) {
                return g;
            }
        }
        return null;
    }

    private boolean isGameTracking(int uid) {
        return getGameTracker(uid) != null;
    }

    private final ActivityManager.OnUidImportanceListener mUidImportanceListener =
            new ActivityManager.OnUidImportanceListener() {
                @Override
                public void onUidImportance(final int uid, final int importance) {
                    /* If app which is not tracked get was removed or went to background,
                     * it is not interested for us
                     */
                    if ((importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
                                    || importance
                                            == ActivityManager.RunningAppProcessInfo
                                                    .IMPORTANCE_CACHED)
                            && !isGameTracking(uid)) {
                        return;
                    }

                    if (!isGameApplication(uid)) {
                        return;
                    }

                    processGameImportanceChange(uid, importance);
                }
            };

    @VisibleForTesting
    int getTmapRoleMask() {
        return mTmapRoleMask;
    }

    private class LeAudioGroupDescriptor {
        LeAudioGroupDescriptor(int groupId, boolean isInbandRingtoneEnabled) {
            mGroupId = groupId;
            mIsConnected = false;
            mActiveState = ACTIVE_STATE_INACTIVE;
            mAllowedSinkContexts = BluetoothLeAudio.CONTEXTS_ALL;
            mAllowedSourceContexts = BluetoothLeAudio.CONTEXTS_ALL;
            mHasFallbackDeviceWhenGettingInactive = false;
            mDirection = AUDIO_DIRECTION_NONE;
            mCodecStatus = null;
            mLostLeadDeviceWhileStreaming = null;
            mCurrentLeadDevice = null;
            mInbandRingtoneEnabled = isInbandRingtoneEnabled;
            mAvailableContexts = null;
            mInputSelectableConfig = new ArrayList<>();
            mOutputSelectableConfig = new ArrayList<>();
            mInactivatedDueToContextType = false;
            mAutoActiveModeEnabled = true;
        }

        final Integer mGroupId;
        Boolean mIsConnected;
        Boolean mHasFallbackDeviceWhenGettingInactive;
        Integer mDirection;
        BluetoothLeAudioCodecStatus mCodecStatus;
        /* This can be non empty only for the streaming time */
        BluetoothDevice mLostLeadDeviceWhileStreaming;
        BluetoothDevice mCurrentLeadDevice;
        Boolean mInbandRingtoneEnabled;
        Integer mAvailableContexts;
        List<BluetoothLeAudioCodecConfig> mInputSelectableConfig;
        List<BluetoothLeAudioCodecConfig> mOutputSelectableConfig;
        Boolean mInactivatedDueToContextType;
        Boolean mAutoActiveModeEnabled;

        private Integer mActiveState;
        private Integer mAllowedSinkContexts;
        private Integer mAllowedSourceContexts;

        private static String getStateString(int state) {
            return switch (state) {
                case ACTIVE_STATE_ACTIVE -> "active";
                case ACTIVE_STATE_INACTIVE -> "inactive";
                case ACTIVE_STATE_GETTING_ACTIVE -> "getting_active";
                default -> "unknownState [" + state + "]";
            };
        }

        boolean isActive() {
            return mActiveState == ACTIVE_STATE_ACTIVE;
        }

        boolean isInactive() {
            return mActiveState == ACTIVE_STATE_INACTIVE;
        }

        boolean isGettingActive() {
            return mActiveState == ACTIVE_STATE_GETTING_ACTIVE;
        }

        void setActiveState(int state) {
            if ((state != ACTIVE_STATE_ACTIVE)
                    && (state != ACTIVE_STATE_INACTIVE)
                    && (state != ACTIVE_STATE_GETTING_ACTIVE)) {
                mEventLogger.loge(
                        TAG,
                        ("LeAudioGroupDescriptor.setActiveState (groupId: " + mGroupId + "):")
                                + ("Invalid state set: " + getStateString(state)));
                return;
            }

            mEventLogger.logd(
                    TAG,
                    ("LeAudioGroupDescriptor.setActiveState (groupId: " + mGroupId + "): ")
                            + (getStateString(mActiveState) + " -> " + getStateString(state)));
            mActiveState = state;
        }

        String getActiveStateString() {
            return switch (mActiveState) {
                case ACTIVE_STATE_ACTIVE -> "ACTIVE_STATE_ACTIVE";
                case ACTIVE_STATE_INACTIVE -> "ACTIVE_STATE_INACTIVE";
                case ACTIVE_STATE_GETTING_ACTIVE -> "ACTIVE_STATE_GETTING_ACTIVE";
                default -> "INVALID";
            };
        }

        void updateAllowedContexts(Integer allowedSinkContexts, Integer allowedSourceContexts) {
            Log.d(
                    TAG,
                    "LeAudioGroupDescriptor.mAllowedSinkContexts (groupId: "
                            + mGroupId
                            + "): "
                            + mAllowedSinkContexts
                            + " -> "
                            + allowedSinkContexts
                            + ", LeAudioGroupDescriptor.mAllowedSourceContexts: "
                            + mAllowedSourceContexts
                            + " -> "
                            + allowedSourceContexts);

            mAllowedSinkContexts = allowedSinkContexts;
            mAllowedSourceContexts = allowedSourceContexts;
        }

        Integer getAllowedSinkContexts() {
            return mAllowedSinkContexts;
        }

        Integer getAllowedSourceContexts() {
            return mAllowedSourceContexts;
        }

        boolean areAllowedContextsModified() {
            return (mAllowedSinkContexts != BluetoothLeAudio.CONTEXTS_ALL)
                    || (mAllowedSourceContexts != BluetoothLeAudio.CONTEXTS_ALL);
        }
    }

    private static class LeAudioDeviceDescriptor {
        LeAudioDeviceDescriptor(boolean isInbandRingtoneEnabled) {
            mAclConnected = false;
            mStateMachine = null;
            mGroupId = LE_AUDIO_GROUP_ID_INVALID;
            mSinkAudioLocation = BluetoothLeAudio.AUDIO_LOCATION_INVALID;
            mDirection = AUDIO_DIRECTION_NONE;
            mDevInbandRingtoneEnabled = isInbandRingtoneEnabled;
        }

        boolean mAclConnected;
        LeAudioStateMachine mStateMachine;
        Integer mGroupId;
        Integer mSinkAudioLocation;
        Integer mDirection;
        Boolean mDevInbandRingtoneEnabled;
    }

    private static class LeAudioBroadcastDescriptor {
        LeAudioBroadcastDescriptor() {
            mState = LeAudioStackEvent.BROADCAST_STATE_STOPPED;
            mMetadata = null;
            mRequestedForDetails = false;
        }

        Integer mState;
        BluetoothLeBroadcastMetadata mMetadata;
        Boolean mRequestedForDetails;
    }

    private static class LeAudioBroadcastSessionStats {
        private final int[] mAudioQuality;
        private final long mSessionRequestTime;
        private long mSessionCreatedTime;
        private long mSessionStreamingTime;
        private int mSessionGroupSize;
        private int mSessionStatus;

        LeAudioBroadcastSessionStats(
                BluetoothLeBroadcastSettings broadcastSettings, long startTime) {
            this.mAudioQuality =
                    broadcastSettings.getSubgroupSettings().stream()
                            .mapToInt(s -> convertToStatsAudioQuality(s.getPreferredQuality()))
                            .toArray();
            this.mSessionRequestTime = startTime;
            this.mSessionCreatedTime = 0;
            this.mSessionStreamingTime = 0;
            this.mSessionGroupSize = 0;
            this.mSessionStatus =
                    BluetoothStatsLog
                            .BROADCAST_AUDIO_SESSION_REPORTED__SESSION_SETUP_STATUS__SETUP_STATUS_REQUESTED;
        }

        void updateSessionCreatedTime(long createdTime) {
            if (mSessionCreatedTime == 0) {
                mSessionCreatedTime = createdTime;
            }
        }

        void updateSessionStreamingTime(long streamingTime) {
            if (mSessionStreamingTime == 0) {
                // Only record the 1st STREAMING EVENT
                mSessionStreamingTime = streamingTime;
            }
        }

        void updateGroupSize(int groupSize) {
            mSessionGroupSize = groupSize;
        }

        void updateSessionStatus(int status) {
            if (mSessionStatus
                    != BluetoothStatsLog
                            .BROADCAST_AUDIO_SESSION_REPORTED__SESSION_SETUP_STATUS__SETUP_STATUS_STREAMING) {
                Log.d(
                        TAG,
                        "logBroadcastSessionMetrics: updating from state: "
                                + mSessionStatus
                                + " to "
                                + status);
                mSessionStatus = status;
            }
        }

        void logBroadcastSessionMetrics(int broadcastId, long sessionStopTime) {
            long sessionDurationMs =
                    (mSessionCreatedTime > 0) ? (sessionStopTime - mSessionCreatedTime) : 0;
            long latencySessionConfiguredMs =
                    (mSessionCreatedTime > 0) ? (mSessionCreatedTime - mSessionRequestTime) : 0;
            long latencySessionStreamingMs =
                    (mSessionCreatedTime > 0 && mSessionStreamingTime > 0)
                            ? (mSessionStreamingTime - mSessionCreatedTime)
                            : 0;

            Log.d(
                    TAG,
                    "logBroadcastSessionMetrics: broadcastId: "
                            + broadcastId
                            + ", audioQuality: "
                            + Arrays.toString(mAudioQuality)
                            + ", sessionGroupSize: "
                            + mSessionGroupSize
                            + ", sessionDurationMs: "
                            + sessionDurationMs
                            + ", latencySessionConfiguredMs: "
                            + latencySessionConfiguredMs
                            + ", latencySessionStreamingMs: "
                            + latencySessionStreamingMs
                            + ", sessionStatus: "
                            + mSessionStatus);

            MetricsLogger.getInstance()
                    .logLeAudioBroadcastAudioSession(
                            broadcastId,
                            mAudioQuality,
                            mSessionGroupSize,
                            sessionDurationMs,
                            latencySessionConfiguredMs,
                            latencySessionStreamingMs,
                            mSessionStatus);
        }

        private static int convertToStatsAudioQuality(int audioQuality) {
            return switch (audioQuality) {
                case BluetoothLeBroadcastSubgroupSettings.QUALITY_STANDARD ->
                        BROADCAST_AUDIO_SESSION_REPORTED__AUDIO_QUALITY__QUALITY_STANDARD;
                case BluetoothLeBroadcastSubgroupSettings.QUALITY_HIGH ->
                        BROADCAST_AUDIO_SESSION_REPORTED__AUDIO_QUALITY__QUALITY_HIGH;
                default -> BROADCAST_AUDIO_SESSION_REPORTED__AUDIO_QUALITY__QUALITY_UNKNOWN;
            };
        }
    }

    List<BluetoothLeAudioCodecConfig> mInputLocalCodecCapabilities = new ArrayList<>();
    List<BluetoothLeAudioCodecConfig> mOutputLocalCodecCapabilities = new ArrayList<>();

    Set<BluetoothDevice> mBroadcastReceivers = new HashSet<>();

    private final Map<Integer, Pair<BluetoothLeAudioCodecConfig, BluetoothLeAudioCodecConfig>>
            mActiveGroupCodecPreferences = new LinkedHashMap<>();

    @GuardedBy("mGroupWriteLock")
    private final Map<Integer, LeAudioGroupDescriptor> mGroupDescriptors = new LinkedHashMap<>();

    @GuardedBy("mGroupReadLock")
    private final Map<Integer, LeAudioGroupDescriptor> mGroupDescriptorsView =
            Collections.unmodifiableMap(mGroupDescriptors);

    private final Map<BluetoothDevice, LeAudioDeviceDescriptor> mDeviceDescriptors =
            new LinkedHashMap<>();
    private final Map<Integer, LeAudioBroadcastDescriptor> mBroadcastDescriptors =
            new LinkedHashMap<>();
    private final Map<Integer, LeAudioBroadcastSessionStats> mBroadcastSessionStats =
            new LinkedHashMap<>();

    private final Handler mHandler;
    private final AudioManagerAudioDeviceCallback mAudioManagerAudioDeviceCallback =
            new AudioManagerAudioDeviceCallback();
    private final AudioModeChangeListener mAudioModeChangeListener = new AudioModeChangeListener();

    @Override
    protected IProfileServiceBinder initBinder() {
        return new LeAudioServiceBinder(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileBapUnicastClientEnabled().orElse(false);
    }

    private boolean registerTmap() {
        if (Flags.leaudioCentralizeTmap()) {
            throw new IllegalStateException(
                    "Decentralized TMAP GATT server start sequence was deprecated");
        }

        if (mTmapGattServer != null) {
            throw new IllegalStateException("TMAP GATT server started before start() is called");
        }

        try {
            mTmapGattServer =
                    LeAudioObjectsFactory.getInstance().getTmapGattServer(getAdapterService());
        } catch (IllegalStateException e) {
            Log.e(TAG, "Fail to start TmapGattServer", e);
            return false;
        }

        return true;
    }

    void handleRecordingModeChange(boolean isRecording) {
        if (Flags.leaudioFixStreamConfirmDatapathRace()) {
            if (isRecording == mCurrentRecordingMode) {
                return;
            }
        }

        Log.d(TAG, "Recording mode changed: " + mCurrentRecordingMode + " -> " + isRecording);
        boolean previousRecordingMode = mCurrentRecordingMode;

        mCurrentRecordingMode = isRecording;

        if (isRecording) {
            if (!areBroadcastsAllStopped()) {
                /* Request activation of unicast group */
                pauseBroadcastDueToStartingUnicast();
            }
        } else {
            /* Remove broadcast if during handover active LE Audio device disappears
             * (switch to primary device or non LE Audio device)
             */
            if (isBroadcastReadyToBeReActivated()
                    && previousRecordingMode
                    && (getActiveGroupId() == LE_AUDIO_GROUP_ID_INVALID)) {
                stopBroadcast(mBroadcastIdDeactivatedForUnicastTransition.get());
                mBroadcastIdDeactivatedForUnicastTransition = Optional.empty();
                return;
            }

            if (mBroadcastIdDeactivatedForUnicastTransition.isPresent()) {
                deactivateUnicastDueToActivatingBroadcast();
            }
        }
    }

    private final AudioManager.AudioRecordingCallback mAudioRecordingCallback =
            new AudioManager.AudioRecordingCallback() {
                /* Audio Framework uses this callback to notify listeners of recording configuration
                 * changes. When a recording scenario starts or its configuration changes, this
                 * callback provides the updated configuration.
                 * When the scenario ends, an empty configs list indicates that recording has
                 * stopped.
                 */
                @Override
                public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    handleRecordingModeChange(configs.size() != 0);
                }
            };

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        mQueuedInCallValue = Optional.empty();
        mAudioManager.removeOnModeChangedListener(mAudioModeChangeListener);

        mAudioManager.unregisterAudioRecordingCallback(mAudioRecordingCallback);

        mBroadcastReceivers.clear();
        mCreateBroadcastQueue.clear();
        mAwaitingBroadcastCreateResponse = false;
        mIsSourceStreamMonitorModeEnabled = false;
        mIsBroadcastPausedFromOutside = false;

        clearCreateBroadcastTimeoutCallback();

        mGameTrackingList.clear();
        unregisterOnUidImportanceListener();

        removeActiveDevice(false);

        if (!Flags.leaudioCentralizeTmap()) {
            if (mTmapGattServer == null) {
                Log.w(TAG, "TMAP GATT server should never be null before stop() is called");
            } else {
                mTmapGattServer.close();
                mTmapGattServer = null;
                mTmapStarted = false;
            }
        }

        mScanCallback.stopBackgroundScan();

        // Don't wait for async call with INACTIVE group status, clean active
        // device for active group.
        mGroupReadLock.lock();
        try {
            try {
                for (Map.Entry<Integer, LeAudioGroupDescriptor> entry :
                        mGroupDescriptorsView.entrySet()) {
                    LeAudioGroupDescriptor descriptor = entry.getValue();
                    Integer groupId = entry.getKey();
                    if (descriptor.isActive()) {
                        descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
                        updateActiveDevices(
                                groupId,
                                descriptor.mDirection,
                                AUDIO_DIRECTION_NONE,
                                false,
                                false,
                                false);
                        break;
                    }
                }

                // Destroy state machines and stop handler thread
                for (LeAudioDeviceDescriptor descriptor : mDeviceDescriptors.values()) {
                    LeAudioStateMachine sm = descriptor.mStateMachine;
                    if (sm == null) {
                        continue;
                    }
                    if (Flags.leaudioIntentBroadcastInStateMachineCleanup()) {
                        sm.doQuit();
                    } else {
                        sm.quit();
                    }
                    sm.cleanup();
                }
            } finally {
                // Upgrade to write lock
                mGroupReadLock.unlock();
                mGroupWriteLock.lock();
            }
            mDeviceDescriptors.clear();
            mGroupDescriptors.clear();
        } finally {
            mGroupWriteLock.unlock();
        }

        // Cleanup native interfaces
        mNativeInterface.cleanup();
        mLeAudioNativeIsInitialized = false;
        mBluetoothEnabled = false;
        mHfpHandoverDevice = null;
        mLeAudioDeviceInactivatedForHfpHandover = null;

        mActiveAudioOutDevice = null;
        mActiveAudioInDevice = null;
        mExposedActiveDevice = null;

        // Unregister broadcast callbacks
        synchronized (mBroadcastCallbacks) {
            mBroadcastCallbacks.kill();
        }

        synchronized (mLeAudioCallbacks) {
            mLeAudioCallbacks.kill();
        }

        mBroadcastDescriptors.clear();
        logAllBroadcastSessionStatsAndCleanup();

        mLeAudioBroadcasterNativeInterface.ifPresent(LeAudioBroadcasterNativeInterface::cleanup);

        if (mStateMachinesThread != null) {
            try {
                mStateMachinesThread.quitSafely();
                mStateMachinesThread.join(SM_THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                // Do not rethrow as we are shutting down anyway
            }
        }

        mHandler.removeCallbacksAndMessages(null);
        if (!Flags.admCentralizeActiveDeviceHandling()) {
            mAudioManager.unregisterAudioDeviceCallback(mAudioManagerAudioDeviceCallback);
        }
    }

    @VisibleForTesting
    int getAudioDeviceGroupVolume(int groupId) {
        final var volumeControl = getAdapterService().getVolumeControlService();
        if (volumeControl.isEmpty()) {
            return IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME;
        }
        return volumeControl.get().getAudioDeviceGroupVolume(groupId);
    }

    LeAudioDeviceDescriptor createDeviceDescriptor(
            BluetoothDevice device, boolean isInbandRingtoneEnabled) {
        LeAudioDeviceDescriptor descriptor = mDeviceDescriptors.get(device);
        if (descriptor == null) {
            mDeviceDescriptors.put(device, new LeAudioDeviceDescriptor(isInbandRingtoneEnabled));
            descriptor = mDeviceDescriptors.get(device);
            Log.d(TAG, "Created descriptor for device: " + device);
        } else {
            Log.w(TAG, "Device: " + device + ", already exists");
        }

        return descriptor;
    }

    private void setEnabledState(BluetoothDevice device, boolean enabled) {
        Log.d(TAG, "setEnabledState: address:" + device + " enabled: " + enabled);
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "setEnabledState, mLeAudioNativeIsInitialized is not initialized");
            return;
        }
        mNativeInterface.setEnableState(device, enabled);
    }

    // During a personal audio sharing session, the owner's device is assumed to be the first
    // one that connected. Guests' devices connect later just to listen to the broadcast.
    //
    // When the session ends, we must fall back to a unicast group. For privacy reasons (calls..),
    // we select the owner's group, which corresponds to the least recently connected device.
    private void setDefaultBroadcastToUnicastFallbackGroup() {
        List<BluetoothDevice> connectedDevices = getConnectedDevices();
        List<BluetoothDevice> availableDevices;
        if (Flags.leaudioFallbackGroupSelection()) {
            availableDevices =
                    connectedDevices.stream()
                            .filter(device -> mBroadcastReceivers.contains(device))
                            .toList();
        } else {
            availableDevices = connectedDevices;
        }

        BluetoothDevice device =
                getStorage().getLeastRecentlyConnectedDeviceInList(availableDevices);
        LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
        int targetGroupId = descriptor != null ? descriptor.mGroupId : LE_AUDIO_GROUP_ID_INVALID;
        updateFallbackUnicastGroupIdForBroadcast(targetGroupId);
    }

    @Override
    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);

        if (!okToConnect(device)) {
            return false;
        }
        final ParcelUuid[] featureUuids = getAdapterService().getRemoteUuids(device);
        if (!Util.arrayContains(featureUuids, BluetoothUuid.LE_AUDIO)) {
            Log.e(TAG, "Cannot connect to " + device + " : Remote does not have LE_AUDIO UUID");
            return false;
        }

        LeAudioStateMachine sm = null;

        mGroupWriteLock.lock();
        try {
            boolean isInbandRingtoneEnabled = false;
            int groupId = getGroupId(device);
            if (groupId != LE_AUDIO_GROUP_ID_INVALID) {
                isInbandRingtoneEnabled = getGroupDescriptor(groupId).mInbandRingtoneEnabled;
            }

            if (createDeviceDescriptor(device, isInbandRingtoneEnabled) == null) {
                return false;
            }

            sm = getOrCreateStateMachine(device);
            if (sm == null) {
                Log.e(TAG, "Ignored connect request for " + device + " : no state machine");
                return false;
            }

        } finally {
            mGroupWriteLock.unlock();
        }

        sm.sendMessage(LeAudioStateMachine.CONNECT);
        return true;
    }

    /**
     * Disconnects LE Audio for the remote bluetooth device
     *
     * @param device is the device with which we would like to disconnect LE Audio
     * @return true if profile disconnected, false if device not connected over LE Audio
     */
    @Override
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);

        LeAudioStateMachine sm = null;

        mGroupReadLock.lock();
        try {
            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                Log.e(TAG, "disconnect: No valid descriptor for device: " + device);
                return false;
            }
            sm = descriptor.mStateMachine;
        } finally {
            mGroupReadLock.unlock();
        }

        if (sm == null) {
            Log.e(TAG, "Ignored disconnect request for " + device + " : no state machine");
            return false;
        }

        sm.sendMessage(LeAudioStateMachine.DISCONNECT);

        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        mGroupReadLock.lock();
        try {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (LeAudioDeviceDescriptor descriptor : mDeviceDescriptors.values()) {
                LeAudioStateMachine sm = descriptor.mStateMachine;
                if (sm != null && sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            return devices;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    BluetoothDevice getConnectedGroupLeadDevice(int groupId) {
        if (getGroupId(mActiveAudioOutDevice) == groupId) {
            return mActiveAudioOutDevice;
        }

        return getLeadDeviceForTheGroup(groupId);
    }

    boolean isGroupConnectingOrConnected(int groupId) {
        Log.d(TAG, "isGroupConnectingOrConnected: " + groupId);
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                Log.e(TAG, "Group " + groupId + " does not exist");
                return false;
            }

            // If group is not connected, check if any device from the group is connecting or
            // connected
            for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> deviceEntry :
                    mDeviceDescriptors.entrySet()) {
                LeAudioDeviceDescriptor deviceDescriptor = deviceEntry.getValue();
                if (deviceDescriptor.mGroupId != groupId) {
                    continue;
                }

                if (deviceDescriptor.mStateMachine == null) {
                    /* Lack of state machine means device is not connecting. */
                    continue;
                }

                int connectionState = deviceDescriptor.mStateMachine.getConnectionState();
                if (connectionState == STATE_CONNECTING || connectionState == STATE_CONNECTED) {
                    Log.d(
                            TAG,
                            "isGroupConnectingOrConnected: group: "
                                    + groupId
                                    + " is connecting/connected. Device:"
                                    + deviceEntry.getKey());
                    return true;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return false;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final var bondedDevices = getAdapterService().getBondedDevices();
        mGroupReadLock.lock();
        try {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = getAdapterService().getRemoteUuids(device);
                if (!Util.arrayContains(featureUuids, BluetoothUuid.LE_AUDIO)) {
                    continue;
                }
                int connectionState = STATE_DISCONNECTED;
                LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
                if (descriptor == null) {
                    Log.e(
                            TAG,
                            "getDevicesMatchingConnectionStates: No valid descriptor for device: "
                                    + device);
                    continue;
                }

                LeAudioStateMachine sm = descriptor.mStateMachine;
                if (sm != null) {
                    connectionState = sm.getConnectionState();
                }
                for (int state : states) {
                    if (connectionState == state) {
                        devices.add(device);
                        break;
                    }
                }
            }
            return devices;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Get the list of devices that have state machines.
     *
     * @return the list of devices that have state machines
     */
    @VisibleForTesting
    List<BluetoothDevice> getDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        mGroupReadLock.lock();
        try {
            for (LeAudioDeviceDescriptor descriptor : mDeviceDescriptors.values()) {
                if (descriptor.mStateMachine != null) {
                    devices.add(descriptor.mStateMachine.getDevice());
                }
            }
            return devices;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Get the current connection state of the profile
     *
     * @param device is the remote bluetooth device
     * @return {@link BluetoothProfile#STATE_DISCONNECTED} if this profile is disconnected, {@link
     *     BluetoothProfile#STATE_CONNECTING} if this profile is being connected, {@link
     *     BluetoothProfile#STATE_CONNECTED} if this profile is connected, or {@link
     *     BluetoothProfile#STATE_DISCONNECTING} if this profile is being disconnected
     */
    @Override
    public int getConnectionState(BluetoothDevice device) {
        mGroupReadLock.lock();
        try {
            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                return STATE_DISCONNECTED;
            }

            LeAudioStateMachine sm = descriptor.mStateMachine;
            if (sm == null) {
                return STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Add device to the given group.
     *
     * @param groupId group ID the device is being added to
     * @param device the active device
     * @return true on success, otherwise false
     */
    boolean groupAddNode(int groupId, BluetoothDevice device) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return false;
        }
        return mNativeInterface.groupAddNode(groupId, device);
    }

    /**
     * Remove device from a given group.
     *
     * @param groupId group ID the device is being removed from
     * @param device the active device
     * @return true on success, otherwise false
     */
    boolean groupRemoveNode(int groupId, BluetoothDevice device) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return false;
        }
        return mNativeInterface.groupRemoveNode(groupId, device);
    }

    /**
     * Checks if given group exists.
     *
     * @param groupId group Id to verify
     * @return true given group exists, otherwise false
     */
    public boolean isValidDeviceGroup(int groupId) {
        mGroupReadLock.lock();
        try {
            return groupId != LE_AUDIO_GROUP_ID_INVALID
                    && mGroupDescriptorsView.containsKey(groupId);
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Get all the devices within a given group.
     *
     * @param groupId group id to get devices
     * @return all devices within a given group or empty list
     */
    public List<BluetoothDevice> getGroupDevices(int groupId) {
        List<BluetoothDevice> result = new ArrayList<>();

        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return result;
        }

        mGroupReadLock.lock();
        try {
            for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> entry :
                    mDeviceDescriptors.entrySet()) {
                if (entry.getValue().mGroupId == groupId) {
                    result.add(entry.getKey());
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return result;
    }

    /**
     * Get all the devices within a group for given device.
     *
     * @param device the device for which we want to get all devices in its group
     * @return all devices within a given group or empty list
     */
    public List<BluetoothDevice> getGroupDevices(BluetoothDevice device) {
        int groupId = getGroupId(device);
        return (getGroupDevices(groupId));
    }

    /** Get the active device group id */
    public Integer getActiveGroupId() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> entry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor descriptor = entry.getValue();
                if (descriptor.isActive()) {
                    return entry.getKey();
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return LE_AUDIO_GROUP_ID_INVALID;
    }

    private int canBroadcastBeCreated(BluetoothLeBroadcastSettings broadcastSettings) {
        if (mBroadcastDescriptors.size() >= getMaximumNumberOfBroadcasts()) {
            Log.w(
                    TAG,
                    "createBroadcast reached maximum allowed broadcasts number: "
                            + getMaximumNumberOfBroadcasts());
            return BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES;
        }

        byte[] broadcastCode = broadcastSettings.getBroadcastCode();
        if (broadcastCode != null && ((broadcastCode.length > 16) || (broadcastCode.length < 4))) {
            Log.e(TAG, "Invalid broadcast code length. Should be from 4 to 16 octets long.");
            return BluetoothStatusCodes.ERROR_LE_BROADCAST_INVALID_CODE;
        }

        List<BluetoothLeBroadcastSubgroupSettings> settingsList =
                broadcastSettings.getSubgroupSettings();
        if (settingsList == null || settingsList.size() < 1) {
            Log.d(TAG, "subgroup settings is not valid value");
            return BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    /**
     * Creates LeAudio Broadcast instance with BluetoothLeBroadcastSettings.
     *
     * @param broadcastSettings broadcast settings for this broadcast source
     */
    public void createBroadcast(BluetoothLeBroadcastSettings broadcastSettings) {
        if (mLeAudioBroadcasterNativeInterface.isEmpty()) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        int canBroadcastBeCreatedReturnCode = canBroadcastBeCreated(broadcastSettings);
        if (canBroadcastBeCreatedReturnCode != BluetoothStatusCodes.SUCCESS) {
            mHandler.post(() -> notifyBroadcastStartFailed(canBroadcastBeCreatedReturnCode));
            return;
        }

        if (mAwaitingBroadcastCreateResponse) {
            mCreateBroadcastQueue.add(broadcastSettings);
            Log.i(TAG, "Broadcast creation queued due to waiting for a previous request response.");
            return;
        }

        mBroadcastSessionStats.put(
                INVALID_BROADCAST_ID,
                new LeAudioBroadcastSessionStats(broadcastSettings, SystemClock.elapsedRealtime()));

        BluetoothLeAudioContentMetadata publicMetadata =
                broadcastSettings.getPublicBroadcastMetadata();

        byte[] broadcastCode = broadcastSettings.getBroadcastCode();
        Log.i(
                TAG,
                "createBroadcast: isEncrypted="
                        + (((broadcastCode != null) && (broadcastCode.length != 0))
                                ? "true"
                                : "false"));

        mAwaitingBroadcastCreateResponse = true;
        mCreateBroadcastQueue.add(broadcastSettings);

        /* Start timeout to recover from stuck/error create Broadcast operation */
        if (mCreateBroadcastTimeoutEvent != null) {
            Log.w(TAG, "CreateBroadcastTimeoutEvent already scheduled");
        } else {
            mCreateBroadcastTimeoutEvent = new CreateBroadcastTimeoutEvent();
            mHandler.postDelayed(mCreateBroadcastTimeoutEvent, CREATE_BROADCAST_TIMEOUT_MS);
        }

        mLeAudioBroadcasterNativeInterface
                .get()
                .createBroadcast(
                        broadcastSettings.isPublicBroadcast(),
                        broadcastSettings.getBroadcastName(),
                        broadcastCode,
                        publicMetadata == null ? null : publicMetadata.getRawMetadata(),
                        getBroadcastAudioQualityPerSinkCapabilities(
                                broadcastSettings.getSubgroupSettings()),
                        broadcastSettings.getSubgroupSettings().stream()
                                .map(s -> s.getContentMetadata().getRawMetadata())
                                .toArray(byte[][]::new));
    }

    private int[] getBroadcastAudioQualityPerSinkCapabilities(
            List<BluetoothLeBroadcastSubgroupSettings> settingsList) {
        int[] preferredQualityArray =
                settingsList.stream().mapToInt(s -> s.getPreferredQuality()).toArray();

        final var bassClient = getAdapterService().getBassClientService();
        if (bassClient.isEmpty()) {
            return preferredQualityArray;
        }

        for (BluetoothDevice sink : bassClient.get().getConnectedDevices()) {
            if (!isCapableToReceiveHighQualityBroadcastAudio(sink).orElse(true)) {
                // If any sink device does not support high quality audio config,
                // set all subgroup audio quality to standard quality for now before multi codec
                // config support is ready
                Log.i(
                        TAG,
                        "Sink device doesn't support HIGH broadcast audio quality, use STANDARD"
                                + " quality");
                Arrays.fill(
                        preferredQualityArray,
                        BluetoothLeBroadcastSubgroupSettings.QUALITY_STANDARD);
                break;
            }
        }
        return preferredQualityArray;
    }

    /**
     * Check if broadcast sink supports high quality configuration
     *
     * @param sink device to check
     * @return empty if unknown, true if supports, false otherwise
     */
    public Optional<Boolean> isCapableToReceiveHighQualityBroadcastAudio(BluetoothDevice sink) {
        int groupId = getGroupId(sink);
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return Optional.empty();
        }

        BluetoothLeAudioCodecStatus cs = getCodecStatus(groupId);
        if (cs == null) {
            return Optional.empty();
        }

        return Optional.of(cs.isOutputCodecConfigSelectable(BROADCAST_HIGH_QUALITY_CONFIG));
    }

    /**
     * Start LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    private void startBroadcast(int broadcastId) {
        if (mLeAudioBroadcasterNativeInterface.isEmpty()) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        Log.d(TAG, "startBroadcast");
        mLeAudioBroadcasterNativeInterface.get().startBroadcast(broadcastId);
    }

    /**
     * Updates LeAudio broadcast instance metadata.
     *
     * @param broadcastId broadcast instance identifier
     * @param broadcastSettings broadcast settings for this broadcast source
     */
    public void updateBroadcast(int broadcastId, BluetoothLeBroadcastSettings broadcastSettings) {
        if (mLeAudioBroadcasterNativeInterface.isEmpty()) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            mHandler.post(
                    () ->
                            notifyBroadcastUpdateFailed(
                                    broadcastId,
                                    BluetoothStatusCodes.ERROR_LE_BROADCAST_INVALID_BROADCAST_ID));
            Log.e(TAG, "updateBroadcast: No valid descriptor for broadcastId: " + broadcastId);
            return;
        }

        List<BluetoothLeBroadcastSubgroupSettings> settingsList =
                broadcastSettings.getSubgroupSettings();
        if (settingsList == null || settingsList.size() < 1) {
            Log.d(TAG, "subgroup settings is not valid value");
            return;
        }

        BluetoothLeAudioContentMetadata publicMetadata =
                broadcastSettings.getPublicBroadcastMetadata();

        Log.d(TAG, "updateBroadcast");
        mLeAudioBroadcasterNativeInterface
                .get()
                .updateMetadata(
                        broadcastId,
                        broadcastSettings.getBroadcastName(),
                        publicMetadata == null ? null : publicMetadata.getRawMetadata(),
                        settingsList.stream()
                                .map(s -> s.getContentMetadata().getRawMetadata())
                                .toArray(byte[][]::new));
    }

    /**
     * Pause LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    private void pauseBroadcast(Integer broadcastId) {
        if (mLeAudioBroadcasterNativeInterface.isEmpty()) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            Log.e(TAG, "pauseBroadcast: No valid descriptor for broadcastId: " + broadcastId);
            return;
        }

        if (isPlaying(broadcastId)) {
            Log.d(TAG, "pauseBroadcast");
            mIsBroadcastPausedFromOutside = true;
            mLeAudioBroadcasterNativeInterface.get().pauseBroadcast(broadcastId);
        } else if (isPaused(broadcastId)) {
            transitionFromBroadcastToUnicast();
        } else {
            Log.d(TAG, "pauseBroadcast: Broadcast is stopped, skip pause request");
        }
    }

    /**
     * Stop LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    public void stopBroadcast(Integer broadcastId) {
        if (mLeAudioBroadcasterNativeInterface.isEmpty()) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            mHandler.post(
                    () ->
                            notifyOnBroadcastStopFailed(
                                    BluetoothStatusCodes.ERROR_LE_BROADCAST_INVALID_BROADCAST_ID));
            Log.e(TAG, "stopBroadcast: No valid descriptor for broadcastId: " + broadcastId);
            return;
        }

        // CLEAR action does not require a sink device, as it applies to all sink devices
        // associated with the BIG.
        setBigChannelMapClassification(
                SetBigChannelMapClassificationAction.CLEAR.getValue(), null, broadcastId);

        Log.d(TAG, "stopBroadcast");

        // log group size before stop
        LeAudioBroadcastSessionStats sessionStats = mBroadcastSessionStats.get(broadcastId);
        final var bassClient = getAdapterService().getBassClientService();
        if (bassClient.isPresent() && sessionStats != null) {
            sessionStats.updateGroupSize(bassClient.get().getSyncedBroadcastSinks().size());
        }

        mLeAudioBroadcasterNativeInterface.get().stopBroadcast(broadcastId);
    }

    /**
     * Destroy LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    private void destroyBroadcast(int broadcastId) {
        if (mLeAudioBroadcasterNativeInterface.isEmpty()) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            mHandler.post(
                    () ->
                            notifyOnBroadcastStopFailed(
                                    BluetoothStatusCodes.ERROR_LE_BROADCAST_INVALID_BROADCAST_ID));
            Log.e(TAG, "destroyBroadcast: No valid descriptor for broadcastId: " + broadcastId);
            return;
        }

        Log.d(TAG, "destroyBroadcast");

        if (mBroadcastIdDeactivatedForUnicastTransition.isPresent()
                && mBroadcastIdDeactivatedForUnicastTransition.get().equals(broadcastId)) {
            mBroadcastIdDeactivatedForUnicastTransition = Optional.empty();
        }
        mLeAudioBroadcasterNativeInterface.get().destroyBroadcast(broadcastId);
    }

    /**
     * Sends parameters to native stack to set the BIG Channel Map by channel classification of sink
     * device.
     *
     * <p>This public method calls the native interface to bridge to JNI.
     *
     * @param action The action for set BIG channel map classification.
     * @param sink The Bluetooth device of the sink device.
     * @param broadcastId The Broadcast ID.
     */
    public void setBigChannelMapClassification(int action, BluetoothDevice sink, int broadcastId) {
        if (!Flags.leaudioBroadcastSourceChannelMapClassification()) {
            return;
        }

        if (mLeAudioBroadcasterNativeInterface.isEmpty()) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            Log.e(TAG, "No valid descriptor for broadcastId: " + broadcastId);
            return;
        }

        // Call the Native Interface
        mLeAudioBroadcasterNativeInterface
                .get()
                .setBigChannelMapClassification(action, sink, broadcastId);
    }

    /**
     * Checks if Broadcast instance is playing.
     *
     * @param broadcastId broadcast instance identifier
     * @return true if if broadcast is playing, false otherwise
     */
    public boolean isPlaying(int broadcastId) {
        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            Log.e(TAG, "isPlaying: No valid descriptor for broadcastId: " + broadcastId);
            return false;
        }

        return (descriptor.mState.equals(LeAudioStackEvent.BROADCAST_STATE_STREAMING));
    }

    /**
     * Checks if Broadcast instance is paused.
     *
     * @param broadcastId broadcast instance identifier
     * @return true if if broadcast is paused, false otherwise
     */
    public boolean isPaused(int broadcastId) {
        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            Log.e(TAG, "isPaused: No valid descriptor for broadcastId: " + broadcastId);
            return false;
        }

        return (descriptor.mState.equals(LeAudioStackEvent.BROADCAST_STATE_PAUSED));
    }

    /**
     * Get broadcast metadata for passed broadcastId
     *
     * @param broadcastId broadcast instance identifier
     * @return metadata if available, null otherwise
     */
    public BluetoothLeBroadcastMetadata getBroadcastMetadata(int broadcastId) {
        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        return descriptor == null ? null : descriptor.mMetadata;
    }

    /**
     * Get all broadcast metadata.
     *
     * @return list of all know Broadcast metadata
     */
    public List<BluetoothLeBroadcastMetadata> getAllBroadcastMetadata() {
        return mBroadcastDescriptors.values().stream()
                .map(s -> s.mMetadata)
                .collect(Collectors.toList());
    }

    /**
     * Check if broadcast is active
     *
     * @return true if there is active broadcast, false otherwise
     */
    public boolean isBroadcastActive() {
        return !mBroadcastDescriptors.isEmpty();
    }

    /**
     * Check if broadcast is active or ready to be activated
     *
     * @return true if there is active broadcast or ready to be activated, false otherwise
     */
    public boolean isBroadcastStarted() {
        return isBroadcastActive() || isBroadcastReadyToBeActivated();
    }

    /**
     * Get the maximum number of supported simultaneous broadcasts.
     *
     * @return number of supported simultaneous broadcasts
     */
    public int getMaximumNumberOfBroadcasts() {
        /* TODO: This is currently fixed to 1 */
        return 1;
    }

    /**
     * Get the maximum number of supported streams per broadcast.
     *
     * @return number of supported streams per broadcast
     */
    public int getMaximumStreamsPerBroadcast() {
        /* TODO: This is currently fixed to 1 */
        return 1;
    }

    /**
     * Get the maximum number of supported subgroups per broadcast.
     *
     * @return number of supported subgroups per broadcast
     */
    public int getMaximumSubgroupsPerBroadcast() {
        /* TODO: This is currently fixed to 1 */
        return 1;
    }

    /** Active Broadcast Assistant notification handler */
    public void activeBroadcastAssistantNotification(boolean active) {
        if (getAdapterService().getBassClientService().isEmpty()) {
            Log.w(TAG, "Ignore active Broadcast Assistant notification");
            return;
        }

        if (active) {
            mIsSourceStreamMonitorModeEnabled = true;
            mNativeInterface.setUnicastMonitorMode(LeAudioStackEvent.DIRECTION_SOURCE, true);
        } else {
            if (mIsSourceStreamMonitorModeEnabled) {
                mNativeInterface.setUnicastMonitorMode(LeAudioStackEvent.DIRECTION_SOURCE, false);
            }

            mIsSourceStreamMonitorModeEnabled = false;
        }
    }

    /** Return true if device is primary - is active or was active before switch to broadcast */
    public boolean isPrimaryDevice(BluetoothDevice device) {
        LeAudioDeviceDescriptor descriptor = mDeviceDescriptors.get(device);
        if (descriptor == null) {
            return false;
        }

        return (descriptor.mGroupId == mBroadcastToUnicastFallbackGroup)
                || device.equals(mActiveAudioInDevice)
                || device.equals(mActiveAudioOutDevice);
    }

    /**
     * If leaudioFallbackGroupSelection flag is active, checks if the given group ID is considered
     * the primary audio group. A group is considered primary if: 1. It is currently {@code active}.
     * 2. It is currently in the process of becoming {@code active} (getting active). 3. It is
     * designated as the fallback group for the Broadcast to Unicast transition.
     *
     * <p>if is not active then return true if group is primary - is active or was active before
     * switch to broadcast.
     *
     * @param groupId The ID of the LE Audio group.
     * @return {@code true} if the group is primary, {@code false} otherwise.
     */
    public boolean isPrimaryGroup(int groupId) {
        if (Flags.leaudioFallbackGroupSelection()) {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                return false;
            }

            return descriptor.isActive()
                    || descriptor.isGettingActive()
                    || (groupId == mBroadcastToUnicastFallbackGroup);
        } else {
            return groupId != IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID
                    && groupId == mBroadcastToUnicastFallbackGroup;
        }
    }

    /** Get local broadcast receiving devices */
    public Set<BluetoothDevice> getLocalBroadcastReceivers() {
        if (mBroadcastDescriptors == null) {
            Log.e(TAG, "getLocalBroadcastReceivers: Invalid Broadcast Descriptors");
            return Collections.emptySet();
        }

        final var bassClient = getAdapterService().getBassClientService();
        if (bassClient.isEmpty()) {
            Log.e(TAG, "getLocalBroadcastReceivers: Bass service not available");
            return Collections.emptySet();
        }

        Set<BluetoothDevice> deviceList = new HashSet<>();
        for (Map.Entry<Integer, LeAudioBroadcastDescriptor> entry :
                mBroadcastDescriptors.entrySet()) {
            if (!entry.getValue().mState.equals(LeAudioStackEvent.BROADCAST_STATE_STOPPED)) {
                List<BluetoothDevice> devices =
                        bassClient.get().getSyncedBroadcastSinks(entry.getKey());
                deviceList.addAll(devices);
            }
        }
        return deviceList;
    }

    /**
     * Selects the default Bluetooth device for the unicast fallback group from a list of
     * candidates.
     */
    public void selectDefaultBroadcastToUnicastFallbackGroup(Set<BluetoothDevice> devices) {
        mBroadcastReceivers = devices;

        setDefaultBroadcastToUnicastFallbackGroup();
    }

    private boolean areBroadcastsAllStopped() {
        if (mBroadcastDescriptors == null) {
            Log.e(TAG, "areBroadcastsAllStopped: Invalid Broadcast Descriptors");
            return false;
        }

        return mBroadcastDescriptors.values().stream()
                .allMatch(d -> d.mState.equals(LeAudioStackEvent.BROADCAST_STATE_STOPPED));
    }

    private Optional<Integer> getFirstNotStoppedBroadcastId() {
        if (mBroadcastDescriptors == null) {
            Log.e(TAG, "getFirstNotStoppedBroadcastId: Invalid Broadcast Descriptors");
            return Optional.empty();
        }

        for (Map.Entry<Integer, LeAudioBroadcastDescriptor> entry :
                mBroadcastDescriptors.entrySet()) {
            if (!entry.getValue().mState.equals(LeAudioStackEvent.BROADCAST_STATE_STOPPED)) {
                return Optional.of(entry.getKey());
            }
        }

        return Optional.empty();
    }

    private boolean areAllGroupsInNotActiveState() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> entry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor descriptor = entry.getValue();
                if (!descriptor.isInactive()) {
                    return false;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return true;
    }

    private boolean areAllGroupsInNotGettingActiveState() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> entry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor descriptor = entry.getValue();
                if (descriptor.isGettingActive()) {
                    return false;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return true;
    }

    private Integer getFirstGroupIdInGettingActiveState() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> entry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor descriptor = entry.getValue();
                if (descriptor.isGettingActive()) {
                    return entry.getKey();
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return LE_AUDIO_GROUP_ID_INVALID;
    }

    private BluetoothDevice getLeadDeviceForTheGroup(Integer groupId) {
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return null;
        }
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                Log.e(TAG, "Group " + groupId + " does not exist");
                return null;
            }

            if (groupDescriptor.mCurrentLeadDevice != null
                    && getConnectionState(groupDescriptor.mCurrentLeadDevice) == STATE_CONNECTED) {
                return groupDescriptor.mCurrentLeadDevice;
            }

            for (LeAudioDeviceDescriptor descriptor : mDeviceDescriptors.values()) {
                if (!descriptor.mGroupId.equals(groupId)) {
                    continue;
                }

                LeAudioStateMachine sm = descriptor.mStateMachine;
                if (sm == null || sm.getConnectionState() != STATE_CONNECTED) {
                    continue;
                }
                groupDescriptor.mCurrentLeadDevice = sm.getDevice();
                return groupDescriptor.mCurrentLeadDevice;
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return null;
    }

    private boolean updateActiveInDevice(
            BluetoothDevice device,
            Integer groupId,
            Integer oldSupportedAudioDirections,
            Integer newSupportedAudioDirections) {
        boolean oldSupportedByDeviceInput =
                (oldSupportedAudioDirections & AUDIO_DIRECTION_INPUT_BIT) != 0;
        boolean newSupportedByDeviceInput =
                (newSupportedAudioDirections & AUDIO_DIRECTION_INPUT_BIT) != 0;

        /*
         * Do not update input if neither previous nor current device support input
         */
        if (!oldSupportedByDeviceInput && !newSupportedByDeviceInput) {
            Log.d(TAG, "updateActiveInDevice: Device does not support input.");
            return false;
        }

        if (device != null && mActiveAudioInDevice != null) {
            LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(mActiveAudioInDevice);
            if (deviceDescriptor == null) {
                Log.e(
                        TAG,
                        "updateActiveInDevice: No valid descriptor for device: "
                                + mActiveAudioInDevice);
                return false;
            }

            if (deviceDescriptor.mGroupId.equals(groupId)) {
                /* This is the same group as already notified to the system.
                 * Therefore do not change the device we have connected to the group,
                 * unless, previous one is disconnected now
                 */
                if (getAdapterService().isConnected(mActiveAudioInDevice)) {
                    device = mActiveAudioInDevice;
                }
            } else if (deviceDescriptor.mGroupId != LE_AUDIO_GROUP_ID_INVALID) {
                mEventLogger.logd(
                        TAG,
                        "Switching(input) active group from "
                                + deviceDescriptor.mGroupId
                                + " to "
                                + groupId);
                /* Mark old group as no active */
                LeAudioGroupDescriptor descriptor = getGroupDescriptor(deviceDescriptor.mGroupId);
                if (descriptor != null) {
                    descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
                }
            }
        }

        BluetoothDevice previousInDevice = mActiveAudioInDevice;

        /*
         * Update input if:
         * - Device changed
         *     OR
         * - Device stops / starts supporting input
         */
        if (!Objects.equals(device, previousInDevice)
                || (oldSupportedByDeviceInput != newSupportedByDeviceInput)) {
            mActiveAudioInDevice = newSupportedByDeviceInput ? device : null;
            return true;
        }
        Log.d(TAG, "updateActiveInDevice: Nothing to do.");
        return false;
    }

    private boolean updateActiveOutDevice(
            BluetoothDevice device,
            Integer groupId,
            Integer oldSupportedAudioDirections,
            Integer newSupportedAudioDirections) {
        boolean oldSupportedByDeviceOutput =
                (oldSupportedAudioDirections & AUDIO_DIRECTION_OUTPUT_BIT) != 0;
        boolean newSupportedByDeviceOutput =
                (newSupportedAudioDirections & AUDIO_DIRECTION_OUTPUT_BIT) != 0;

        /*
         * Do not update output if neither previous nor current device support output
         */
        if (!oldSupportedByDeviceOutput && !newSupportedByDeviceOutput) {
            Log.d(TAG, "updateActiveOutDevice: Device does not support output.");
            return false;
        }

        if (device != null && mActiveAudioOutDevice != null) {
            LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(mActiveAudioOutDevice);
            if (deviceDescriptor == null) {
                Log.e(
                        TAG,
                        "updateActiveOutDevice: No valid descriptor for device: "
                                + mActiveAudioOutDevice);
                return false;
            }

            if (deviceDescriptor.mGroupId.equals(groupId)) {
                /* This is the same group as already notified to the system.
                 * Therefore do not change the device we have connected to the group,
                 * unless, previous one is disconnected now
                 */
                if (getAdapterService().getConnectionState(mActiveAudioOutDevice)
                        != BluetoothDevice.CONNECTION_STATE_DISCONNECTED) {
                    device = mActiveAudioOutDevice;
                }
            } else if (deviceDescriptor.mGroupId != LE_AUDIO_GROUP_ID_INVALID) {
                mEventLogger.logd(
                        TAG,
                        "Switching(output) active group from "
                                + deviceDescriptor.mGroupId
                                + " to "
                                + groupId);
                /* Mark old group as no active */
                LeAudioGroupDescriptor descriptor = getGroupDescriptor(deviceDescriptor.mGroupId);
                if (descriptor != null) {
                    descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
                }
            }
        }

        BluetoothDevice previousOutDevice = mActiveAudioOutDevice;

        /*
         * Update output if:
         * - Device changed
         *     OR
         * - Device stops / starts supporting output
         */
        if (!Objects.equals(device, previousOutDevice)
                || (oldSupportedByDeviceOutput != newSupportedByDeviceOutput)) {
            mActiveAudioOutDevice = newSupportedByDeviceOutput ? device : null;
            return true;
        }
        Log.d(TAG, "updateActiveOutDevice: Nothing to do.");
        return false;
    }

    /**
     * Send broadcast intent about LeAudio connection state changed. This is called by
     * LeAudioStateMachine.
     */
    void notifyConnectionStateChanged(BluetoothDevice device, int newState, int prevState) {
        Log.d(
                TAG,
                "Notify connection state changed."
                        + device
                        + "("
                        + prevState
                        + " -> "
                        + newState
                        + ")");

        getAdapterService()
                .notifyProfileConnectionStateChangeToScan(getProfileId(), prevState, newState);
        getAdapterService()
                .handleProfileConnectionStateChange(getProfileId(), device, prevState, newState);
        mActiveDeviceManager.profileConnectionStateChanged(
                getProfileId(), device, prevState, newState);
        getAdapterService()
                .updateProfileConnectionAdapterProperties(
                        device, getProfileId(), newState, prevState);

        Intent intent = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Util.getTempBroadcastBundle());
    }

    void sendActiveDeviceChangeIntent(BluetoothDevice device) {
        Intent intent = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        getBaseContext()
                .sendBroadcastWithMultiplePermissions(
                        intent, new String[] {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED});
        mEventLogger.logd(
                TAG, "[Intent] Active Device Changed:" + mExposedActiveDevice + " -> " + device);
        mExposedActiveDevice = device;
    }

    private void notifyVolumeControlServiceAboutActiveGroup(BluetoothDevice device) {
        final var vcs = getAdapterService().getVolumeControlService();
        if (vcs.isEmpty()) {
            return;
        }

        if (mExposedActiveDevice != null) {
            vcs.get().setGroupActive(getGroupId(mExposedActiveDevice), false);
        }

        if (device != null) {
            vcs.get().setGroupActive(getGroupId(device), true);
        }
    }

    /**
     * Send broadcast intent about LeAudio active device. This is called when AudioManager confirms,
     * LeAudio device is added or removed.
     */
    @VisibleForTesting
    void notifyActiveDeviceChanged(BluetoothDevice device) {
        Log.d(
                TAG,
                "Notify Active device changed."
                        + device
                        + ". Currently active device is "
                        + mActiveAudioOutDevice
                        + " Currently exposed device "
                        + mExposedActiveDevice);

        getAdapterService().handleActiveDeviceChange(getProfileId(), device);
        notifyVolumeControlServiceAboutActiveGroup(device);
        sendActiveDeviceChangeIntent(device);
        if (device != null) {
            mNativeInterface.groupConfirmActive(getGroupId(device));
        }
    }

    boolean isAnyGroupDisabledFromAutoActiveMode() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> groupEntry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor groupDescriptor = groupEntry.getValue();
                if (!groupDescriptor.mAutoActiveModeEnabled) {
                    Log.d(
                            TAG,
                            "isAnyGroupDisabledFromAutoActiveMode: disabled groupId "
                                    + groupEntry.getKey());
                    return true;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return false;
    }

    /**
     * Configure LE audio service when the system is suspended and resumed. Stop LE scanner if LE
     * audio devices should not reconnect when the system is suspended and restore LE scanner after
     * resume if needed.
     */
    public void setSystemSuspended(boolean suspended) {
        mSystemSuspended = suspended;
        if (suspended) {
            Log.d(TAG, "system suspended, stop background scan");
            mScanCallback.stopBackgroundScan();
        } else if (isScannerNeeded()) {
            Log.d(TAG, "system resumed, restore background scan");
            mScanCallback.startBackgroundScan();
        }
    }

    boolean isScannerNeeded() {
        if (mDeviceDescriptors.isEmpty() || !mBluetoothEnabled) {
            Log.d(TAG, "isScannerNeeded: false, mBluetoothEnabled: " + mBluetoothEnabled);
            return false;
        }

        if (isAnyGroupDisabledFromAutoActiveMode()) {
            Log.d(TAG, "isScannerNeeded true, some group has disabled Auto Active Mode");
            return true;
        }

        if (allLeAudioDevicesConnected()) {
            Log.d(TAG, "isScannerNeeded: all devices connected, scanner not needed");
            return false;
        }

        if (mSystemSuspended) {
            Log.d(TAG, "isScannerNeeded: system suspended, scanner not needed");
            return false;
        }

        Log.d(TAG, "isScannerNeeded: true");
        return true;
    }

    boolean allLeAudioDevicesConnected() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> deviceEntry :
                    mDeviceDescriptors.entrySet()) {
                LeAudioDeviceDescriptor deviceDescriptor = deviceEntry.getValue();

                if (deviceDescriptor.mStateMachine == null) {
                    /* Lack of state machine means device is not connected */
                    return false;
                }

                if (!deviceDescriptor.mStateMachine.isConnected()
                        || !deviceDescriptor.mAclConnected) {
                    return false;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return true;
    }

    private class AudioServerScanCallback extends IScannerCallback.Stub {
        // See BluetoothLeScanner.BleScanCallbackWrapper.mScannerId
        static final int SCANNER_NOT_INITIALIZED = -2;
        static final int SCANNER_INITIALIZING = -1;
        int mScannerId = SCANNER_NOT_INITIALIZED;

        synchronized void startBackgroundScan() {
            if (mScannerId >= 0) {
                Log.i(
                        TAG,
                        "startBackgroundScan: Scanner is already registered with id " + mScannerId);
                return;
            }

            if (mScannerId == SCANNER_INITIALIZING) {
                Log.i(TAG, "startBackgroundScan: Scanner is already initializing");
                return;
            }

            mScannerId = SCANNER_INITIALIZING;
            var source = getAttributionSource();

            ScanFilter filter =
                    new ScanFilter.Builder()
                            .setServiceData(BluetoothUuid.CAP, CAP_TARGETED_ANNOUNCEMENT_PAYLOAD)
                            .build();
            ScanSettings settings =
                    new ScanSettings.Builder()
                            .setLegacy(false)
                            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                            .setPhy(BluetoothDevice.PHY_LE_1M)
                            .build();
            mScanController.doOnScanThread(
                    () ->
                            mScanController.registerAndStartScanInternal(
                                    this, source, settings, List.of(filter)));
        }

        synchronized void stopBackgroundScan() {
            if (mScannerId < 0) {
                Log.d(TAG, "Scanner is not running (mScannerId=" + mScannerId + ")");
                return;
            }
            final var scannerIdToStop = mScannerId;
            mScanController.doOnScanThread(
                    () -> {
                        mScanController.stopScan(scannerIdToStop);
                        mScanController.unregisterScanner(scannerIdToStop);
                    });
            mScannerId = SCANNER_NOT_INITIALIZED;
        }

        @Override
        public synchronized void onScannerRegistered(int status, int scannerId) {
            Log.d(TAG, "onScannerRegistered: status: " + status + ", id:" + scannerId);
            if (status != ScanCallback.NO_ERROR) {
                mScannerId = SCANNER_NOT_INITIALIZED;
                return;
            }
            mScannerId = scannerId;

            // `ScanController#onScannerRegistered` starts the scan for us
        }

        @Override
        public void onScanResult(ScanResult scanResult) {
            Log.d(TAG, "onScanResult: " + scanResult.getDevice());
            BluetoothDevice device = scanResult.getDevice();
            if (device == null) {
                return;
            }

            int groupId = getGroupId(device);
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                return;
            }

            if (!descriptor.mAutoActiveModeEnabled) {
                Log.i(TAG, "onScanResult: GroupId: " + groupId + " is getting Active");
                descriptor.mAutoActiveModeEnabled = true;
                if (!getConnectedPeerDevices(groupId).isEmpty()) {
                    setActiveDevice(device);
                }
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> batchResults) {}

        @Override
        public void onFoundOrLost(boolean onFound, ScanResult scanResult) {}

        @Override
        public void onScanManagerErrorCallback(int errorCode) {}
    }

    /**
     * Handle when AudioManager adds audio device.
     *
     * @param device added audio device
     * @param isSink if device is sink
     * @param isSource if device is source
     * @return true if the exposed active device changed, otherwise false
     */
    public boolean handleAudioDeviceAdded(
            BluetoothDevice device, int type, boolean isSink, boolean isSource) {
        mEventLogger.logd(
                TAG,
                ("[From AudioManager]: handleAudioDeviceAdded: " + device)
                        + (", device type: " + type)
                        + (", isSink: " + isSink)
                        + (" isSource: " + isSource)
                        + (" exposed: " + mExposedActiveDevice));

        /* Don't expose already exposed active device */
        if (device.equals(mExposedActiveDevice)) {
            Log.d(TAG, " onAudioDevicesAdded: " + device + " is already exposed");
            return true;
        }

        if ((isSink && !device.equals(mActiveAudioOutDevice))
                || (isSource && !device.equals(mActiveAudioInDevice))) {
            mEventLogger.loge(
                    TAG,
                    "[From AudioManager]: Added device does not match to the one activated here. ("
                            + (device
                                    + " != "
                                    + mActiveAudioOutDevice
                                    + " / "
                                    + mActiveAudioInDevice
                                    + ")"));
            return false;
        }

        notifyActiveDeviceChanged(device);
        return true;
    }

    /**
     * Handle when AudioManager removes audio device.
     *
     * @param device added audio device
     * @param type of device
     * @param isSink if device is sink
     * @param isSource if device is source
     */
    public void handleAudioDeviceRemoved(
            BluetoothDevice device, int type, boolean isSink, boolean isSource) {
        mEventLogger.logd(
                TAG,
                ("[From AudioManager]: handleAudioDeviceRemoved: " + device)
                        + (" device type: " + type)
                        + (" isSink: " + isSink)
                        + (" isSource: " + isSource)
                        + (" mActiveAudioInDevice: " + mActiveAudioInDevice)
                        + (" mActiveAudioOutDevice: " + mActiveAudioOutDevice)
                        + (" mExposedActiveDevice: " + mExposedActiveDevice));

        if (!device.equals(mExposedActiveDevice)) {
            return;
        }

        if ((isSource && mActiveAudioInDevice == null)
                || (isSink && mActiveAudioOutDevice == null)) {
            if (mActiveAudioInDevice == null && mActiveAudioOutDevice == null) {
                mExposedActiveDevice = null;
            }
            return;
        }

        if (device.equals(mActiveAudioInDevice) || device.equals(mActiveAudioOutDevice)) {
            mEventLogger.loge(
                    TAG,
                    "[From AudioManager]: Audio manager autonomously deactivated LeAudio device."
                            + " Probably restarting and device shall be re-added "
                            + mExposedActiveDevice);

            return;
        }

        mEventLogger.logd(
                TAG,
                ("LeAudio active device switch: "
                        + mExposedActiveDevice
                        + " -> "
                        + (mActiveAudioInDevice != null
                                ? mActiveAudioInDevice
                                : mActiveAudioOutDevice)));
    }

    /* Notifications of audio device connection/disconnection events. */
    private class AudioManagerAudioDeviceCallback extends AudioDeviceCallback {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (Flags.admCentralizeActiveDeviceHandling()) {
                throw new IllegalStateException("admCentralizeActiveDeviceHandling");
            }
            if (!isAvailable()) {
                Log.e(TAG, "Callback called when LeAudioService is stopped");
                return;
            }

            for (AudioDeviceInfo deviceInfo : addedDevices) {
                if ((deviceInfo.getType() != AudioDeviceInfo.TYPE_BLE_HEADSET)
                        && (deviceInfo.getType() != AudioDeviceInfo.TYPE_BLE_SPEAKER)
                        && (deviceInfo.getType() != AudioDeviceInfo.TYPE_BLE_HEARING_AID)) {
                    continue;
                }

                String address = deviceInfo.getAddress();
                if (address.equals("00:00:00:00:00:00")) {
                    continue;
                }

                byte[] addressBytes = Util.getBytesFromAddress(address);
                BluetoothDevice device = getAdapterService().getDeviceFromByte(addressBytes);

                if (handleAudioDeviceAdded(
                        device, deviceInfo.getType(), deviceInfo.isSink(), deviceInfo.isSource())) {
                    return;
                }
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (Flags.admCentralizeActiveDeviceHandling()) {
                throw new IllegalStateException("admCentralizeActiveDeviceHandling");
            }
            if (!isAvailable()) {
                Log.e(TAG, "Callback called when LeAudioService is stopped");
                return;
            }

            for (AudioDeviceInfo deviceInfo : removedDevices) {
                if ((deviceInfo.getType() != AudioDeviceInfo.TYPE_BLE_HEADSET)
                        && (deviceInfo.getType() != AudioDeviceInfo.TYPE_BLE_SPEAKER)
                        && (deviceInfo.getType() != AudioDeviceInfo.TYPE_BLE_HEARING_AID)) {
                    continue;
                }

                String address = deviceInfo.getAddress();
                if (address.equals("00:00:00:00:00:00")) {
                    continue;
                }

                byte[] addressBytes = Util.getBytesFromAddress(address);
                BluetoothDevice device = getAdapterService().getDeviceFromByte(addressBytes);

                handleAudioDeviceRemoved(
                        device, deviceInfo.getType(), deviceInfo.isSink(), deviceInfo.isSource());
            }
        }
    }

    /**
     * Report the active broadcast device change to the active device manager and the media
     * framework.
     *
     * @param newDevice new supported broadcast audio device
     * @param previousDevice previous no longer supported broadcast audio device
     */
    private void updateBroadcastActiveDevice(
            BluetoothDevice newDevice,
            BluetoothDevice previousDevice,
            boolean suppressNoisyIntent) {
        mActiveBroadcastAudioDevice = newDevice;
        mEventLogger.logd(
                TAG,
                "[To AudioManager]: updateBroadcastActiveDevice: newDevice: "
                        + newDevice
                        + ", previousDevice: "
                        + previousDevice);
        mAudioManager.handleBluetoothActiveDeviceChanged(
                newDevice, previousDevice, getBroadcastProfile(suppressNoisyIntent));
    }

    /**
     * Report the active devices change to the active device manager and the media framework.
     *
     * @param groupId id of group which devices should be updated
     * @param newSupportedAudioDirections new supported audio directions for group of devices
     * @param oldSupportedAudioDirections old supported audio directions for group of devices
     * @param isActive if there is new active group
     * @param hasFallbackDevice whether any fallback device exists when deactivating the current
     *     active device.
     * @param notifyAndUpdateInactiveOutDeviceOnly if only output device should be updated to
     *     inactive devices (if new out device would be null device).
     * @return true if group is active after change false otherwise.
     */
    private boolean updateActiveDevices(
            Integer groupId,
            Integer oldSupportedAudioDirections,
            Integer newSupportedAudioDirections,
            boolean isActive,
            boolean hasFallbackDevice,
            boolean notifyAndUpdateInactiveOutDeviceOnly) {
        BluetoothDevice newOutDevice = null;
        BluetoothDevice newInDevice = null;
        BluetoothDevice previousActiveOutDevice = mActiveAudioOutDevice;
        BluetoothDevice previousActiveInDevice = mActiveAudioInDevice;

        if (isActive) {
            newOutDevice = getLeadDeviceForTheGroup(groupId);
            newInDevice = newOutDevice;
        }

        boolean isNewActiveOutDevice =
                updateActiveOutDevice(
                        newOutDevice,
                        groupId,
                        oldSupportedAudioDirections,
                        newSupportedAudioDirections);
        boolean isNewActiveInDevice =
                updateActiveInDevice(
                        newInDevice,
                        groupId,
                        oldSupportedAudioDirections,
                        newSupportedAudioDirections);

        Log.d(
                TAG,
                " isNewActiveOutDevice: "
                        + isNewActiveOutDevice
                        + ", "
                        + mActiveAudioOutDevice
                        + ", isNewActiveInDevice: "
                        + isNewActiveInDevice
                        + ", "
                        + mActiveAudioInDevice
                        + ", notifyAndUpdateInactiveOutDeviceOnly: "
                        + notifyAndUpdateInactiveOutDeviceOnly);

        if (isNewActiveOutDevice) {
            int volume = IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME;

            if (mActiveAudioOutDevice != null) {
                volume = getAudioDeviceGroupVolume(groupId);
            }

            final boolean suppressNoisyIntent = hasFallbackDevice || mActiveAudioOutDevice != null;

            mEventLogger.logd(
                    TAG,
                    "[To AudioManager]: handleBluetoothActiveDeviceChanged previousOutDevice: "
                            + previousActiveOutDevice
                            + (", mActiveAudioOutDevice: " + mActiveAudioOutDevice)
                            + " isLeOutput: true"
                            + (", suppressNoisyIntent: " + suppressNoisyIntent)
                            + (", hasFallbackDevice: " + hasFallbackDevice));

            final BluetoothProfileConnectionInfo connectionInfo =
                    BluetoothProfileConnectionInfo.createLeAudioOutputInfo(
                            suppressNoisyIntent, volume);
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioOutDevice, previousActiveOutDevice, connectionInfo);
        }

        if (isNewActiveInDevice) {
            mEventLogger.logd(
                    TAG,
                    "[To AudioManager]: handleBluetoothActiveDeviceChanged previousActiveInDevice: "
                            + previousActiveInDevice
                            + (", mActiveAudioInDevice: " + mActiveAudioInDevice)
                            + " isLeOutput: false");
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioInDevice,
                    previousActiveInDevice,
                    BluetoothProfileConnectionInfo.createLeAudioInfo(false, false));
        }

        if ((mActiveAudioOutDevice == null)
                && (notifyAndUpdateInactiveOutDeviceOnly || (mActiveAudioInDevice == null))) {
            /* Notify about inactive device as soon as possible.
             * When adding new device, wait with notification until AudioManager is ready
             * with adding the device.
             */
            notifyActiveDeviceChanged(null);
        }

        return mActiveAudioOutDevice != null || mActiveAudioInDevice != null;
    }

    private void clearInactiveDueToContextTypeFlags() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> groupEntry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor groupDescriptor = groupEntry.getValue();
                if (groupDescriptor.mInactivatedDueToContextType) {
                    Log.d(TAG, "clearInactiveDueToContextTypeFlags " + groupEntry.getKey());
                    groupDescriptor.mInactivatedDueToContextType = false;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private void clearAutoActiveModeToDefault() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> groupEntry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor groupDescriptor = groupEntry.getValue();
                if (!groupDescriptor.mAutoActiveModeEnabled) {
                    Log.d(
                            TAG,
                            "mAutoActiveModeEnabled back to default for groupId: "
                                    + groupEntry.getKey());
                    groupDescriptor.mAutoActiveModeEnabled = true;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Set the active device group.
     *
     * @param hasFallbackDevice hasFallbackDevice whether any fallback device exists when {@code
     *     device} is null.
     */
    private boolean setActiveGroupWithDevice(BluetoothDevice device, boolean hasFallbackDevice) {
        int groupId = LE_AUDIO_GROUP_ID_INVALID;

        if (device != null) {
            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                Log.e(TAG, "setActiveGroupWithDevice: No valid descriptor for device: " + device);
                return false;
            }

            groupId = descriptor.mGroupId;

            /* User force device being active, clear the flag */
            clearAutoActiveModeToDefault();

            if (!isGroupAvailableForStream(groupId)) {
                Log.e(
                        TAG,
                        "setActiveGroupWithDevice: groupId "
                                + groupId
                                + " is not available for streaming");
                return false;
            }

            clearInactiveDueToContextTypeFlags();
        }

        int currentlyActiveGroupId = getActiveGroupId();
        Log.d(
                TAG,
                "setActiveGroupWithDevice = "
                        + groupId
                        + ", currentlyActiveGroupId = "
                        + currentlyActiveGroupId
                        + ", device: "
                        + device
                        + ", hasFallbackDevice: "
                        + hasFallbackDevice
                        + ", mExposedActiveDevice: "
                        + mExposedActiveDevice);

        /* Replace fallback unicast and monitoring input device if device is active local
         * broadcaster.
         */
        if (isAnyBroadcastInStreamingState()) {
            Log.w(TAG, "setActiveGroupWithDevice: Setting active device while broadcasting");
            return true;
        }

        LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(currentlyActiveGroupId);
        if (groupDescriptor != null && groupId == currentlyActiveGroupId) {
            /* Make sure active group is already exposed to audio framework.
             * If not, lets wait for it and don't sent additional intent.
             */
            if (Objects.equals(groupDescriptor.mCurrentLeadDevice, mExposedActiveDevice)) {
                Log.w(
                        TAG,
                        "group is already active: device="
                                + device
                                + ", groupId = "
                                + groupId
                                + ", exposedDevice: "
                                + mExposedActiveDevice);
                sendActiveDeviceChangeIntent(mExposedActiveDevice);
            }
            return true;
        }

        if (currentlyActiveGroupId != LE_AUDIO_GROUP_ID_INVALID
                && (groupId != LE_AUDIO_GROUP_ID_INVALID || hasFallbackDevice)) {
            Log.i(TAG, "Remember that device has FallbackDevice when become inactive active");
            groupDescriptor.mHasFallbackDeviceWhenGettingInactive = true;
        }

        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return false;
        }

        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = mGroupDescriptorsView.get(groupId);
            if (descriptor != null) {
                descriptor.setActiveState(ACTIVE_STATE_GETTING_ACTIVE);
            }
        } finally {
            mGroupReadLock.unlock();
        }

        mNativeInterface.groupSetActive(groupId);
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            /* Native will clear its states and send us group Inactive.
             * However we would like to notify audio framework that LeAudio is not
             * active anymore and does not want to get more audio data.
             */
            handleGroupTransitToInactive(currentlyActiveGroupId);
        }
        return true;
    }

    /**
     * Remove the current active group.
     *
     * @param hasFallbackDevice whether any fallback device exists when deactivating the current
     *     active device.
     * @return true on success, otherwise false
     */
    public boolean removeActiveDevice(boolean hasFallbackDevice) {
        /* Clear active group */
        Log.d(TAG, "removeActiveDevice, hasFallbackDevice " + hasFallbackDevice);
        setActiveGroupWithDevice(null, hasFallbackDevice);
        return true;
    }

    /**
     * Set the active group represented by device.
     *
     * @param device the new active device. Should not be null.
     * @return true on success, otherwise false
     */
    public boolean setActiveDevice(BluetoothDevice device) {
        mEventLogger.logd(
                TAG,
                ("[API call] setActiveDevice: device=" + device)
                        + (", current out=" + mActiveAudioOutDevice)
                        + (", current in=" + mActiveAudioInDevice)
                        + (", exposed= " + mExposedActiveDevice));
        /* Clear active group */
        if (device == null) {
            Log.e(TAG, "device should not be null!");
            return removeActiveDevice(false);
        }
        if (getConnectionState(device) != STATE_CONNECTED) {
            Log.e(
                    TAG,
                    "setActiveDevice("
                            + device
                            + "): failed because group device is not "
                            + "connected");
            return false;
        }

        if (Utils.isDualModeAudioEnabled()) {
            if (!getAdapterService().isAllSupportedClassicAudioProfilesActive(device)) {
                Log.e(
                        TAG,
                        "setActiveDevice("
                                + device
                                + "): failed because the device is not active for all supported"
                                + " classic audio profiles");
                return false;
            }
        }
        return setActiveGroupWithDevice(device, false);
    }

    /**
     * Get the active LE audio devices.
     *
     * <p>Note: When LE audio group is active, one of the Bluetooth device address which belongs to
     * the group, represents the active LE audio group - it is called Lead device. Internally, this
     * address is translated to LE audio group id.
     *
     * @return List of active group members. First element is a Lead device.
     */
    public List<BluetoothDevice> getActiveDevices() {
        Log.d(TAG, "getActiveDevices");
        ArrayList<BluetoothDevice> activeDevices = new ArrayList<>(2);
        activeDevices.add(null);
        activeDevices.add(null);

        int currentlyActiveGroupId = getActiveGroupId();
        if (currentlyActiveGroupId == LE_AUDIO_GROUP_ID_INVALID) {
            return activeDevices;
        }

        BluetoothDevice leadDevice = getConnectedGroupLeadDevice(currentlyActiveGroupId);
        activeDevices.set(0, leadDevice);

        int i = 1;
        for (BluetoothDevice dev : getGroupDevices(currentlyActiveGroupId)) {
            if (Objects.equals(dev, leadDevice)) {
                continue;
            }
            if (i == 1) {
                /* Already has a spot for first member */
                activeDevices.set(i++, dev);
            } else {
                /* Extend list with other members */
                activeDevices.add(dev);
            }
        }
        return activeDevices;
    }

    void connectSet(BluetoothDevice device) {
        LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
        if (descriptor == null) {
            Log.e(TAG, "connectSet: No valid descriptor for device: " + device);
            return;
        }
        if (descriptor.mGroupId == LE_AUDIO_GROUP_ID_INVALID) {
            return;
        }

        Log.d(TAG, "connect() others from group id: " + descriptor.mGroupId);

        Integer setGroupId = descriptor.mGroupId;

        for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> entry :
                mDeviceDescriptors.entrySet()) {
            BluetoothDevice storedDevice = entry.getKey();
            descriptor = entry.getValue();
            if (device.equals(storedDevice)) {
                continue;
            }

            if (!descriptor.mGroupId.equals(setGroupId)) {
                continue;
            }

            Log.d(TAG, "connect(): " + storedDevice);

            mGroupReadLock.lock();
            try {
                LeAudioStateMachine sm = getOrCreateStateMachine(storedDevice);
                if (sm == null) {
                    Log.e(
                            TAG,
                            "Ignored connect request for " + storedDevice + " : no state machine");
                    continue;
                }
                sm.sendMessage(LeAudioStateMachine.CONNECT);
            } finally {
                mGroupReadLock.unlock();
            }
        }
    }

    BluetoothProfileConnectionInfo getBroadcastProfile(boolean suppressNoisyIntent) {
        Parcel parcel = Parcel.obtain();
        parcel.writeInt(BluetoothProfile.LE_AUDIO_BROADCAST);
        parcel.writeBoolean(suppressNoisyIntent);
        parcel.writeInt(-1 /* mVolume */);
        parcel.writeBoolean(true /* mIsLeOutput */);
        parcel.setDataPosition(0);

        BluetoothProfileConnectionInfo profileInfo =
                BluetoothProfileConnectionInfo.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return profileInfo;
    }

    private void clearLostDevicesWhileStreaming(LeAudioGroupDescriptor descriptor) {
        mGroupReadLock.lock();
        try {
            Log.d(TAG, "Clearing lost dev: " + descriptor.mLostLeadDeviceWhileStreaming);

            LeAudioDeviceDescriptor deviceDescriptor =
                    getDeviceDescriptor(descriptor.mLostLeadDeviceWhileStreaming);
            if (deviceDescriptor == null) {
                Log.e(
                        TAG,
                        "clearLostDevicesWhileStreaming: No valid descriptor for device: "
                                + descriptor.mLostLeadDeviceWhileStreaming);
                return;
            }

            LeAudioStateMachine sm = deviceDescriptor.mStateMachine;
            if (sm != null) {
                LeAudioStackEvent stackEvent =
                        new LeAudioStackEvent(
                                LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
                stackEvent.device = descriptor.mLostLeadDeviceWhileStreaming;
                stackEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
                sm.sendMessage(LeAudioStateMachine.STACK_EVENT, stackEvent);
            }
            descriptor.mLostLeadDeviceWhileStreaming = null;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private void handleDeviceHealthAction(BluetoothDevice device, int action) {
        Log.d(
                TAG,
                "handleDeviceHealthAction: device: "
                        + device
                        + " action: "
                        + action
                        + ", not implemented");
        if (action == LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_DISABLE) {
            MetricsLogger.getInstance()
                    .count(
                            getAdapterService().isLeAudioAllowed(device)
                                    ? BluetoothProtoEnums
                                            .LE_AUDIO_ALLOWLIST_DEVICE_HEALTH_STATUS_BAD
                                    : BluetoothProtoEnums
                                            .LE_AUDIO_NONALLOWLIST_DEVICE_HEALTH_STATUS_BAD,
                            1);
        }
    }

    private void disableLeAudioAndFallbackToLegacyAudioProfiles(int groupId) {
        Log.i(
                TAG,
                "Disabling LE Audio for group: "
                        + groupId
                        + " and falling back to legacy profiles");
        final var a2dp = getAdapterService().getA2dpService();
        final var headset = getAdapterService().getHeadsetService();
        final var hearingAid = getAdapterService().getHearingAidService();
        boolean isDualMode = Utils.isDualModeAudioEnabled();

        List<BluetoothDevice> leAudioActiveGroupDevices = getGroupDevices(groupId);

        for (BluetoothDevice activeGroupDevice : leAudioActiveGroupDevices) {
            Log.d(TAG, "Disable LE_AUDIO for the device: " + activeGroupDevice);
            final ParcelUuid[] uuids = getAdapterService().getRemoteUuids(activeGroupDevice);

            setConnectionPolicy(activeGroupDevice, CONNECTION_POLICY_FORBIDDEN);
            if (headset.isPresent()
                    && !isDualMode
                    && Util.arrayContains(uuids, BluetoothUuid.HFP)) {
                Log.d(TAG, "Enable HFP for the device: " + activeGroupDevice);
                headset.get().setConnectionPolicy(activeGroupDevice, CONNECTION_POLICY_ALLOWED);
            }
            if (a2dp.isPresent()
                    && !isDualMode
                    && (Util.arrayContains(uuids, BluetoothUuid.A2DP_SINK)
                            || Util.arrayContains(uuids, BluetoothUuid.ADV_AUDIO_DIST))) {
                Log.d(TAG, "Enable A2DP for the device: " + activeGroupDevice);
                a2dp.get().setConnectionPolicy(activeGroupDevice, CONNECTION_POLICY_ALLOWED);
            }
            if (hearingAid.isPresent() && Util.arrayContains(uuids, BluetoothUuid.HEARING_AID)) {
                Log.d(TAG, "Enable ASHA for the device: " + activeGroupDevice);
                hearingAid.get().setConnectionPolicy(activeGroupDevice, CONNECTION_POLICY_ALLOWED);
            }
        }
    }

    private void handleGroupHealthAction(int groupId, int action) {
        Log.d(TAG, "handleGroupHealthAction: groupId: " + groupId + " action: " + action);
        BluetoothDevice device = getLeadDeviceForTheGroup(groupId);
        switch (action) {
            case com.android.bluetooth.le_audio.LeAudioStackEvent
                            .HEALTH_RECOMMENDATION_ACTION_DISABLE -> {
                MetricsLogger.getInstance()
                        .count(
                                getAdapterService().isLeAudioAllowed(device)
                                        ? BluetoothProtoEnums
                                                .LE_AUDIO_ALLOWLIST_GROUP_HEALTH_STATUS_BAD
                                        : BluetoothProtoEnums
                                                .LE_AUDIO_NONALLOWLIST_GROUP_HEALTH_STATUS_BAD,
                                1);
                disableLeAudioAndFallbackToLegacyAudioProfiles(groupId);
            }
            case LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_CONSIDER_DISABLING -> {
                MetricsLogger.getInstance()
                        .count(
                                getAdapterService().isLeAudioAllowed(device)
                                        ? BluetoothProtoEnums
                                                .LE_AUDIO_ALLOWLIST_GROUP_HEALTH_STATUS_TRENDING_BAD
                                        : BluetoothProtoEnums
                                                .LE_AUDIO_NONALLOWLIST_GROUP_HEALTH_STATUS_TRENDING_BAD,
                                1);
            }
            case LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_INACTIVATE_GROUP -> {
                LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
                if (groupDescriptor != null
                        && groupDescriptor.isActive()
                        && !isGroupReceivingBroadcast(groupId)) {
                    Log.i(TAG, "Group " + groupId + " is inactivated due to blocked media context");
                    groupDescriptor.mInactivatedDueToContextType = true;
                    setActiveGroupWithDevice(null, false);
                }
            }
            default -> {}
        }
    }

    private void handleGroupTransitToActive(int groupId) {
        Log.d(TAG, "handleGroupTransitToActive for group: " + groupId);

        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null || (descriptor.isActive())) {
                Log.e(
                        TAG,
                        "handleGroupTransitToActive: no descriptors for group: "
                                + groupId
                                + " or group already active");
                return;
            }

            final int currentlyActiveGroupId = getActiveGroupId();

            if (updateActiveDevices(
                    groupId, AUDIO_DIRECTION_NONE, descriptor.mDirection, true, false, false)) {
                descriptor.setActiveState(ACTIVE_STATE_ACTIVE);
            } else {
                descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
            }

            if (descriptor.isActive()) {
                restoreActiveGroupCodecConfigPreference(groupId);

                mHandler.post(
                        () ->
                                notifyGroupStatusChanged(
                                        groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE));
                updateInbandRingtoneForTheGroup(groupId);

                if (currentlyActiveGroupId != LE_AUDIO_GROUP_ID_INVALID) {
                    updateInbandRingtoneForTheGroup(currentlyActiveGroupId);
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Checks if starting or resuming a broadcast is allowed in the current audio and recording
     * mode.
     *
     * @return {@code true} if broadcast is allowed to be active, {@code false} otherwise.
     */
    private boolean isBroadcastAllowedToActivateInCurrentMode() {
        if (Flags.leaudioFixStreamConfirmDatapathRace()) {
            if (mCurrentRecordingMode) {
                return false;
            }
        }

        switch (mCurrentAudioMode) {
            case AudioManager.MODE_NORMAL:
                return true;
            case AudioManager.MODE_RINGTONE:
            case AudioManager.MODE_IN_CALL:
            case AudioManager.MODE_IN_COMMUNICATION:
            default:
                return false;
        }
    }

    private boolean isBroadcastReadyToBeActivated() {
        return areAllGroupsInNotGettingActiveState()
                && (!mCreateBroadcastQueue.isEmpty()
                        || mBroadcastIdDeactivatedForUnicastTransition.isPresent())
                && isBroadcastAllowedToActivateInCurrentMode();
    }

    private boolean isBroadcastReadyToBeReActivated() {
        return areAllGroupsInNotGettingActiveState()
                && mBroadcastIdDeactivatedForUnicastTransition.isPresent()
                && isBroadcastAllowedToActivateInCurrentMode();
    }

    private BluetoothDevice getBroadcastBluetoothDevice() {
        return getAdapterService().getDeviceFromByte(Util.getBytesFromAddress("FF:FF:FF:FF:FF:FF"));
    }

    private void handleGroupTransitToInactive(int groupId) {
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null || descriptor.isInactive()) {
                Log.e(
                        TAG,
                        "handleGroupTransitToInactive: no descriptors for group: "
                                + groupId
                                + " or group already inactive");
                return;
            }

            descriptor.setActiveState(ACTIVE_STATE_INACTIVE);

            if (isBroadcastReadyToBeActivated()) {
                /* Update Broadcast device before streaming state in handover case to avoid switch
                 * to non LE Audio device in Audio Manager e.g. Phone Speaker for broadcast to
                 * unicast handover case.
                 */
                BluetoothDevice broadcastDevice = getBroadcastBluetoothDevice();
                if (!broadcastDevice.equals(mActiveBroadcastAudioDevice)) {
                    updateBroadcastActiveDevice(broadcastDevice, mActiveBroadcastAudioDevice, true);
                }

                /* After group de-activation a deactivated unicast device would be potential
                 * ringtone streaming device.
                 */
                updateInbandRingtoneForTheGroup(mBroadcastToUnicastFallbackGroup);
            }

            updateActiveDevices(
                    groupId,
                    descriptor.mDirection,
                    AUDIO_DIRECTION_NONE,
                    false,
                    descriptor.mHasFallbackDeviceWhenGettingInactive,
                    false);
            /* Clear lost devices */
            Log.d(TAG, "Clear for group: " + groupId);
            descriptor.mHasFallbackDeviceWhenGettingInactive = false;
            clearLostDevicesWhileStreaming(descriptor);
            mHandler.post(
                    () ->
                            notifyGroupStatusChanged(
                                    groupId, LeAudioStackEvent.GROUP_STATUS_INACTIVE));
            updateInbandRingtoneForTheGroup(groupId);
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private void pauseBroadcastDueToStartingUnicast() {
        Log.d(TAG, "pauseBroadcastDueToUnicast ");

        Optional<Integer> broadcastId = getFirstNotStoppedBroadcastId();
        if (broadcastId.isEmpty() || (mBroadcastDescriptors.get(broadcastId.get()) == null)) {
            Log.e(
                    TAG,
                    "pauseBroadcastDueToStartingUnicast: Broadcast to Unicast handover not"
                            + " possible");
            return;
        }

        mBroadcastIdDeactivatedForUnicastTransition = Optional.of(broadcastId.get());
        pauseBroadcast(broadcastId.get());
    }

    private void deactivateUnicastDueToActivatingBroadcast() {
        Log.d(TAG, "deactivateUnicastDueToActivatingBroadcast if needed");
        if (!areAllGroupsInNotActiveState() && isBroadcastReadyToBeActivated()) {
            removeActiveDevice(true);
        }
    }

    private void handleSinkStreamStatusChange(int unicastStatus) {
        Log.d(TAG, "handleSinkStreamStatusChange status: " + unicastStatus);

        switch (unicastStatus) {
            case LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED ->
                    pauseBroadcastDueToStartingUnicast();
            case LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED ->
                    deactivateUnicastDueToActivatingBroadcast();
            default -> {}
        }
    }

    private void handleSourceStreamStatusChange(int status) {
        final var bassClient = getAdapterService().getBassClientService();
        if (bassClient.isEmpty()) {
            Log.e(TAG, "handleSourceStreamStatusChange: BASS Client service is not available");

            mIsSourceStreamMonitorModeEnabled = false;
            mNativeInterface.setUnicastMonitorMode(LeAudioStackEvent.DIRECTION_SOURCE, false);
        }

        bassClient.get().handleUnicastSourceStreamStatusChange(status);
    }

    private void handleUnicastStreamStatusChange(int direction, int status) {
        if (direction == LeAudioStackEvent.DIRECTION_SINK) {
            handleSinkStreamStatusChange(status);
        } else if (direction == LeAudioStackEvent.DIRECTION_SOURCE) {
            handleSourceStreamStatusChange(status);
        } else {
            Log.e(TAG, "handleUnicastStreamStatusChange: invalid direction: " + direction);
        }
    }

    private boolean isGroupReceivingBroadcast(int groupId) {
        final var bassClient = getAdapterService().getBassClientService();
        if (bassClient.isEmpty()) {
            return false;
        }

        return bassClient.get().isAnyReceiverActive(getGroupDevices(groupId));
    }

    private void notifyGroupStreamStatusChanged(int groupId, int groupStreamStatus) {
        synchronized (mLeAudioCallbacks) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks
                            .getBroadcastItem(i)
                            .onGroupStreamStatusChanged(groupId, groupStreamStatus);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    private void notifyBroadcastToUnicastFallbackGroupChanged(int groupId) {
        synchronized (mLeAudioCallbacks) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks
                            .getBroadcastItem(i)
                            .onBroadcastToUnicastFallbackGroupChanged(groupId);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    /**
     * Set allowed context which should be considered while Audio Framework would request streaming.
     *
     * @param groupId LE Audio group id
     * @param sinkContextTypes sink context types that would be allowed to stream
     * @param sourceContextTypes source context types that would be allowed to stream
     */
    public void setGroupAllowedContextMask(
            int groupId, int sinkContextTypes, int sourceContextTypes) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }

        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            Log.i(TAG, "setActiveGroupAllowedContextMask: no active group");
            return;
        }

        LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
        if (groupDescriptor == null) {
            Log.e(TAG, "Group " + groupId + " does not exist");
            return;
        }

        groupDescriptor.updateAllowedContexts(sinkContextTypes, sourceContextTypes);

        mNativeInterface.setGroupAllowedContextMask(groupId, sinkContextTypes, sourceContextTypes);
    }

    @VisibleForTesting
    void handleGroupIdleDuringCall() {
        if (mHfpHandoverDevice == null) {
            Log.d(TAG, "There is no HFP handover");
            return;
        }
        final var headset = getAdapterService().getHeadsetService();
        if (headset.isEmpty()) {
            Log.d(TAG, "There is no HFP service available");
            return;
        }

        BluetoothDevice activeHfpDevice = headset.get().getActiveDevice();
        if (activeHfpDevice == null) {
            Log.d(TAG, "Make " + mHfpHandoverDevice + " active again ");
            headset.get().setActiveDevice(mHfpHandoverDevice);
        } else {
            Log.d(TAG, "Connect audio to " + activeHfpDevice);
            headset.get().connectAudio();
        }
        mHfpHandoverDevice = null;
    }

    /* Return true if Fallback Unicast Group For Broadcast is the given groupId and broadcast is
     * active or ready to be activated.
     */
    boolean isFallbackUnicastGroupDuringBroadcast(int groupId) {
        if (Flags.leaudioFallbackGroupSelection()) {
            return (groupId != IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID)
                    && (groupId == mBroadcastToUnicastFallbackGroup
                            || groupId == mUnicastGroupIdDeactivatedForBroadcastTransition)
                    && isBroadcastStarted();
        } else {
            return (groupId != IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID)
                    && (groupId == mBroadcastToUnicastFallbackGroup)
                    && isBroadcastStarted();
        }
    }

    void updateInbandRingtoneForTheGroup(int groupId) {
        if (!mLeAudioInbandRingtoneSupportedByPlatform) {
            Log.d(TAG, "Platform does not support inband ringtone");
            return;
        }

        final var tbsService = getAdapterService().getTbsService();
        if (tbsService.isEmpty()) {
            Log.w(TAG, "updateInbandRingtoneForTheGroup, tbsService not available");
            return;
        }

        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                Log.e(TAG, "group descriptor for " + groupId + " does not exist");
                return;
            }

            boolean ringtoneContextAvailable = false;
            if (groupDescriptor.mAvailableContexts != null) {
                ringtoneContextAvailable =
                        ((groupDescriptor.mAvailableContexts
                                        & BluetoothLeAudio.CONTEXT_TYPE_RINGTONE)
                                != 0);
            }

            /* Enables in-band ringtone only for the currently active device or
             * fallback broadcast to unicast device in broadcast scenarios.
             * Devices are notified of its state over GTBS.
             * When enabled, remote devices will not generate an internal ringtone upon
             * receiving a GTBS incoming call notification. Instead, they will wait for a
             * Unicast stream containing the in-band ringtone.
             *
             * Note: In-band ringtone is disabled if any device in the group removes "Ringtone"
             *  from its available context types.
             */
            boolean isRingtoneEnabled =
                    ringtoneContextAvailable
                            && (groupDescriptor.isActive()
                                    || isFallbackUnicastGroupDuringBroadcast(groupId));
            Log.i(
                    TAG,
                    "updateInbandRingtoneForTheGroup groupId: "
                            + (groupId + ", active state: " + groupDescriptor.mActiveState)
                            + (", ringtone supported: " + ringtoneContextAvailable)
                            + (", is fallback Unicast group during broadcast: "
                                    + isFallbackUnicastGroupDuringBroadcast(groupId))
                            + (", isBroadcastReadyToBeActivated: "
                                    + isBroadcastReadyToBeActivated())
                            + (", state change: "
                                    + groupDescriptor.mInbandRingtoneEnabled
                                    + " -> "
                                    + isRingtoneEnabled));

            groupDescriptor.mInbandRingtoneEnabled = isRingtoneEnabled;
            for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> entry :
                    mDeviceDescriptors.entrySet()) {
                if (entry.getValue().mGroupId == groupId) {
                    BluetoothDevice device = entry.getKey();
                    LeAudioDeviceDescriptor deviceDescriptor = entry.getValue();
                    if (Objects.equals(
                            groupDescriptor.mInbandRingtoneEnabled,
                            deviceDescriptor.mDevInbandRingtoneEnabled)) {
                        Log.d(
                                TAG,
                                "updateInbandRingtoneForTheGroup, "
                                        + device
                                        + " has already set inband ringtone to: "
                                        + groupDescriptor.mInbandRingtoneEnabled);
                        continue;
                    }

                    Log.i(
                            TAG,
                            "updateInbandRingtoneForTheGroup, setting group inband ringtone to: "
                                    + groupDescriptor.mInbandRingtoneEnabled
                                    + " to "
                                    + device);

                    deviceDescriptor.mDevInbandRingtoneEnabled =
                            groupDescriptor.mInbandRingtoneEnabled;
                    if (deviceDescriptor.mDevInbandRingtoneEnabled) {
                        tbsService.get().setInbandRingtoneSupport(device);
                    } else {
                        tbsService.get().clearInbandRingtoneSupport(device);
                    }
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private boolean isAnyBroadcastInStreamingState() {
        return mBroadcastDescriptors.values().stream()
                .anyMatch(d -> d.mState.equals(LeAudioStackEvent.BROADCAST_STATE_STREAMING));
    }

    void transitionFromBroadcastToUnicast() {
        int groupIdToActivate = mBroadcastToUnicastFallbackGroup;

        if (Flags.leaudioFallbackGroupSelection()) {
            /* If there were no set fallback group prior, pick deactivated group as backup.
             * This may happen if broadcast is not started properly e.g. create broadcast failed
             */
            if (groupIdToActivate == LE_AUDIO_GROUP_ID_INVALID) {
                groupIdToActivate = mUnicastGroupIdDeactivatedForBroadcastTransition;
            }

            mUnicastGroupIdDeactivatedForBroadcastTransition = LE_AUDIO_GROUP_ID_INVALID;
        }

        if (groupIdToActivate == LE_AUDIO_GROUP_ID_INVALID) {
            Log.d(TAG, "No deactivated group due for broadcast transmission");
            // Notify audio manager
            if (!isAnyBroadcastInStreamingState()) {
                updateBroadcastActiveDevice(null, mActiveBroadcastAudioDevice, false);
            }
            return;
        }

        BluetoothDevice unicastDevice = getLeadDeviceForTheGroup(groupIdToActivate);
        if (unicastDevice == null) {
            /* All devices from group were disconnected in meantime */
            Log.w(
                    TAG,
                    "transitionFromBroadcastToUnicast: No valid unicast device for group ID: "
                            + groupIdToActivate);
            updateBroadcastActiveDevice(null, mActiveBroadcastAudioDevice, false);
            return;
        }

        Log.d(
                TAG,
                "Transitioning to Unicast stream for group: "
                        + groupIdToActivate
                        + ", with device: "
                        + unicastDevice);

        /* After group activation a fallback broadcast to unicast device should be no longer
         * potential ringtone streaming device.
         */
        updateInbandRingtoneForTheGroup(groupIdToActivate);
        setActiveDevice(unicastDevice);
    }

    private void clearCreateBroadcastTimeoutCallback() {
        if (mHandler == null) {
            Log.e(TAG, "No callback handler");
            return;
        }

        /* Timeout callback already cleared */
        if (mCreateBroadcastTimeoutEvent == null) {
            return;
        }

        mHandler.removeCallbacks(mCreateBroadcastTimeoutEvent);
        mCreateBroadcastTimeoutEvent = null;
    }

    void notifyAudioFrameworkForCodecConfigUpdate(
            int groupId,
            LeAudioGroupDescriptor descriptor,
            boolean outputCodecOrFreqChanged,
            boolean inputCodecOrFreqChanged) {
        Log.i(TAG, "notifyAudioFrameworkForCodecConfigUpdate groupId: " + groupId);

        if (mActiveAudioOutDevice != null && outputCodecOrFreqChanged) {
            int volume = getAudioDeviceGroupVolume(groupId);

            final BluetoothProfileConnectionInfo connectionInfo =
                    BluetoothProfileConnectionInfo.createLeAudioOutputInfo(true, volume);

            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioOutDevice, mActiveAudioOutDevice, connectionInfo);
        }

        if (mActiveAudioInDevice != null && inputCodecOrFreqChanged) {
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioInDevice,
                    mActiveAudioInDevice,
                    BluetoothProfileConnectionInfo.createLeAudioInfo(false, false));
        }
    }

    boolean isCodecChangedForTheStream(
            BluetoothLeAudioCodecStatus previous, BluetoothLeAudioCodecStatus next) {
        /* This function checks if in general CodecType has changed. */
        if ((previous == null) && (next == null)) {
            return false;
        }

        if ((previous == null) || (next == null)) {
            Log.d(TAG, previous + " != " + next);
            return true;
        }

        if (previous.getOutputCodecConfig() == null
                && next.getOutputCodecConfig() == null
                && previous.getInputCodecConfig() == null
                && next.getInputCodecConfig() == null) {
            Log.d(TAG, "There is no input and output");
            return false;
        }

        if (previous.getOutputCodecConfig() == null || next.getOutputCodecConfig() == null) {
            Log.d(TAG, "Output differs: " + previous + " != " + next);
            return true;
        }

        if (previous.getInputCodecConfig() == null || next.getInputCodecConfig() == null) {
            Log.d(TAG, "Input differs: " + previous + " != " + next);
            return true;
        }

        if (previous.getOutputCodecConfig().getCodecType()
                        != next.getOutputCodecConfig().getCodecType()
                && previous.getOutputCodecConfig().getCodecType()
                        != BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID
                && next.getOutputCodecConfig().getCodecType()
                        != BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
            Log.d(
                    TAG,
                    "Different output codec type: "
                            + (previous.getOutputCodecConfig().getCodecName()
                                    + "( "
                                    + (previous.getOutputCodecConfig().getCodecType() + ")")
                                    + " != "
                                    + (next.getOutputCodecConfig().getCodecName())
                                    + "( "
                                    + next.getOutputCodecConfig().getCodecType()
                                    + ")"));

            return true;
        }

        if (previous.getInputCodecConfig().getCodecType()
                        != next.getInputCodecConfig().getCodecType()
                && previous.getInputCodecConfig().getCodecType()
                        != BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID
                && next.getInputCodecConfig().getCodecType()
                        != BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
            Log.d(
                    TAG,
                    "Different Input codec type: "
                            + (previous.getInputCodecConfig().getCodecName()
                                    + "( "
                                    + (previous.getInputCodecConfig().getCodecType() + ")")
                                    + " != "
                                    + (next.getInputCodecConfig().getCodecName())
                                    + "( "
                                    + next.getInputCodecConfig().getCodecType()
                                    + ")"));
            return true;
        }

        if (next.getOutputCodecConfig().getCodecType()
                != BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
            Log.d(TAG, "Current output codec is " + previous.getOutputCodecConfig().getCodecName());
        }

        if (next.getInputCodecConfig().getCodecType()
                != BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
            Log.d(TAG, "Current input codec is " + previous.getInputCodecConfig().getCodecName());
        }

        return false;
    }

    boolean isUsingLc3(BluetoothLeAudioCodecStatus codecStatus) {
        if (codecStatus == null) {
            return false;
        }

        /* For now on both directions we use same codec. */
        return codecStatus.getOutputCodecConfig().getCodecType()
                        == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3
                || codecStatus.getInputCodecConfig().getCodecType()
                        == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3;
    }

    boolean isOutputCodecOrSampleFrequencyChanged(
            BluetoothLeAudioCodecStatus previous, BluetoothLeAudioCodecStatus next) {
        if ((previous == null) && (next == null)) {
            return false;
        }

        if ((previous == null) || (next == null)) {
            Log.d(TAG, previous + " != " + next);
            return true;
        }

        if ((previous.getOutputCodecConfig() == null) && (next.getOutputCodecConfig() == null)) {
            /* Nothing changed here.*/
            return false;
        }

        if ((previous.getOutputCodecConfig() == null || next.getOutputCodecConfig() == null)) {
            Log.d(
                    TAG,
                    "New output codec: "
                            + (previous.getOutputCodecConfig()
                                    + " != "
                                    + next.getOutputCodecConfig()));
            return true;
        }

        if (previous.getOutputCodecConfig().getCodecType()
                != next.getOutputCodecConfig().getCodecType()) {
            Log.d(
                    TAG,
                    "Different output codec type: "
                            + (previous.getOutputCodecConfig().getCodecName()
                                    + "( "
                                    + (previous.getOutputCodecConfig().getCodecType() + ")")
                                    + " != "
                                    + (next.getOutputCodecConfig().getCodecName())
                                    + "( "
                                    + next.getOutputCodecConfig().getCodecType()
                                    + ")"));
            return true;
        }

        if (previous.getOutputCodecConfig().getSampleRate()
                != next.getOutputCodecConfig().getSampleRate()) {
            Log.d(
                    TAG,
                    "Different output sampleRate: "
                            + (previous.getOutputCodecConfig()
                                    + " != "
                                    + next.getOutputCodecConfig()));
            return true;
        }

        return false;
    }

    boolean isInputCodecOrSampleFrequencyChanged(
            BluetoothLeAudioCodecStatus previous, BluetoothLeAudioCodecStatus next) {
        if ((previous == null) && (next == null)) {
            return false;
        }

        if ((previous == null) || (next == null)) {
            Log.d(TAG, previous + " != " + next);
            return true;
        }

        if ((previous.getInputCodecConfig() == null) && (next.getInputCodecConfig() == null)) {
            /* Nothing changed here.*/
            return false;
        }

        if ((previous.getInputCodecConfig() == null) || (next.getInputCodecConfig() == null)) {
            Log.d(
                    TAG,
                    "New input codec: "
                            + (previous.getInputCodecConfig()
                                    + " != "
                                    + next.getInputCodecConfig()));
            return true;
        }

        if (previous.getInputCodecConfig().getCodecType()
                != next.getInputCodecConfig().getCodecType()) {
            Log.d(
                    TAG,
                    "Different Input codec type: "
                            + (previous.getInputCodecConfig().getCodecName()
                                    + "( "
                                    + (previous.getInputCodecConfig().getCodecType() + ")")
                                    + " != "
                                    + (next.getInputCodecConfig().getCodecName())
                                    + "( "
                                    + next.getInputCodecConfig().getCodecType()
                                    + ")"));
            return true;
        }

        if (previous.getInputCodecConfig().getSampleRate()
                != next.getInputCodecConfig().getSampleRate()) {
            Log.d(
                    TAG,
                    "Different input sampleRate: "
                            + (previous.getInputCodecConfig()
                                    + " != "
                                    + next.getInputCodecConfig()));
            return true;
        }

        return false;
    }

    void messageFromNative(LeAudioStackEvent stackEvent) {
        Log.d(TAG, "Message from native: " + stackEvent);
        BluetoothDevice device = stackEvent.device;

        if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
            // Some events require device state machine
            mGroupReadLock.lock();
            try {
                LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
                if (deviceDescriptor == null) {
                    Log.e(TAG, "messageFromNative: No valid descriptor for device: " + device);
                    return;
                }

                LeAudioStateMachine sm = deviceDescriptor.mStateMachine;
                if (sm != null) {
                    /*
                     * To improve scenario when lead Le Audio device is disconnected for the
                     * streaming group, while there are still other devices streaming,
                     * LeAudioService will not notify audio framework or other users about
                     * Le Audio lead device disconnection. Instead we try to reconnect under
                     * the hood and keep using lead device as a audio device identifier in
                     * the audio framework in order to not stop the stream.
                     */
                    int groupId = deviceDescriptor.mGroupId;
                    LeAudioGroupDescriptor descriptor = mGroupDescriptorsView.get(groupId);
                    switch (stackEvent.valueInt1) {
                        case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTING,
                                LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED -> {
                            deviceDescriptor.mAclConnected = false;

                            if (isScannerNeeded()) {
                                mScanCallback.startBackgroundScan();
                            }

                            boolean disconnectDueToUnbond =
                                    (BluetoothDevice.BOND_NONE
                                            == getAdapterService().getBondState(device));
                            if (descriptor != null
                                    && (Objects.equals(device, mActiveAudioOutDevice)
                                            || Objects.equals(device, mActiveAudioInDevice))
                                    && (getConnectedPeerDevices(groupId).size() > 1)
                                    && !disconnectDueToUnbond) {

                                Log.d(TAG, "Adding to lost devices : " + device);
                                descriptor.mLostLeadDeviceWhileStreaming = device;
                                return;
                            }
                        }
                        case LeAudioStackEvent.CONNECTION_STATE_CONNECTED,
                                LeAudioStackEvent.CONNECTION_STATE_CONNECTING -> {
                            deviceDescriptor.mAclConnected = true;
                            if (descriptor != null
                                    && Objects.equals(
                                            descriptor.mLostLeadDeviceWhileStreaming, device)) {
                                Log.d(TAG, "Removing from lost devices : " + device);
                                descriptor.mLostLeadDeviceWhileStreaming = null;
                                /* Try to connect other devices from the group */
                                connectSet(device);
                            }
                        }
                        default -> {} // Nothing to do
                    }
                } else {
                    /* state machine does not exist yet */
                    switch (stackEvent.valueInt1) {
                        case LeAudioStackEvent.CONNECTION_STATE_CONNECTED,
                                LeAudioStackEvent.CONNECTION_STATE_CONNECTING -> {
                            deviceDescriptor.mAclConnected = true;
                            sm = getOrCreateStateMachine(device);
                            /* Incoming connection try to connect other devices from the group */
                            connectSet(device);
                        }
                        default -> {} // Nothing to do
                    }

                    if (sm == null) {
                        Log.e(TAG, "Cannot process stack event: no state machine: " + stackEvent);
                        return;
                    }
                }

                sm.sendMessage(LeAudioStateMachine.STACK_EVENT, stackEvent);
                return;
            } finally {
                mGroupReadLock.unlock();
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED) {
            int groupId = stackEvent.valueInt1;
            int nodeStatus = stackEvent.valueInt2;

            requireNonNull(stackEvent.device);

            switch (nodeStatus) {
                case LeAudioStackEvent.GROUP_NODE_ADDED -> handleGroupNodeAdded(device, groupId);
                case LeAudioStackEvent.GROUP_NODE_REMOVED ->
                        handleGroupNodeRemoved(device, groupId);
                default -> {}
            }
        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED) {
            mInputLocalCodecCapabilities = stackEvent.valueCodecList1;
            mOutputLocalCodecCapabilities = stackEvent.valueCodecList2;
        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED) {
            int groupId = stackEvent.valueInt1;
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                Log.e(TAG, " Group not found " + groupId);
                return;
            }

            descriptor.mInputSelectableConfig = new ArrayList<>(stackEvent.valueCodecList1);
            descriptor.mOutputSelectableConfig = new ArrayList<>(stackEvent.valueCodecList2);

            BluetoothLeAudioCodecConfig emptyConfig =
                    new BluetoothLeAudioCodecConfig.Builder().build();

            descriptor.mInputSelectableConfig.removeIf(n -> n.equals(emptyConfig));
            descriptor.mOutputSelectableConfig.removeIf(n -> n.equals(emptyConfig));

        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED) {
            int groupId = stackEvent.valueInt1;
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                Log.e(TAG, " Group not found " + groupId);
                return;
            }

            BluetoothLeAudioCodecStatus status =
                    new BluetoothLeAudioCodecStatus(
                            stackEvent.valueCodec1,
                            stackEvent.valueCodec2,
                            mInputLocalCodecCapabilities,
                            mOutputLocalCodecCapabilities,
                            descriptor.mInputSelectableConfig,
                            descriptor.mOutputSelectableConfig);

            boolean outputCodecOrFreqChanged =
                    isOutputCodecOrSampleFrequencyChanged(descriptor.mCodecStatus, status);
            boolean inputCodecOrFreqChanged =
                    isInputCodecOrSampleFrequencyChanged(descriptor.mCodecStatus, status);
            boolean codecTypeHasChanged =
                    isCodecChangedForTheStream(descriptor.mCodecStatus, status);

            Log.d(
                    TAG,
                    ("Codec update for group:" + groupId)
                            + (", codecTypeHasChanged: " + codecTypeHasChanged)
                            + (", outputCodecOrFreqChanged: " + outputCodecOrFreqChanged)
                            + (", inputCodecOrFreqChanged: " + inputCodecOrFreqChanged));

            descriptor.mCodecStatus = status;
            mHandler.post(
                    () ->
                            notifyUnicastCodecConfigChanged(
                                    groupId, prepareCodecStatusForTheApi(status)));

            /* For LC3 codec, the sample frequency change does not have to be notified to Audio
             * Framework, as this is internal change done in Bluetooth which is internally synced
             * with Audio HAL over Bluetooth Audio HAL. For other codecs we might want to to still
             * notify Audio Manager e.g. for high res codecs.
             */
            if (descriptor.isActive()
                    && (codecTypeHasChanged
                            || (!isUsingLc3(descriptor.mCodecStatus)
                                    && (outputCodecOrFreqChanged || inputCodecOrFreqChanged)))) {
                /* Audio framework needs to be notified so it get new codec config.
                 * Note: this most likely will trigger device TearDown and Setup which will impact
                 * Bluetooth Audio Session.
                 */
                notifyAudioFrameworkForCodecConfigUpdate(
                        groupId, descriptor, outputCodecOrFreqChanged, inputCodecOrFreqChanged);
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED) {
            int direction = stackEvent.valueInt1;
            int groupId = stackEvent.valueInt2;
            int available_contexts = stackEvent.valueInt5;

            mGroupReadLock.lock();
            try {
                LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
                if (descriptor != null) {
                    if (descriptor.isActive()) {
                        if (updateActiveDevices(
                                groupId, descriptor.mDirection, direction, true, false, false)) {
                            descriptor.setActiveState(ACTIVE_STATE_ACTIVE);
                        } else {
                            descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
                        }

                        if (descriptor.isInactive()) {
                            mHandler.post(
                                    () ->
                                            notifyGroupStatusChanged(
                                                    groupId,
                                                    BluetoothLeAudio.GROUP_STATUS_INACTIVE));
                        }
                    }

                    boolean isInitial = descriptor.mAvailableContexts == null;
                    boolean availableContextChanged =
                            isInitial ? true : descriptor.mAvailableContexts != available_contexts;

                    descriptor.mDirection = direction;
                    descriptor.mAvailableContexts = available_contexts;
                    updateInbandRingtoneForTheGroup(groupId);

                    if (!availableContextChanged) {
                        Log.d(
                                TAG,
                                " Context did not changed for "
                                        + groupId
                                        + ": "
                                        + descriptor.mAvailableContexts);
                        return;
                    }

                    if (descriptor.mAvailableContexts == 0) {
                        if (descriptor.isActive()) {
                            Log.i(
                                    TAG,
                                    " Inactivating group "
                                            + groupId
                                            + " due to unavailable context types");
                            descriptor.mInactivatedDueToContextType = true;
                            setActiveGroupWithDevice(null, false);
                        } else if (isInitial) {
                            Log.i(
                                    TAG,
                                    " New group " + groupId + " with no context types available");
                            descriptor.mInactivatedDueToContextType = true;
                        }
                        return;
                    }

                    if (descriptor.mInactivatedDueToContextType) {
                        Log.i(
                                TAG,
                                " Some context got available again for "
                                        + groupId
                                        + ", try it out: "
                                        + descriptor.mAvailableContexts);
                        descriptor.mInactivatedDueToContextType = false;
                        setActiveGroupWithDevice(getLeadDeviceForTheGroup(groupId), true);
                    }
                } else {
                    Log.e(TAG, "messageFromNative: no descriptors for group: " + groupId);
                }
            } finally {
                mGroupReadLock.unlock();
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE) {
            requireNonNull(stackEvent.device);

            int sink_audio_location = stackEvent.valueInt1;

            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                Log.e(TAG, "messageFromNative: No valid descriptor for device: " + device);
                return;
            }

            descriptor.mSinkAudioLocation = sink_audio_location;

            Log.i(
                    TAG,
                    "EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE:"
                            + device
                            + " audio location:"
                            + sink_audio_location);
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED) {
            int groupId = stackEvent.valueInt1;
            int groupStatus = stackEvent.valueInt2;
            mEventLogger.logd(
                    TAG,
                    "[From Native]: groupId: "
                            + groupId
                            + ", status: "
                            + (groupStatus == LeAudioStackEvent.GROUP_STATUS_ACTIVE
                                    ? "Active"
                                    : "Inactive"));

            switch (groupStatus) {
                case LeAudioStackEvent.GROUP_STATUS_ACTIVE -> {
                    handleGroupTransitToActive(groupId);

                    /* Clear possible exposed broadcast device after activating unicast */
                    if (mActiveBroadcastAudioDevice != null) {
                        updateBroadcastActiveDevice(null, mActiveBroadcastAudioDevice, true);
                    }
                }
                case LeAudioStackEvent.GROUP_STATUS_INACTIVE,
                        LeAudioStackEvent.GROUP_STATUS_AUTONOMOUS_INACTIVE -> {
                    LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
                    if (descriptor == null) {
                        Log.e(TAG, "deviceDisconnected: no descriptors for group: " + groupId);
                        return;
                    }

                    if (descriptor.isActive()) {
                        handleGroupTransitToInactive(groupId);
                    }

                    descriptor.setActiveState(ACTIVE_STATE_INACTIVE);

                    /* In case if group is inactivated due to switch to other */
                    Integer gettingActiveGroupId = getFirstGroupIdInGettingActiveState();
                    if (gettingActiveGroupId != LE_AUDIO_GROUP_ID_INVALID) {
                        /* Context were modified, apply mask to activating group */
                        if (descriptor.areAllowedContextsModified()) {
                            setGroupAllowedContextMask(
                                    gettingActiveGroupId,
                                    descriptor.getAllowedSinkContexts(),
                                    descriptor.getAllowedSourceContexts());
                            setGroupAllowedContextMask(
                                    groupId,
                                    BluetoothLeAudio.CONTEXTS_ALL,
                                    BluetoothLeAudio.CONTEXTS_ALL);
                        }
                        break;
                    }

                    /* Clear allowed context mask if there is no switch of group */
                    if (descriptor.areAllowedContextsModified()) {
                        setGroupAllowedContextMask(
                                groupId,
                                BluetoothLeAudio.CONTEXTS_ALL,
                                BluetoothLeAudio.CONTEXTS_ALL);
                    }

                    if (isBroadcastAllowedToActivateInCurrentMode()) {
                        /* Check if broadcast was deactivated due to unicast */
                        if (mBroadcastIdDeactivatedForUnicastTransition.isPresent()) {
                            startBroadcast(mBroadcastIdDeactivatedForUnicastTransition.get());
                            mBroadcastIdDeactivatedForUnicastTransition = Optional.empty();
                        }
                    }

                    if (groupStatus == LeAudioStackEvent.GROUP_STATUS_AUTONOMOUS_INACTIVE) {
                        getAdapterService()
                                .getBassClientService()
                                .ifPresent(
                                        bassClient ->
                                                bassClient.notifyLeAudioGroupAutonomousInactivated(
                                                        getLeadDeviceForTheGroup(groupId)));
                    }
                }
                case LeAudioStackEvent.GROUP_STATUS_TURNED_IDLE_DURING_CALL -> {
                    handleGroupIdleDuringCall();
                }
                default -> {}
            }
        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_HEALTH_BASED_DEV_RECOMMENDATION) {
            handleDeviceHealthAction(stackEvent.device, stackEvent.valueInt1);
        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_HEALTH_BASED_GROUP_RECOMMENDATION) {
            handleGroupHealthAction(stackEvent.valueInt1, stackEvent.valueInt2);
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED) {
            int broadcastId = stackEvent.valueInt1;
            boolean success = stackEvent.valueBool1;

            mCreateBroadcastQueue.remove();

            if (success) {
                Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " created.");
                mBroadcastDescriptors.put(broadcastId, new LeAudioBroadcastDescriptor());
                mHandler.post(
                        () ->
                                notifyBroadcastStarted(
                                        broadcastId,
                                        BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));

                LeAudioBroadcastSessionStats sessionStats =
                        mBroadcastSessionStats.remove(INVALID_BROADCAST_ID);
                if (sessionStats != null) {
                    sessionStats.updateSessionStatus(
                            BluetoothStatsLog
                                    .BROADCAST_AUDIO_SESSION_REPORTED__SESSION_SETUP_STATUS__SETUP_STATUS_CREATED);
                    sessionStats.updateSessionCreatedTime(SystemClock.elapsedRealtime());
                    mBroadcastSessionStats.put(broadcastId, sessionStats);
                }

                /* Update Broadcast device before streaming state in handover case to avoid switch
                 * to non LE Audio device in Audio Manager e.g. Phone Speaker.
                 */
                BluetoothDevice broadcastDevice = getBroadcastBluetoothDevice();
                if (!broadcastDevice.equals(mActiveBroadcastAudioDevice)) {
                    updateBroadcastActiveDevice(broadcastDevice, mActiveBroadcastAudioDevice, true);
                }

                // Start sending the actual stream
                startBroadcast(broadcastId);
            } else {
                // TODO: Improve reason reporting or extend the native stack event with reason code
                Log.e(
                        TAG,
                        "EVENT_TYPE_BROADCAST_CREATED: Failed to create broadcast: " + broadcastId);

                /* Disconnect Broadcast device which was connected to avoid non LE Audio sound
                 * leak in handover scenario.
                 */
                if (mCreateBroadcastQueue.isEmpty()
                        && mActiveBroadcastAudioDevice != null
                        && (Flags.leaudioFallbackGroupSelection()
                                || (mBroadcastToUnicastFallbackGroup
                                        != LE_AUDIO_GROUP_ID_INVALID))) {
                    transitionFromBroadcastToUnicast();
                }

                mHandler.post(() -> notifyBroadcastStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN));
                logBroadcastSessionStatsWithStatus(
                        INVALID_BROADCAST_ID,
                        BluetoothStatsLog
                                .BROADCAST_AUDIO_SESSION_REPORTED__SESSION_SETUP_STATUS__SETUP_STATUS_CREATE_FAILED);
            }

            clearCreateBroadcastTimeoutCallback();
            mAwaitingBroadcastCreateResponse = false;

            if (Flags.leaudioFallbackGroupSelection()) {
                /* Tracking of the deactivated group is no longer needed once the broadcast
                 * creation process has finished (regardless of success or failure),
                 * as the tracking was a temporary fallback mechanism.
                 */
                int deactivatedGroupId = mUnicastGroupIdDeactivatedForBroadcastTransition;
                mUnicastGroupIdDeactivatedForBroadcastTransition = LE_AUDIO_GROUP_ID_INVALID;
                updateInbandRingtoneForTheGroup(deactivatedGroupId);
            }

            // In case if there were additional calls to create broadcast
            if (!mCreateBroadcastQueue.isEmpty()) {
                BluetoothLeBroadcastSettings settings = mCreateBroadcastQueue.remove();
                createBroadcast(settings);
            }

        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_BROADCAST_DESTROYED) {
            final Integer broadcastId = stackEvent.valueInt1;
            LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
            if (descriptor == null) {
                Log.e(
                        TAG,
                        "EVENT_TYPE_BROADCAST_DESTROYED: No valid descriptor for broadcastId: "
                                + broadcastId);
            } else {
                mBroadcastDescriptors.remove(broadcastId);
            }

            /* Clean up the exposed broadcast device after destroy, skipping if a handover
             * occurred.
             */
            if (!isAnyBroadcastInStreamingState() && (mActiveBroadcastAudioDevice != null)) {
                updateBroadcastActiveDevice(null, mActiveBroadcastAudioDevice, true);
            }

            // TODO: Improve reason reporting or extend the native stack event with reason code
            mHandler.post(
                    () ->
                            notifyOnBroadcastStopped(
                                    broadcastId, BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
            getAdapterService()
                    .getBassClientService()
                    .ifPresent(
                            bassClient ->
                                    bassClient.stopReceiversSourceSynchronization(broadcastId));
            logBroadcastSessionStatsWithStatus(
                    broadcastId,
                    BluetoothStatsLog
                            .BROADCAST_AUDIO_SESSION_REPORTED__SESSION_SETUP_STATUS__SETUP_STATUS_UNKNOWN);
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE) {
            int broadcastId = stackEvent.valueInt1;
            int state = stackEvent.valueInt2;
            int previousState;

            LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
            if (descriptor == null) {
                Log.e(
                        TAG,
                        "EVENT_TYPE_BROADCAST_STATE: No valid descriptor for broadcastId: "
                                + broadcastId);
                return;
            }

            /* Request broadcast details if not known yet */
            if (!descriptor.mRequestedForDetails) {
                mLeAudioBroadcasterNativeInterface.get().getBroadcastMetadata(broadcastId);
                descriptor.mRequestedForDetails = true;
            }
            previousState = descriptor.mState;
            descriptor.mState = state;

            final var bassClient = getAdapterService().getBassClientService();
            switch (descriptor.mState) {
                case LeAudioStackEvent.BROADCAST_STATE_STOPPED -> {
                    Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " stopped.");

                    // Playback stopped
                    mHandler.post(
                            () ->
                                    notifyPlaybackStopped(
                                            broadcastId,
                                            BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));

                    transitionFromBroadcastToUnicast();
                    destroyBroadcast(broadcastId);
                }
                case LeAudioStackEvent.BROADCAST_STATE_CONFIGURING ->
                        Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " configuring.");
                case LeAudioStackEvent.BROADCAST_STATE_PAUSED -> {
                    Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " paused.");

                    /* Stop here if Broadcast was not in Streaming state before */
                    if (previousState != LeAudioStackEvent.BROADCAST_STATE_STREAMING) {
                        return;
                    }

                    // Playback paused
                    mHandler.post(
                            () ->
                                    notifyPlaybackStopped(
                                            broadcastId,
                                            BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));

                    bassClient.ifPresent(bass -> bass.cacheSuspendingSources(broadcastId));

                    if (mIsBroadcastPausedFromOutside) {
                        mIsBroadcastPausedFromOutside = false;
                        transitionFromBroadcastToUnicast();
                    }
                }
                case LeAudioStackEvent.BROADCAST_STATE_STOPPING ->
                        Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " stopping.");
                case LeAudioStackEvent.BROADCAST_STATE_STREAMING -> {
                    Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " streaming.");

                    // Stream resumed
                    mHandler.post(
                            () ->
                                    notifyPlaybackStarted(
                                            broadcastId,
                                            BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));

                    // Log streaming start timestamp
                    LeAudioBroadcastSessionStats sessionStats =
                            mBroadcastSessionStats.get(broadcastId);
                    if (sessionStats != null) {
                        sessionStats.updateSessionStatus(
                                BluetoothStatsLog
                                        .BROADCAST_AUDIO_SESSION_REPORTED__SESSION_SETUP_STATUS__SETUP_STATUS_STREAMING);
                        sessionStats.updateSessionStreamingTime(SystemClock.elapsedRealtime());
                    }

                    if (previousState == LeAudioStackEvent.BROADCAST_STATE_PAUSED) {
                        bassClient.ifPresent(
                                BassClientService::resumeReceiversSourceSynchronization);
                    }
                }
                default -> Log.e(TAG, "Invalid state of broadcast: " + descriptor.mState);
            }

            // Notify broadcast assistant
            bassClient.ifPresent(
                    bass -> bass.notifyBroadcastStateChanged(descriptor.mState, broadcastId));
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_BROADCAST_METADATA_CHANGED) {
            int broadcastId = stackEvent.valueInt1;
            if (stackEvent.broadcastMetadata == null) {
                Log.e(TAG, "Missing Broadcast metadata for broadcastId: " + broadcastId);
            } else {
                LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
                if (descriptor == null) {
                    Log.e(
                            TAG,
                            "EVENT_TYPE_BROADCAST_METADATA_CHANGED: No valid descriptor for "
                                    + "broadcastId: "
                                    + broadcastId);
                    return;
                }
                descriptor.mMetadata = stackEvent.broadcastMetadata;
                mHandler.post(
                        () ->
                                notifyBroadcastMetadataChanged(
                                        broadcastId, stackEvent.broadcastMetadata));
            }
        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED) {
            boolean success = stackEvent.valueBool1;

            if (!success) {
                /* On fail, EVENT_TYPE_BROADCAST_CREATED will be send with fail too */
                Log.e(TAG, "EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED: failed to create");

                if (mAwaitingBroadcastCreateResponse) {
                    mAwaitingBroadcastCreateResponse = false;
                    mCreateBroadcastQueue.clear();
                }

                return;
            }

            /* Broadcast creation procedure were initiated and some unicast group are still
             * active.
             */
            if (mAwaitingBroadcastCreateResponse && !areAllGroupsInNotActiveState()) {
                /* Broadcast would be created once unicast group became inactive */
                Log.i(
                        TAG,
                        "Unicast group is active, deactivate group: "
                                + getActiveGroupId()
                                + " due to pending broadcast");

                if (Flags.leaudioFallbackGroupSelection()) {
                    mUnicastGroupIdDeactivatedForBroadcastTransition = getActiveGroupId();
                }

                removeActiveDevice(true);
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_NATIVE_INITIALIZED) {
            mLeAudioNativeIsInitialized = true;
            for (Map.Entry<ParcelUuid, Pair<Integer, Integer>> entry :
                    ContentControlIdKeeper.getUuidToCcidContextPairMap().entrySet()) {
                ParcelUuid userUuid = entry.getKey();
                Pair<Integer, Integer> ccidInformation = entry.getValue();
                setCcidInformation(userUuid, ccidInformation.first, ccidInformation.second);
            }
            if (!Flags.leaudioCentralizeTmap() && !mTmapStarted) {
                mTmapStarted = registerTmap();
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS) {
            handleUnicastStreamStatusChange(stackEvent.valueInt1, stackEvent.valueInt2);
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_GROUP_STREAM_STATUS_CHANGED) {
            mHandler.post(
                    () ->
                            notifyGroupStreamStatusChanged(
                                    stackEvent.valueInt1, stackEvent.valueInt2));
        }
    }

    private LeAudioStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }

        LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
        if (descriptor == null) {
            Log.e(TAG, "getOrCreateStateMachine: No valid descriptor for device: " + device);
            return null;
        }

        LeAudioStateMachine sm = descriptor.mStateMachine;
        if (sm != null) {
            return sm;
        }

        Log.d(TAG, "Creating a new state machine for " + device);
        sm = new LeAudioStateMachine(device, this, mNativeInterface, mStateMachinesLooper);
        descriptor.mStateMachine = sm;
        return sm;
    }

    @Override
    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    /**
     * Process a change in the bonding state for a device.
     *
     * @param device the device whose bonding state has changed
     * @param bondState the new bond state for the device. Possible values are: {@link
     *     BluetoothDevice#BOND_NONE}, {@link BluetoothDevice#BOND_BONDING}, {@link
     *     BluetoothDevice#BOND_BONDED}.
     */
    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);
        // Remove state machine if the bonding for a device is removed
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }

        mGroupReadLock.lock();
        try {
            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                Log.e(TAG, "bondStateChanged: No valid descriptor for device: " + device);
                return;
            }

            descriptor.mGroupId = LE_AUDIO_GROUP_ID_INVALID;
            descriptor.mSinkAudioLocation = BluetoothLeAudio.AUDIO_LOCATION_INVALID;
            descriptor.mDirection = AUDIO_DIRECTION_NONE;

            LeAudioStateMachine sm = descriptor.mStateMachine;
            if (sm == null) {
                return;
            }
            if (sm.getConnectionState() != STATE_DISCONNECTED) {
                Log.w(TAG, "Device is not disconnected yet.");
                disconnect(device);
                return;
            }
        } finally {
            mGroupReadLock.unlock();
        }
        removeStateMachine(device);
        removeAuthorizationInfoForRelatedProfiles(device);
    }

    private void removeStateMachine(BluetoothDevice device) {
        mGroupReadLock.lock();
        try {
            try {
                LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
                if (descriptor == null) {
                    Log.e(TAG, "removeStateMachine: No valid descriptor for device: " + device);
                    return;
                }

                LeAudioStateMachine sm = descriptor.mStateMachine;
                if (sm == null) {
                    Log.w(
                            TAG,
                            "removeStateMachine: device "
                                    + device
                                    + " does not have a state machine");
                    return;
                }
                Log.i(TAG, "removeStateMachine: removing state machine for device: " + device);
                if (Flags.leaudioIntentBroadcastInStateMachineCleanup()) {
                    sm.doQuit();
                } else {
                    sm.quit();
                }
                sm.cleanup();
                descriptor.mStateMachine = null;
            } finally {
                // Upgrade to write lock
                mGroupReadLock.unlock();
                mGroupWriteLock.lock();
            }
            mDeviceDescriptors.remove(device);
            if (!isScannerNeeded()) {
                mScanCallback.stopBackgroundScan();
            }
        } finally {
            mGroupWriteLock.unlock();
        }
    }

    @VisibleForTesting
    List<BluetoothDevice> getConnectedPeerDevices(int groupId) {
        List<BluetoothDevice> result = new ArrayList<>();
        for (BluetoothDevice peerDevice : getConnectedDevices()) {
            if (getGroupId(peerDevice) == groupId) {
                result.add(peerDevice);
            }
        }
        return result;
    }

    /** Process a change for connection of a device. */
    public synchronized void deviceConnected(BluetoothDevice device) {
        LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
        if (deviceDescriptor == null) {
            Log.e(TAG, "deviceConnected: No valid descriptor for device: " + device);
            return;
        }

        LeAudioGroupDescriptor descriptor = getGroupDescriptor(deviceDescriptor.mGroupId);
        if (descriptor != null) {
            descriptor.mIsConnected = true;
        } else {
            Log.e(TAG, "deviceConnected: no descriptors for group: " + deviceDescriptor.mGroupId);
        }

        if (!isScannerNeeded()) {
            mScanCallback.stopBackgroundScan();
        }

        /* Set by default earliest connected device */
        if (mBroadcastToUnicastFallbackGroup == LE_AUDIO_GROUP_ID_INVALID) {
            setDefaultBroadcastToUnicastFallbackGroup();
        }

        if (Flags.leaudioAllowlistRefactor()) {
            setAllowlistFlag(device, getAdapterService().isLeAudioAllowed(device));
        }
    }

    /** Process a change for disconnection of a device. */
    public synchronized void deviceDisconnected(BluetoothDevice device, boolean hasFallbackDevice) {
        Log.d(TAG, "deviceDisconnected " + device);

        int groupId = LE_AUDIO_GROUP_ID_INVALID;
        mGroupReadLock.lock();
        try {
            LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
            if (deviceDescriptor == null) {
                Log.e(TAG, "deviceDisconnected: No valid descriptor for device: " + device);
                return;
            }
            groupId = deviceDescriptor.mGroupId;
        } finally {
            mGroupReadLock.unlock();
        }

        int bondState = getAdapterService().getBondState(device);
        if (bondState == BluetoothDevice.BOND_NONE) {
            Log.d(TAG, device + " is unbond. Remove state machine");

            removeStateMachine(device);
            removeAuthorizationInfoForRelatedProfiles(device);
        }

        if (!isScannerNeeded()) {
            mScanCallback.stopBackgroundScan();
        }

        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                Log.e(TAG, "deviceDisconnected: no descriptors for group: " + groupId);
                return;
            }

            List<BluetoothDevice> connectedDevices = getConnectedPeerDevices(groupId);
            /* Let's check if the last connected device is really connected */
            if (connectedDevices.size() == 1
                    && Objects.equals(
                            connectedDevices.get(0), descriptor.mLostLeadDeviceWhileStreaming)) {
                clearLostDevicesWhileStreaming(descriptor);
                return;
            }

            if (getConnectedPeerDevices(groupId).isEmpty()) {
                descriptor.mIsConnected = false;
                descriptor.mAutoActiveModeEnabled = true;
                descriptor.mAvailableContexts = null;
                if (descriptor.isActive()) {
                    /* Notify Native layer */
                    removeActiveDevice(hasFallbackDevice);
                    descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
                    /* Update audio framework */
                    updateActiveDevices(
                            groupId,
                            descriptor.mDirection,
                            descriptor.mDirection,
                            false,
                            hasFallbackDevice,
                            false);
                    /* Set by default earliest connected device */
                    if (mBroadcastToUnicastFallbackGroup == groupId) {
                        setDefaultBroadcastToUnicastFallbackGroup();
                    }
                    return;
                }

                /* Set by default earliest connected device */
                if (mBroadcastToUnicastFallbackGroup == groupId) {
                    setDefaultBroadcastToUnicastFallbackGroup();
                }
            }

            if (descriptor.isActive()
                    || Objects.equals(mActiveAudioOutDevice, device)
                    || Objects.equals(mActiveAudioInDevice, device)) {
                updateActiveDevices(
                        groupId,
                        descriptor.mDirection,
                        descriptor.mDirection,
                        descriptor.isActive(),
                        hasFallbackDevice,
                        false);
            }
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Get device audio location.
     *
     * @param device LE Audio capable device
     * @return the sink audio location that this device currently exposed
     */
    public int getAudioLocation(BluetoothDevice device) {
        if (device == null) {
            return BluetoothLeAudio.AUDIO_LOCATION_INVALID;
        }

        LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
        if (descriptor == null) {
            Log.e(TAG, "getAudioLocation: No valid descriptor for device: " + device);
            return BluetoothLeAudio.AUDIO_LOCATION_INVALID;
        }

        return descriptor.mSinkAudioLocation;
    }

    /**
     * Check if inband ringtone is enabled by the LE Audio group. Group id for the device can be
     * found with {@link BluetoothLeAudio#getGroupId}.
     *
     * @param groupId LE Audio group id
     * @return true if inband ringtone is enabled, false otherwise
     */
    public boolean isInbandRingtoneEnabled(int groupId) {
        if (!mLeAudioInbandRingtoneSupportedByPlatform) {
            return mLeAudioInbandRingtoneSupportedByPlatform;
        }

        LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
        if (descriptor == null) {
            return false;
        }

        return descriptor.mInbandRingtoneEnabled;
    }

    /**
     * Set In Call state
     *
     * @param inCall True if device in call (any state), false otherwise.
     */
    public void setInCall(boolean inCall) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }

        mNativeInterface.setInCall(inCall);
    }

    /**
     * Set allowlist flag
     *
     * @param device the remote device to check
     */
    public void setAllowlistFlag(BluetoothDevice device, boolean allowed) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }

        mNativeInterface.setAllowlistFlag(device, allowed);
    }

    /**
     * Sends the preferred audio profiles for a dual mode audio device to the native stack.
     *
     * @param groupId is the group id of the device which had a preference change
     * @param isOutputPreferenceLeAudio {@code true} if {@link BluetoothProfile#LE_AUDIO} is
     *     preferred for {@link BluetoothAdapter#AUDIO_MODE_OUTPUT_ONLY}, {@code false} if it is
     *     {@link BluetoothProfile#A2DP}
     * @param isDuplexPreferenceLeAudio {@code true} if {@link BluetoothProfile#LE_AUDIO} is
     *     preferred for {@link BluetoothAdapter#AUDIO_MODE_DUPLEX}, {@code false} if it is {@link
     *     BluetoothProfile#HEADSET}
     */
    public void sendAudioProfilePreferencesToNative(
            int groupId, boolean isOutputPreferenceLeAudio, boolean isDuplexPreferenceLeAudio) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }
        mNativeInterface.sendAudioProfilePreferences(
                groupId, isOutputPreferenceLeAudio, isDuplexPreferenceLeAudio);
    }

    /**
     * Set allowed context which should be considered while Audio Framework would request streaming.
     *
     * @param sinkContextTypes sink context types that would be allowed to stream
     * @param sourceContextTypes source context types that would be allowed to stream
     */
    public void setActiveGroupAllowedContextMask(int sinkContextTypes, int sourceContextTypes) {
        setGroupAllowedContextMask(getActiveGroupId(), sinkContextTypes, sourceContextTypes);
    }

    /**
     * Set Inactive by HFP during handover This is a work around to handle controllers that cannot
     * have SCO and CIS at the same time. So remove active device to tear down CIS, and re-connect
     * the SCO in {@link LeAudioService#handleGroupIdleDuringCall()}
     *
     * @param hfpHandoverDevice is the hfp device that was set to active
     */
    public void setInactiveForHfpHandover(BluetoothDevice hfpHandoverDevice) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }
        if (getActiveGroupId() != LE_AUDIO_GROUP_ID_INVALID) {
            mHfpHandoverDevice = hfpHandoverDevice;
            // record the lead device
            mLeAudioDeviceInactivatedForHfpHandover = mExposedActiveDevice;
            removeActiveDevice(true);
        }
    }

    /** Resume prior active device after HFP phone call hand over */
    public void setActiveAfterHfpHandover() {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }
        if (mLeAudioDeviceInactivatedForHfpHandover != null) {
            Log.i(TAG, "handover to LE audio device=" + mLeAudioDeviceInactivatedForHfpHandover);
            setActiveDevice(mLeAudioDeviceInactivatedForHfpHandover);
            mLeAudioDeviceInactivatedForHfpHandover = null;
        } else {
            Log.d(TAG, "nothing to handover back");
        }
    }

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED} or disconnects if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p>The device should already be paired. Connection policy can be one of: {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED}, {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device the remote device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true on success, otherwise false
     */
    @Override
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);

        getAdapterService().setProfileConnectionPolicy(device, getProfileId(), connectionPolicy);

        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            setEnabledState(device, /* enabled= */ true);
            // Authorizes LEA GATT server services if already assigned to a group
            int groupId = getGroupId(device);
            if (groupId != LE_AUDIO_GROUP_ID_INVALID) {
                setAuthorizationForRelatedProfiles(device, true);
            }
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            setEnabledState(device, /* enabled= */ false);
            // Remove authorization for LEA GATT server services
            setAuthorizationForRelatedProfiles(device, false);
            disconnect(device);
        }
        setLeAudioGattClientProfilesPolicy(device, connectionPolicy);
        return true;
    }

    /**
     * Sets the connection policy for LE Audio GATT client profiles
     *
     * @param device is the remote device
     * @param connectionPolicy is the connection policy we wish to set
     */
    private void setLeAudioGattClientProfilesPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(
                TAG,
                "setLeAudioGattClientProfilesPolicy for device "
                        + device
                        + " to policy="
                        + connectionPolicy);
        final ParcelUuid[] featureUuids = getAdapterService().getRemoteUuids(device);

        final var vcs = getAdapterService().getVolumeControlService();
        if (vcs.isPresent() && Util.arrayContains(featureUuids, BluetoothUuid.VOLUME_CONTROL)) {
            vcs.get().setConnectionPolicy(device, connectionPolicy);
        }

        final var hapClient = getAdapterService().getHapClientService();
        if (hapClient.isPresent() && Util.arrayContains(featureUuids, BluetoothUuid.HAS)) {
            if (Flags.hapOnMainLooper()) {
                hapClient.get().post(h -> h.setConnectionPolicy(device, connectionPolicy));
            } else {
                hapClient.get().setConnectionPolicy(device, connectionPolicy);
            }
        }

        final var csipSetCoordinator = getAdapterService().getCsipSetCoordinatorService();
        // Disallow setting CSIP to forbidden until characteristic reads are complete
        if (csipSetCoordinator.isPresent()
                && Util.arrayContains(featureUuids, BluetoothUuid.COORDINATED_SET)) {
            csipSetCoordinator.get().setConnectionPolicy(device, connectionPolicy);
        }

        final var bassClient = getAdapterService().getBassClientService();
        if (bassClient.isPresent()
                && bassClient.get().isEnabled()
                && Util.arrayContains(featureUuids, BluetoothUuid.BASS)) {
            bassClient.get().setConnectionPolicy(device, connectionPolicy);
        }
    }

    /**
     * Get device group id. Devices with same group id belong to same group (i.e left and right
     * earbud)
     *
     * @param device LE Audio capable device
     * @return group id that this device currently belongs to
     */
    public int getGroupId(BluetoothDevice device) {
        if (device == null) {
            return LE_AUDIO_GROUP_ID_INVALID;
        }

        mGroupReadLock.lock();
        try {
            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                Log.v(TAG, "getGroupId: No valid descriptor for device: " + device);
                return LE_AUDIO_GROUP_ID_INVALID;
            }

            return descriptor.mGroupId;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Set auto active mode state.
     *
     * <p>Auto Active Mode by default is set to true and it means, that after ACL connection is
     * created to LeAudio device which is part of the group, can be connected to Audio Framework
     * (set as Active).
     *
     * <p>If Auto Active Mode is set to false, it means that after LeAudio device is connected for
     * given group, the function isGroupAvailableForStream(groupId) will return false and
     * ActiveDeviceManager will not make this group active.
     *
     * <p>This mode can change internally when two things happen: 1. LeAudioService detects Targeted
     * Announcements from the device which belongs to the group.
     * 2. @BluetoothLeAudio.setActiveDevice() is called with a device which belongs to the group.
     *
     * <p>Note: Auto Active Mode can be disabled only when all devices from the group are
     * disconnected.
     *
     * @param groupId LeAudio group id which Auto Active Mode should be changed.
     * @param enabled true when Auto Active Mode should be enabled (default value), false otherwise.
     * @return true when Auto Active Mode is set, false otherwise
     */
    public boolean setAutoActiveModeState(int groupId, boolean enabled) {
        Log.d(TAG, "setAutoActiveModeState: groupId: " + groupId + " enabled: " + enabled);

        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                Log.i(
                        TAG,
                        "setAutoActiveModeState: groupId: "
                                + groupId
                                + " is not known LeAudio group");
                return false;
            }

            if (descriptor.mAutoActiveModeEnabled == enabled) {
                // Nothing has changed.
                return true;
            }

            boolean isGroupConnectingOrConnected = isGroupConnectingOrConnected(groupId);

            /* Disabling Auto Active Mode is allowed only when all the devices from the group
             * are disconnected or disconnecting */
            if (!enabled && isGroupConnectingOrConnected) {
                Log.i(
                        TAG,
                        "setAutoActiveModeState: GroupId: "
                                + groupId
                                + " is already connected or connecting");
                return false;
            }

            Log.i(TAG, "setAutoActiveModeState: groupId: " + groupId + ", enabled: " + enabled);
            descriptor.mAutoActiveModeEnabled = enabled;
            return true;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Is Auto Active Mode enabled
     *
     * @param groupId LeAudio group id which Auto Active Mode should be taken.
     * @return true when Auto Active Mode is enabled, false otherwise
     */
    public boolean isAutoActiveModeEnabled(int groupId) {
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                Log.i(
                        TAG,
                        "isAutoActiveModeEnabled: groupId: "
                                + groupId
                                + " is not known LeAudio group");
                return false;
            }
            Log.i(
                    TAG,
                    "isAutoActiveModeEnabled: groupId: "
                            + groupId
                            + ", mAutoActiveModeEnabled: "
                            + descriptor.mAutoActiveModeEnabled);
            return descriptor.mAutoActiveModeEnabled;

        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Check if group is available for streaming. If there is no available context types then group
     * is not available for streaming.
     *
     * @param groupId groupId
     * @return true if available, false otherwise
     */
    public boolean isGroupAvailableForStream(int groupId) {
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                Log.e(
                        TAG,
                        "isGroupAvailableForStream: No valid descriptor for groupId: " + groupId);
                return false;
            }

            if (!descriptor.mAutoActiveModeEnabled) {
                Log.e(
                        TAG,
                        "isGroupAvailableForStream: Auto Active Mode is disabled for groupId: "
                                + groupId);
                return false;
            }

            Log.i(TAG, " descriptor.mAvailableContexts: " + descriptor.mAvailableContexts);
            return descriptor.mAvailableContexts != null && descriptor.mAvailableContexts != 0;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Set the user application ccid along with used context type
     *
     * @param userUuid user uuid
     * @param ccid content control id
     * @param contextType context type
     */
    public void setCcidInformation(ParcelUuid userUuid, int ccid, int contextType) {
        /* for the moment we care only for GMCS, GTBS and VAP */
        if (!BluetoothUuid.GENERIC_MEDIA_CONTROL.equals(userUuid)
                && !TbsGatt.UUID_GTBS.equals(userUuid.getUuid())
                && !BluetoothUuid.VAP.equals(userUuid)) {
            return;
        }
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }
        mNativeInterface.setCcidInformation(ccid, contextType);
    }

    /**
     * Set volume for streaming devices
     *
     * @param volume volume to set
     */
    public void setVolume(int volume) {
        Log.d(TAG, "SetVolume " + volume);

        int currentlyActiveGroupId = getActiveGroupId();
        List<BluetoothDevice> activeBroadcastSinks = new ArrayList<>();

        if (currentlyActiveGroupId == LE_AUDIO_GROUP_ID_INVALID) {
            final var bassClient = getAdapterService().getBassClientService();
            if (bassClient.isPresent()) {
                activeBroadcastSinks = bassClient.get().getSyncedBroadcastSinks();
            }

            if (activeBroadcastSinks.isEmpty()) {
                Log.e(TAG, "There is no active streaming group or broadcast sinks");
                return;
            }
        }

        final var vcs = getAdapterService().getVolumeControlService();
        if (vcs.isEmpty()) {
            return;
        }
        final int groupForVolume;
        if (currentlyActiveGroupId == LE_AUDIO_GROUP_ID_INVALID
                && !activeBroadcastSinks.isEmpty()) {
            if (!activeBroadcastSinks.stream().anyMatch(dev -> isPrimaryGroup(getGroupId(dev)))) {
                Log.w(TAG, "Setting volume when no active or broadcast primary group");
                return;
            }
            groupForVolume = mBroadcastToUnicastFallbackGroup;
            Log.d(TAG, "Setting volume for broadcast sink primary group: " + groupForVolume);
        } else {
            groupForVolume = currentlyActiveGroupId;
        }
        vcs.get().setGroupVolume(groupForVolume, volume);
    }

    public void registerCallback(IBluetoothLeAudioCallback callback) {
        synchronized (mLeAudioCallbacks) {
            mLeAudioCallbacks.register(callback);
        }
        if (!mBluetoothEnabled) {
            handleBluetoothEnabled();
        }
    }

    public void unregisterCallback(IBluetoothLeAudioCallback callback) {
        synchronized (mLeAudioCallbacks) {
            mLeAudioCallbacks.unregister(callback);
        }
    }

    public void registerLeBroadcastCallback(IBluetoothLeBroadcastCallback callback) {
        synchronized (mBroadcastCallbacks) {
            mBroadcastCallbacks.register(callback);
        }
    }

    public void unregisterLeBroadcastCallback(IBluetoothLeBroadcastCallback callback) {
        synchronized (mBroadcastCallbacks) {
            mBroadcastCallbacks.unregister(callback);
        }
    }

    private void setAuthorizationForRelatedProfiles(BluetoothDevice device, boolean authorize) {
        getAdapterService()
                .getMcpService()
                .ifPresent(mcp -> mcp.setDeviceAuthorized(device, authorize));

        getAdapterService()
                .getTbsService()
                .ifPresent(tbsService -> tbsService.setDeviceAuthorized(device, authorize));

        if (Flags.headtrackerConnectionPolicy()) {
            getAdapterService()
                    .getHidHostService()
                    .ifPresent(
                            hidHostService ->
                                    hidHostService.setAndroidHeadTrackerEnabled(device, authorize));
        }
    }

    private void removeAuthorizationInfoForRelatedProfiles(BluetoothDevice device) {
        getAdapterService()
                .getMcpService()
                .ifPresent(mcp -> mcp.removeDeviceAuthorizationInfo(device));

        getAdapterService()
                .getTbsService()
                .ifPresent(tbsService -> tbsService.removeDeviceAuthorizationInfo(device));
    }

    /**
     * This function is called when the framework registers a callback with the service for this
     * first time. This is used as an indication that Bluetooth has been enabled.
     *
     * <p>It is used to authorize all known LeAudio devices in the services which requires that e.g.
     * GMCS
     */
    @VisibleForTesting
    void handleBluetoothEnabled() {
        Log.d(TAG, "handleBluetoothEnabled ");

        mBluetoothEnabled = true;

        mGroupReadLock.lock();
        try {
            if (mDeviceDescriptors.isEmpty()) {
                return;
            }
            for (BluetoothDevice device : mDeviceDescriptors.keySet()) {
                int connection_policy = getConnectionPolicy(device);
                if (connection_policy != CONNECTION_POLICY_FORBIDDEN) {
                    setAuthorizationForRelatedProfiles(device, true);
                }
                setEnabledState(device, connection_policy != CONNECTION_POLICY_FORBIDDEN);
            }
        } finally {
            mGroupReadLock.unlock();
        }

        if (isScannerNeeded()) {
            mScanCallback.startBackgroundScan();
        }
    }

    @VisibleForTesting
    void handleAudioModeChange(int mode) {
        if (Flags.leaudioFixStreamConfirmDatapathRace()) {
            if (mode == mCurrentAudioMode) {
                return;
            }
        }

        mEventLogger.logd(
                TAG,
                "[From AudioManager]: Audio mode changed: " + mCurrentAudioMode + " -> " + mode);
        int previousAudioMode = mCurrentAudioMode;

        mCurrentAudioMode = mode;

        switch (mode) {
            case AudioManager.MODE_RINGTONE,
                    AudioManager.MODE_IN_CALL,
                    AudioManager.MODE_IN_COMMUNICATION -> {
                if (!areBroadcastsAllStopped()) {
                    /* Request activation of unicast group */
                    pauseBroadcastDueToStartingUnicast();
                }
            }
            case AudioManager.MODE_NORMAL -> {
                /* Remove broadcast if during handover active LE Audio device disappears
                 * (switch to primary device or non LE Audio device)
                 */
                if (isBroadcastReadyToBeReActivated()
                        && isAudioModeChangedFromCommunicationToNormal(
                                previousAudioMode, mCurrentAudioMode)
                        && (getActiveGroupId() == LE_AUDIO_GROUP_ID_INVALID)) {
                    stopBroadcast(mBroadcastIdDeactivatedForUnicastTransition.get());
                    mBroadcastIdDeactivatedForUnicastTransition = Optional.empty();
                    break;
                }

                if (mBroadcastIdDeactivatedForUnicastTransition.isPresent()) {
                    deactivateUnicastDueToActivatingBroadcast();
                }
            }
            default -> Log.d(TAG, "Not handled audio mode set: " + mode);
        }
    }

    private LeAudioGroupDescriptor getGroupDescriptor(int groupId) {
        mGroupReadLock.lock();
        try {
            return mGroupDescriptorsView.get(groupId);
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private LeAudioDeviceDescriptor getDeviceDescriptor(BluetoothDevice device) {
        mGroupReadLock.lock();
        try {
            return mDeviceDescriptors.get(device);
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private void handleGroupNodeAdded(BluetoothDevice device, int groupId) {
        mGroupWriteLock.lock();
        try {
            Log.d(TAG, "Device " + device + " added to group " + groupId);

            LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                mGroupDescriptors.put(groupId, new LeAudioGroupDescriptor(groupId, false));
            }
            groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                Log.e(TAG, "Could not create group description");
                return;
            }
            LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
            if (deviceDescriptor == null) {
                deviceDescriptor =
                        createDeviceDescriptor(device, groupDescriptor.mInbandRingtoneEnabled);
                if (deviceDescriptor == null) {
                    Log.e(
                            TAG,
                            "handleGroupNodeAdded: Can't create descriptor for added from"
                                    + " storage device: "
                                    + device);
                    return;
                }

                LeAudioStateMachine unused = getOrCreateStateMachine(device);
                if (getOrCreateStateMachine(device) == null) {
                    Log.e(TAG, "Can't get state machine for device: " + device);
                    return;
                }
            }
            deviceDescriptor.mGroupId = groupId;

            mHandler.post(() -> notifyGroupNodeAdded(device, groupId));
        } finally {
            mGroupWriteLock.unlock();
        }

        /* Set by default earliest connected device */
        if (mBroadcastToUnicastFallbackGroup == LE_AUDIO_GROUP_ID_INVALID) {
            setDefaultBroadcastToUnicastFallbackGroup();
        }

        if (mBluetoothEnabled) {
            setAuthorizationForRelatedProfiles(device, true);
            if (isScannerNeeded()) {
                mScanCallback.startBackgroundScan();
            }
        }
    }

    private void notifyGroupNodeAdded(BluetoothDevice device, int groupId) {
        getAdapterService()
                .getVolumeControlService()
                .ifPresent(vcs -> vcs.handleGroupNodeAdded(groupId, device));

        synchronized (mLeAudioCallbacks) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks.getBroadcastItem(i).onGroupNodeAdded(device, groupId);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    private void handleGroupNodeRemoved(BluetoothDevice device, int groupId) {
        Log.d(TAG, "Removing device " + device + " from group " + groupId);

        boolean isGroupEmpty = true;
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                Log.e(TAG, "handleGroupNodeRemoved: No valid descriptor for group: " + groupId);
                return;
            }
            Log.d(TAG, "Lost lead device is " + groupDescriptor.mLostLeadDeviceWhileStreaming);
            if (Objects.equals(device, groupDescriptor.mLostLeadDeviceWhileStreaming)) {
                clearLostDevicesWhileStreaming(groupDescriptor);
            }

            LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
            if (deviceDescriptor == null) {
                Log.e(TAG, "handleGroupNodeRemoved: No valid descriptor for device: " + device);
                return;
            }
            deviceDescriptor.mGroupId = LE_AUDIO_GROUP_ID_INVALID;

            for (LeAudioDeviceDescriptor descriptor : mDeviceDescriptors.values()) {
                if (descriptor.mGroupId == groupId) {
                    isGroupEmpty = false;
                    break;
                }
            }

            if (isGroupEmpty) {
                /* Device is currently an active device. Group needs to be inactivated before
                 * removing
                 */
                if (Objects.equals(device, mActiveAudioOutDevice)
                        || Objects.equals(device, mActiveAudioInDevice)) {
                    handleGroupTransitToInactive(groupId);
                }

                if (mBroadcastToUnicastFallbackGroup == groupId) {
                    setDefaultBroadcastToUnicastFallbackGroup();
                }
            }
            mHandler.post(() -> notifyGroupNodeRemoved(device, groupId));
        } finally {
            mGroupReadLock.unlock();
        }

        if (isGroupEmpty) {
            mGroupWriteLock.lock();
            try {
                mGroupDescriptors.remove(groupId);
            } finally {
                mGroupWriteLock.unlock();
            }
        }

        setAuthorizationForRelatedProfiles(device, false);
        removeAuthorizationInfoForRelatedProfiles(device);
    }

    private void notifyGroupNodeRemoved(BluetoothDevice device, int groupId) {
        synchronized (mLeAudioCallbacks) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks.getBroadcastItem(i).onGroupNodeRemoved(device, groupId);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    private void notifyGroupStatusChanged(int groupId, int status) {
        synchronized (mLeAudioCallbacks) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks.getBroadcastItem(i).onGroupStatusChanged(groupId, status);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    private void notifyUnicastCodecConfigChanged(int groupId, BluetoothLeAudioCodecStatus status) {
        synchronized (mLeAudioCallbacks) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks.getBroadcastItem(i).onCodecConfigChanged(groupId, status);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    private void notifyBroadcastStarted(Integer broadcastId, int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onBroadcastStarted(reason, broadcastId);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyBroadcastStartFailed(int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onBroadcastStartFailed(reason);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyOnBroadcastStopped(Integer broadcastId, int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onBroadcastStopped(reason, broadcastId);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyOnBroadcastStopFailed(int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onBroadcastStopFailed(reason);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyPlaybackStarted(Integer broadcastId, int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onPlaybackStarted(reason, broadcastId);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyPlaybackStopped(Integer broadcastId, int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onPlaybackStopped(reason, broadcastId);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyBroadcastUpdateFailed(int broadcastId, int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks
                            .getBroadcastItem(i)
                            .onBroadcastUpdateFailed(reason, broadcastId);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyBroadcastMetadataChanged(
            int broadcastId, BluetoothLeBroadcastMetadata metadata) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks
                            .getBroadcastItem(i)
                            .onBroadcastMetadataChanged(broadcastId, metadata);
                } catch (RemoteException e) {
                    // Ignore Exception
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    /**
     * Update the fallback unicast group id during the handover to broadcast Also store the fallback
     * group id in Settings store.
     *
     * @param groupId group id to update
     */
    private void updateFallbackUnicastGroupIdForBroadcast(int groupId) {
        if (mBroadcastToUnicastFallbackGroup == groupId) {
            Log.d(TAG, "Skip mBroadcastToUnicastFallbackGroup, already is primary");
            return;
        }
        Log.i(
                TAG,
                "Update unicast fallback active group from: "
                        + mBroadcastToUnicastFallbackGroup
                        + " to : "
                        + groupId);
        int oldBroadcastToUnicastFallbackGroup = mBroadcastToUnicastFallbackGroup;
        mBroadcastToUnicastFallbackGroup = groupId;

        // Revise inband ringtone support for old and new Fallback Unicast group
        if (isBroadcastStarted()) {
            if (oldBroadcastToUnicastFallbackGroup != LE_AUDIO_GROUP_ID_INVALID) {
                updateInbandRingtoneForTheGroup(oldBroadcastToUnicastFallbackGroup);
            }
            if (groupId != LE_AUDIO_GROUP_ID_INVALID) {
                updateInbandRingtoneForTheGroup(groupId);
            }
        }

        mHandler.post(() -> notifyBroadcastToUnicastFallbackGroupChanged(groupId));
    }

    private static boolean isAudioModeChangedFromCommunicationToNormal(
            int previousMode, int currentMode) {
        return switch (previousMode) {
            case AudioManager.MODE_RINGTONE,
                    AudioManager.MODE_IN_CALL,
                    AudioManager.MODE_IN_COMMUNICATION ->
                    currentMode == AudioManager.MODE_NORMAL;
            default -> false;
        };
    }

    private void logBroadcastSessionStatsWithStatus(int broadcastId, int status) {
        LeAudioBroadcastSessionStats sessionStats = mBroadcastSessionStats.remove(broadcastId);
        if (sessionStats != null) {
            if (status
                    != BluetoothStatsLog
                            .BROADCAST_AUDIO_SESSION_REPORTED__SESSION_SETUP_STATUS__SETUP_STATUS_UNKNOWN) {
                sessionStats.updateSessionStatus(status);
            }
            sessionStats.logBroadcastSessionMetrics(broadcastId, SystemClock.elapsedRealtime());
        }
    }

    private void logAllBroadcastSessionStatsAndCleanup() {
        for (Map.Entry<Integer, LeAudioBroadcastSessionStats> entry :
                mBroadcastSessionStats.entrySet()) {
            LeAudioBroadcastSessionStats sessionStats = entry.getValue();
            Integer broadcastId = entry.getKey();
            sessionStats.logBroadcastSessionMetrics(broadcastId, SystemClock.elapsedRealtime());
        }
        mBroadcastSessionStats.clear();
    }

    private static BluetoothLeAudioCodecStatus prepareCodecStatusForTheApi(
            BluetoothLeAudioCodecStatus codecStatus) {
        if (codecStatus == null) {
            return null;
        }
        BluetoothLeAudioCodecConfig emptyConfig = new BluetoothLeAudioCodecConfig.Builder().build();

        BluetoothLeAudioCodecStatus codecStatusForTheApi =
                new BluetoothLeAudioCodecStatus(
                        ((codecStatus.getInputCodecConfig() == null
                                        || codecStatus.getInputCodecConfig().equals(emptyConfig))
                                ? null
                                : codecStatus.getInputCodecConfig()),
                        ((codecStatus.getOutputCodecConfig() == null
                                        || codecStatus.getOutputCodecConfig().equals(emptyConfig))
                                ? null
                                : codecStatus.getOutputCodecConfig()),
                        codecStatus.getInputCodecLocalCapabilities(),
                        codecStatus.getOutputCodecLocalCapabilities(),
                        codecStatus.getInputCodecSelectableCapabilities(),
                        codecStatus.getOutputCodecSelectableCapabilities());
        return codecStatusForTheApi;
    }

    /**
     * Gets the current codec status (configuration and capability).
     *
     * @param groupId the group id
     * @return the current codec status
     */
    public BluetoothLeAudioCodecStatus getCodecStatus(int groupId) {
        LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
        if (descriptor == null) {
            Log.d(
                    TAG,
                    "getCodecStatus(" + groupId + ") = null because there is no group descriptor");
            return null;
        }

        BluetoothLeAudioCodecStatus codecStatus =
                prepareCodecStatusForTheApi(descriptor.mCodecStatus);
        Log.d(TAG, "getCodecStatus(" + groupId + ") = " + codecStatus);
        return codecStatus;
    }

    private boolean shouldUpdateCodecConfigPreference(BluetoothLeAudioCodecConfig codecConfig) {
        // Note: Opus and Opus Hi-res are a different codecs at the API level, but still
        // the same codec at the Bluetooth specification level. If we have both flavors of the Opus
        // codec configuration priorities set by the API, we should call to native only with an
        // equal or higher codec priority of the two, since the BT Audio HAL receives the same
        // Bluetooth domain codec identifier when setting the priority for both.
        if (codecConfig.getCodecType() != BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_OPUS
                && codecConfig.getCodecType()
                        != BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_OPUS_HI_RES) {
            return true;
        }

        int checkAgainstCodecType =
                codecConfig.getCodecType()
                                == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_OPUS_HI_RES
                        ? BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_OPUS
                        : BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_OPUS_HI_RES;
        if (mActiveGroupCodecPreferences.containsKey(checkAgainstCodecType)) {
            return mActiveGroupCodecPreferences.get(checkAgainstCodecType).second.getCodecPriority()
                    <= codecConfig.getCodecPriority();
        }
        return true;
    }

    private void storeActiveGroupCodecConfigPreference(
            int groupId,
            BluetoothLeAudioCodecConfig inputCodecConfig,
            BluetoothLeAudioCodecConfig outputCodecConfig) {
        Log.d(
                TAG,
                "storeActiveGroupCodecConfigPreference("
                        + groupId
                        + "): "
                        + inputCodecConfig
                        + outputCodecConfig);

        // Update the active group codec preference map
        mActiveGroupCodecPreferences.put(
                Integer.valueOf(outputCodecConfig.getCodecType()),
                new Pair<>(inputCodecConfig, outputCodecConfig));

        getStorage()
                .setLeAudioCodecPreferences(getGroupDevices(groupId), mActiveGroupCodecPreferences);
    }

    private void restoreActiveGroupCodecConfigPreference(int groupId) {
        Log.d(TAG, "restoreActiveGroupCodecConfigPreference(" + groupId + ")");

        // Reload the active group codec preferences map from storage
        mActiveGroupCodecPreferences.clear();
        mActiveGroupCodecPreferences.putAll(
                getStorage().getLeAudioCodecPreferences(getGroupDevices(groupId)));
        mActiveGroupCodecPreferences.values().stream()
                .filter(pair -> shouldUpdateCodecConfigPreference(pair.second))
                .forEach(
                        pair ->
                                mNativeInterface.setCodecConfigPreference(
                                        groupId, pair.first, pair.second));
    }

    /**
     * Sets the codec configuration preference.
     *
     * @param groupId the group id
     * @param inputCodecConfig the input codec configuration preference
     * @param outputCodecConfig the output codec configuration preference
     */
    public void setCodecConfigPreference(
            int groupId,
            BluetoothLeAudioCodecConfig inputCodecConfig,
            BluetoothLeAudioCodecConfig outputCodecConfig) {
        Log.d(
                TAG,
                "setCodecConfigPreference("
                        + groupId
                        + "): "
                        + Objects.toString(inputCodecConfig)
                        + Objects.toString(outputCodecConfig));
        LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
        if (descriptor == null) {
            Log.e(TAG, "setCodecConfigPreference: Invalid groupId, " + groupId);
            return;
        }

        if (inputCodecConfig == null || outputCodecConfig == null) {
            Log.e(TAG, "setCodecConfigPreference: Codec config can't be null");
            return;
        }

        /* We support different configuration for input and output but codec type
         * shall be same */
        if (inputCodecConfig.getCodecType() != outputCodecConfig.getCodecType()) {
            Log.e(
                    TAG,
                    "setCodecConfigPreference: Input codec type: "
                            + inputCodecConfig.getCodecType()
                            + "does not match output codec type: "
                            + outputCodecConfig.getCodecType());
            return;
        }

        if (descriptor.mCodecStatus == null) {
            Log.e(TAG, "setCodecConfigPreference: Codec status is null");
            return;
        }

        storeActiveGroupCodecConfigPreference(groupId, inputCodecConfig, outputCodecConfig);

        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }

        if (shouldUpdateCodecConfigPreference(outputCodecConfig)) {
            Log.d(TAG, "setCodecConfigPreference: Send codec preference to native.");
            mNativeInterface.setCodecConfigPreference(groupId, inputCodecConfig, outputCodecConfig);
        }
    }

    void setBroadcastToUnicastFallbackGroup(int groupId) {
        Log.d(TAG, "setBroadcastToUnicastFallbackGroup(" + groupId + ")");

        if (mBroadcastToUnicastFallbackGroup == groupId) {
            Log.d(TAG, "Requested Broadcast to Unicast fallback group is already set");
            return;
        }

        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor oldFallbackGroupDescriptor =
                    getGroupDescriptor(mBroadcastToUnicastFallbackGroup);
            LeAudioGroupDescriptor newFallbackGroupDescriptor = getGroupDescriptor(groupId);
            if (oldFallbackGroupDescriptor == null && newFallbackGroupDescriptor == null) {
                Log.w(
                        TAG,
                        "Failed to set Broadcast to Unicast Fallback group "
                                + "(lack of new and old group descriptors): "
                                + mBroadcastToUnicastFallbackGroup
                                + " -> "
                                + groupId);
                return;
            }

            /* Fallback group should be not updated if new group is not connected or requested
             * group ID is different than INVALID but there is no such descriptor.
             */
            if (groupId != LE_AUDIO_GROUP_ID_INVALID
                    && (newFallbackGroupDescriptor == null
                            || !newFallbackGroupDescriptor.mIsConnected)) {
                Log.w(
                        TAG,
                        "Failed to set Broadcast to Unicast Fallback group (invalid new group): "
                                + mBroadcastToUnicastFallbackGroup
                                + " -> "
                                + groupId);
                return;
            }
        } finally {
            mGroupReadLock.unlock();
        }

        updateFallbackUnicastGroupIdForBroadcast(groupId);
    }

    int getBroadcastToUnicastFallbackGroup() {
        Log.v(
                TAG,
                "getBroadcastToUnicastFallbackGroup(), group id:"
                        + mBroadcastToUnicastFallbackGroup);

        return mBroadcastToUnicastFallbackGroup;
    }

    /**
     * Checks if the remote device supports LE Audio duplex (output and input).
     *
     * @param device the remote device to check
     * @return {@code true} if LE Audio duplex is supported, {@code false} otherwise
     */
    public boolean isLeAudioDuplexSupported(BluetoothDevice device) {
        int groupId = getGroupId(device);
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return false;
        }

        LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
        if (descriptor == null) {
            return false;
        }
        return (descriptor.mDirection & AUDIO_DIRECTION_OUTPUT_BIT) != 0
                && (descriptor.mDirection & AUDIO_DIRECTION_INPUT_BIT) != 0;
    }

    /**
     * Checks if the remote device supports LE Audio output
     *
     * @param device the remote device to check
     * @return {@code true} if LE Audio output is supported, {@code false} otherwise
     */
    public boolean isLeAudioOutputSupported(BluetoothDevice device) {
        int groupId = getGroupId(device);
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return false;
        }

        LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
        if (descriptor == null) {
            return false;
        }
        return (descriptor.mDirection & AUDIO_DIRECTION_OUTPUT_BIT) != 0;
    }

    /**
     * Gets the lead device for the CSIP group containing the provided device
     *
     * @param device the remote device whose CSIP group lead device we want to find
     * @return the lead device of the CSIP group or {@code null} if the group does not exist
     */
    public BluetoothDevice getLeadDevice(BluetoothDevice device) {
        int groupId = getGroupId(device);
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return null;
        }
        return getConnectedGroupLeadDevice(groupId);
    }

    /**
     * Sends the preferred audio profile change requested from a call to {@link
     * BluetoothAdapter#setPreferredAudioProfiles(BluetoothDevice, Bundle)} to the audio framework
     * to apply the change. The audio framework will call {@link
     * BluetoothAdapter#notifyActiveDeviceChangeApplied(BluetoothDevice)} once the change is
     * successfully applied.
     *
     * @return the number of requests sent to the audio framework
     */
    public int sendPreferredAudioProfileChangeToAudioFramework() {
        if (mActiveAudioOutDevice == null && mActiveAudioInDevice == null) {
            Log.e(TAG, "sendPreferredAudioProfileChangeToAudioFramework: no active device");
            return 0;
        }

        int audioFrameworkCalls = 0;

        if (mActiveAudioOutDevice != null) {
            int volume = getAudioDeviceGroupVolume(getGroupId(mActiveAudioOutDevice));
            final boolean suppressNoisyIntent = mActiveAudioOutDevice != null;
            Log.i(
                    TAG,
                    "Sending LE Audio Output active device changed for preferred profile "
                            + "change with volume="
                            + volume
                            + " and suppressNoisyIntent="
                            + suppressNoisyIntent);

            final BluetoothProfileConnectionInfo connectionInfo =
                    BluetoothProfileConnectionInfo.createLeAudioOutputInfo(
                            suppressNoisyIntent, volume);

            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioOutDevice, mActiveAudioOutDevice, connectionInfo);
            audioFrameworkCalls++;
        }

        if (mActiveAudioInDevice != null) {
            Log.i(TAG, "Sending LE Audio Input active device changed for audio profile change");
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioInDevice,
                    mActiveAudioInDevice,
                    BluetoothProfileConnectionInfo.createLeAudioInfo(false, false));
            audioFrameworkCalls++;
        }

        return audioFrameworkCalls;
    }

    private class CreateBroadcastTimeoutEvent implements Runnable {
        CreateBroadcastTimeoutEvent() {}

        @Override
        public void run() {
            Log.w(TAG, "Failed to start Broadcast in time");

            mCreateBroadcastTimeoutEvent = null;
            mCreateBroadcastQueue.remove();
            mAwaitingBroadcastCreateResponse = false;

            /* Disconnect Broadcast device which was connected to avoid non LE Audio sound
             * leak in handover scenario.
             */
            if (Flags.leaudioFallbackGroupSelection()
                    || (mBroadcastToUnicastFallbackGroup != LE_AUDIO_GROUP_ID_INVALID)) {
                if (mCreateBroadcastQueue.isEmpty() && (mActiveBroadcastAudioDevice != null)) {
                    transitionFromBroadcastToUnicast();
                }
            }

            mHandler.post(() -> notifyBroadcastStartFailed(BluetoothStatusCodes.ERROR_TIMEOUT));
            logBroadcastSessionStatsWithStatus(
                    INVALID_BROADCAST_ID,
                    BluetoothStatsLog
                            .BROADCAST_AUDIO_SESSION_REPORTED__SESSION_SETUP_STATUS__SETUP_STATUS_CREATE_FAILED);

            // In case if there were additional calls to create broadcast
            if (!mCreateBroadcastQueue.isEmpty()) {
                BluetoothLeBroadcastSettings settings = mCreateBroadcastQueue.remove();
                createBroadcast(settings);
            }
        }
    }

    private static String getImportanceString(int importance) {
        return switch (importance) {
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
                    "Foreground (" + importance + ")";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ->
                    "Visible (" + importance + ")";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED ->
                    "Background (" + importance + ")";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE ->
                    "Gone (" + importance + ")";
            default -> "Unknown (" + importance + ")";
        };
    }

    private void processGameImportanceChange(int uid, int importance) {
        GameTracker gameTracker = getGameTracker(uid);
        boolean isInGameOnEntry = !mGameTrackingList.isEmpty();

        Log.d(
                TAG,
                ("processGameImportanceChange, uid: "
                        + uid
                        + " game "
                        + (gameTracker != null ? " known" : " unknown")
                        + ", isInGameOnEntry: "
                        + isInGameOnEntry
                        + " importance: "
                        + getImportanceString(importance)));

        boolean gameBecomeActive =
                (importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                        || importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE);
        boolean gameMovedToBackground =
                (importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        boolean gameIsGone = (importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE);

        // Update game tracking list based on received importance
        if (gameBecomeActive) {
            if (gameTracker == null) {
                mGameTrackingList.add(new GameTracker(uid));
            } else {
                gameTracker.stopBackgroundMonitor();
            }
        } else if (gameMovedToBackground) {
            if (gameTracker != null) {
                gameTracker.startBackgroundMonitor(Looper.getMainLooper());
            }
        } else if (gameIsGone) {
            if (gameTracker != null) {
                gameTracker.stopBackgroundMonitor();
                mGameTrackingList.remove(gameTracker);
            }
        }

        // Change the game state
        boolean inInGameOnExit = !mGameTrackingList.isEmpty();
        if (isInGameOnEntry != inInGameOnExit) {
            Log.i(TAG, "Game mode changing from " + isInGameOnEntry + " to " + inInGameOnExit);
            mNativeInterface.setInGame(inInGameOnExit);
        }
    }

    private void checkForGameApplications(List<RunningAppProcessInfo> processes) {
        if (processes == null) {
            Log.e(TAG, "checkForGameApplications, no processes");
            return;
        }

        if (mPackageManager == null) {
            Log.e(TAG, "checkForGameApplications, No PackageManager");
            return;
        }

        for (RunningAppProcessInfo process : processes) {
            if (!isGameApplication(process.uid)) {
                continue;
            }
            processGameImportanceChange(process.uid, process.importance);
        }
    }

    private void registerOnUidImportanceListener() {
        if (!Flags.leaudioGameDetector()) {
            return;
        }

        if (mActivityManager != null) {
            Log.d(TAG, "registerOnUidImportanceListener");
            mActivityManager.addOnUidImportanceListener(
                    mUidImportanceListener,
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE);
            checkForGameApplications(mActivityManager.getRunningAppProcesses());
        } else {
            Log.e(TAG, "registerOnUidImportanceListener, No Activity Manager");
        }
    }

    private void unregisterOnUidImportanceListener() {
        if (!Flags.leaudioGameDetector()) {
            return;
        }

        if (mActivityManager != null) {
            Log.d(TAG, "unregisterOnUidImportanceListener");
            mActivityManager.removeOnUidImportanceListener(mUidImportanceListener);
        } else {
            Log.e(TAG, "unregisterOnUidImportanceListener, No Activity Manager");
        }
    }

    class AudioModeChangeListener implements AudioManager.OnModeChangedListener {
        @Override
        public void onModeChanged(int mode) {
            handleAudioModeChange(mode);
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        ProfileService.println(sb, "isDualModeAudioEnabled: " + Utils.isDualModeAudioEnabled());
        ProfileService.println(sb, "Active Groups information:");
        ProfileService.println(sb, "  currentlyActiveGroupId: " + getActiveGroupId());
        ProfileService.println(sb, "  mActiveAudioOutDevice: " + mActiveAudioOutDevice);
        ProfileService.println(sb, "  mActiveAudioInDevice: " + mActiveAudioInDevice);
        ProfileService.println(
                sb, "  mBroadcastToUnicastFallbackGroup: " + mBroadcastToUnicastFallbackGroup);
        ProfileService.println(
                sb,
                "  mUnicastGroupIdDeactivatedForBroadcastTransition: "
                        + mUnicastGroupIdDeactivatedForBroadcastTransition);
        ProfileService.println(
                sb,
                "  mBroadcastIdDeactivatedForUnicastTransition: "
                        + mBroadcastIdDeactivatedForUnicastTransition);
        if (mGameTrackingList.isEmpty()) {
            ProfileService.println(sb, "  game mode not active");
        } else {
            ProfileService.println(sb, "  game mode is active, gameTrackList:");
            String gameTrackingLog = "";
            for (GameTracker g : mGameTrackingList) {
                gameTrackingLog =
                        gameTrackingLog.concat(
                                "   game uid: "
                                        + g.getUid()
                                        + " state: "
                                        + (g.isBackgroundMonitorActive()
                                                ? "background monitor,\n\n"
                                                : "foreground,\n\n"));
            }
            ProfileService.println(sb, gameTrackingLog);
        }

        ProfileService.println(sb, "  mExposedActiveDevice: " + mExposedActiveDevice);
        ProfileService.println(sb, "  mHfpHandoverDevice:" + mHfpHandoverDevice);
        ProfileService.println(
                sb,
                "  mLeAudioDeviceInactivatedForHfpHandover:"
                        + mLeAudioDeviceInactivatedForHfpHandover);
        ProfileService.println(
                sb,
                "  mLeAudioIsInbandRingtoneSupported:" + mLeAudioInbandRingtoneSupportedByPlatform);

        int numberOfUngroupedDevs = 0;
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> groupEntry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor groupDescriptor = groupEntry.getValue();
                Integer groupId = groupEntry.getKey();
                BluetoothDevice leadDevice = getConnectedGroupLeadDevice(groupId);

                ProfileService.println(sb, "Group: " + groupId);
                ProfileService.println(
                        sb, "  activeState: " + groupDescriptor.getActiveStateString());
                ProfileService.println(sb, "  isConnected: " + groupDescriptor.mIsConnected);
                ProfileService.println(sb, "  mDirection: " + groupDescriptor.mDirection);
                ProfileService.println(sb, "  group lead: " + leadDevice);
                ProfileService.println(
                        sb, "  lost lead device: " + groupDescriptor.mLostLeadDeviceWhileStreaming);
                ProfileService.println(
                        sb, "  mInbandRingtoneEnabled: " + groupDescriptor.mInbandRingtoneEnabled);
                ProfileService.println(
                        sb,
                        "mInactivatedDueToContextType: "
                                + groupDescriptor.mInactivatedDueToContextType);
                ProfileService.println(
                        sb, "mAutoActiveModeEnabled: " + groupDescriptor.mAutoActiveModeEnabled);

                for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> deviceEntry :
                        mDeviceDescriptors.entrySet()) {
                    LeAudioDeviceDescriptor deviceDescriptor = deviceEntry.getValue();
                    if (!Objects.equals(deviceDescriptor.mGroupId, groupId)) {
                        if (deviceDescriptor.mGroupId == LE_AUDIO_GROUP_ID_INVALID) {
                            numberOfUngroupedDevs++;
                        }
                        continue;
                    }

                    if (deviceDescriptor.mStateMachine != null) {
                        deviceDescriptor.mStateMachine.dump(sb);
                    } else {
                        ProfileService.println(sb, "state machine is null");
                    }
                    ProfileService.println(
                            sb, "    mAclConnected: " + deviceDescriptor.mAclConnected);
                    ProfileService.println(
                            sb,
                            "    mDevInbandRingtoneEnabled: "
                                    + deviceDescriptor.mDevInbandRingtoneEnabled);
                    ProfileService.println(
                            sb, "    mSinkAudioLocation: " + deviceDescriptor.mSinkAudioLocation);
                    ProfileService.println(sb, "    mDirection: " + deviceDescriptor.mDirection);
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }

        if (mEventLogger != null) {
            sb.append("\n\n");
            mEventLogger.dump(sb);
        }

        if (numberOfUngroupedDevs > 0) {
            ProfileService.println(sb, "UnGroup devices:");
            for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> entry :
                    mDeviceDescriptors.entrySet()) {
                LeAudioDeviceDescriptor deviceDescriptor = entry.getValue();
                if (deviceDescriptor.mGroupId != LE_AUDIO_GROUP_ID_INVALID) {
                    continue;
                }

                deviceDescriptor.mStateMachine.dump(sb);
                ProfileService.println(sb, "    mAclConnected: " + deviceDescriptor.mAclConnected);
                ProfileService.println(
                        sb,
                        "    mDevInbandRingtoneEnabled: "
                                + deviceDescriptor.mDevInbandRingtoneEnabled);
                ProfileService.println(
                        sb, "    mSinkAudioLocation: " + deviceDescriptor.mSinkAudioLocation);
                ProfileService.println(sb, "    mDirection: " + deviceDescriptor.mDirection);
            }
        }
    }
}
