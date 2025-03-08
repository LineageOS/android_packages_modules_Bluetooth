/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.bluetooth.audio_util;

import android.content.ComponentName;
import android.content.Context;
import android.media.browse.MediaBrowser.MediaItem;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.bluetooth.R;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles API calls to a MediaBrowser.
 *
 * <p>{@link MediaBrowser} APIs work with callbacks only and need a connection beforehand.
 *
 * <p>This class handles the connection then will call the appropriate API and trigger the given
 * callback when it gets the answer from MediaBrowser.
 */
class MediaBrowserWrapper {
    private static final String TAG = MediaBrowserWrapper.class.getSimpleName();

    /**
     * Some devices will continuously request each item in a folder one at a time.
     *
     * <p>This timeout is here to remove the connection between this class and the {@link
     * MediaBrowser} after a certain time without requests from the remote device to browse the
     * player. If the next request happens soon after, the bound will still exist.
     *
     * <p>Note: Previous implementation was keeping a local list of fetched items, this worked at
     * the cost of not having the items actualized if fetching again the same folder.
     */
    private static final Duration BROWSER_DISCONNECT_TIMEOUT = Duration.ofSeconds(5);

    private enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    public interface RequestCallback {
        void run();
    }

    public interface GetPlayerRootCallback {
        void run(String rootId);
    }

    public interface GetFolderItemsCallback {
        void run(String parentId, List<ListItem> items);
    }

    private final MediaBrowser mWrappedBrowser;
    private final Context mContext;
    private final Looper mLooper;
    private final String mPackageName;
    private final Handler mRunHandler;
    private final String mClassName;

    private ConnectionState mBrowserConnectionState = ConnectionState.DISCONNECTED;

    private final ArrayList<RequestCallback> mRequestsList = new ArrayList<>();

    // GetFolderItems also works with a callback, so we need to store all requests made before we
    // got the results and prevent new subscriptions.
    private final Map<String, List<GetFolderItemsCallback>> mSubscribedIds = new HashMap<>();

    private final Runnable mDisconnectRunnable = () -> _disconnect();

    public MediaBrowserWrapper(
            Context context, Looper looper, String packageName, String className) {
        mContext = context;
        mPackageName = packageName;
        mClassName = className;
        mLooper = looper;
        mRunHandler = new Handler(mLooper);
        mWrappedBrowser =
                MediaBrowserFactory.make(
                        context,
                        new ComponentName(packageName, className),
                        new MediaConnectionCallback(),
                        null);
    }

    /** Returns the package name of the {@link MediaBrowser}. */
    public final String getPackageName() {
        Log.v(TAG, "getPackageName: " + mPackageName);
        return mPackageName;
    }

    /** Retrieves the root path of the {@link MediaBrowser}. */
    public void getRootId(GetPlayerRootCallback callback) {
        Log.v(TAG, "getRootId: " + mPackageName);
        browseRequest(
                () -> {
                    if (mBrowserConnectionState != ConnectionState.CONNECTED) {
                        Log.e(TAG, "getRootId: cb triggered but MediaBrowser is not connected.");
                        callback.run("");
                        return;
                    }
                    String rootId = mWrappedBrowser.getRoot();
                    Log.v(TAG, "getRootId for " + mPackageName + ": " + rootId);
                    callback.run(rootId);
                    setDisconnectDelay();
                });
    }

    /** Plays the specified {@code mediaId}. */
    public void playItem(String mediaId) {
        Log.v(TAG, "playItem for " + mPackageName + ": " + mediaId);
        browseRequest(
                () -> {
                    if (mBrowserConnectionState != ConnectionState.CONNECTED) {
                        Log.e(TAG, "playItem: cb triggered but MediaBrowser is not connected.");
                        return;
                    }
                    setDisconnectDelay();
                    // Retrieve the MediaController linked with this MediaBrowser.
                    // Note that the MediaBrowser should be connected for this.
                    MediaController controller =
                            MediaControllerFactory.make(
                                    mContext, mWrappedBrowser.getSessionToken());
                    // Retrieve TransportControls from this MediaController and play mediaId
                    MediaController.TransportControls ctrl = controller.getTransportControls();
                    Log.v(TAG, "playItem for " + mPackageName + ": " + mediaId + " playing.");
                    ctrl.playFromMediaId(mediaId, null);
                });
    }

    /**
     * Retrieves the content of a specific {@link MediaBrowser} path.
     *
     * @param mediaId the path to retrieve content of
     * @param callback to be called when the content list is retrieved
     */
    public void getFolderItems(String mediaId, GetFolderItemsCallback callback) {
        Log.v(TAG, "getFolderItems for " + mPackageName + " and " + mediaId);
        browseRequest(
                () -> {
                    if (mBrowserConnectionState != ConnectionState.CONNECTED) {
                        Log.e(
                                TAG,
                                "getFolderItems: cb triggered but MediaBrowser is not connected.");
                        callback.run(mediaId, Collections.emptyList());
                        return;
                    }
                    setDisconnectDelay();
                    if (mSubscribedIds.containsKey(mediaId)) {
                        Log.v(
                                TAG,
                                "getFolderItems for "
                                        + mPackageName
                                        + " and "
                                        + mediaId
                                        + ": adding callback, already subscribed.");
                        ArrayList<GetFolderItemsCallback> newList =
                                (ArrayList) mSubscribedIds.get(mediaId);
                        newList.add(callback);
                        mSubscribedIds.put(mediaId, newList);
                        return;
                    }
                    Log.v(
                            TAG,
                            "getFolderItems for "
                                    + mPackageName
                                    + " and "
                                    + mediaId
                                    + ": adding callback and subscribing.");
                    // Empty mediaId can cause an exception, retrieve root instead.
                    if (mediaId.isEmpty()) {
                        getRootId(
                                (rootId) -> {
                                    mSubscribedIds.put(
                                            rootId, new ArrayList<>(Arrays.asList(callback)));
                                    mWrappedBrowser.subscribe(
                                            rootId, new BrowserSubscriptionCallback(mediaId));
                                });
                    } else {
                        mSubscribedIds.put(mediaId, new ArrayList<>(Arrays.asList(callback)));
                        mWrappedBrowser.subscribe(
                                mediaId, new BrowserSubscriptionCallback(mediaId));
                    }
                });
    }

    /**
     * Requests information from {@link MediaBrowser}.
     *
     * <p>If the {@link MediaBrowser} this instance wraps around is already connected, calls the
     * callback directly.
     *
     * <p>If it is connecting, adds the callback to the {@code mRequestsList}, to be called once the
     * connection is done.
     *
     * <p>If the connection isn't started, starts it and adds the callback to the {@code
     * mRequestsList}
     */
    private void browseRequest(RequestCallback callback) {
        mRunHandler.post(
                () -> {
                    switch (mBrowserConnectionState) {
                        case CONNECTED:
                            callback.run();
                            break;
                        case DISCONNECTED:
                            connect();
                            mRequestsList.add(callback);
                            break;
                        case CONNECTING:
                            mRequestsList.add(callback);
                            break;
                    }
                });
    }

    /** Connects to the {@link MediaBrowser} this instance wraps around. */
    private void connect() {
        if (mBrowserConnectionState != ConnectionState.DISCONNECTED) {
            Log.e(
                    TAG,
                    "Trying to bind to a player that is not disconnected: "
                            + mBrowserConnectionState);
            return;
        }
        mBrowserConnectionState = ConnectionState.CONNECTING;
        Log.v(TAG, "connect: " + mPackageName + " connecting");
        mWrappedBrowser.connect();
    }

    /** Disconnects from the {@link MediaBrowser} */
    public void disconnect() {
        mRunHandler.post(() -> _disconnect());
    }

    private void _disconnect() {
        mRunHandler.removeCallbacks(mDisconnectRunnable);
        if (mBrowserConnectionState == ConnectionState.DISCONNECTED) {
            Log.e(
                    TAG,
                    "disconnect: Trying to disconnect a player that is not connected: "
                            + mBrowserConnectionState);
            return;
        }
        mBrowserConnectionState = ConnectionState.DISCONNECTED;
        Log.v(TAG, "disconnect: " + mPackageName + " disconnected");
        mWrappedBrowser.disconnect();
    }

    /** Sets the delay before the disconnection from the {@link MediaBrowser} happens. */
    private void setDisconnectDelay() {
        mRunHandler.removeCallbacks(mDisconnectRunnable);
        mRunHandler.postDelayed(mDisconnectRunnable, BROWSER_DISCONNECT_TIMEOUT.toMillis());
    }

    /** Callback for {@link MediaBrowser} connection. */
    private class MediaConnectionCallback extends MediaBrowser.ConnectionCallback {
        @Override
        public void onConnected() {
            mRunHandler.post(
                    () -> {
                        mBrowserConnectionState = ConnectionState.CONNECTED;
                        Log.v(TAG, "MediaConnectionCallback: " + mPackageName + " onConnected");
                        runCallbacks();
                    });
        }

        @Override
        public void onConnectionFailed() {
            mRunHandler.post(
                    () -> {
                        Log.e(
                                TAG,
                                "MediaConnectionCallback: " + mPackageName + " onConnectionFailed");
                        mBrowserConnectionState = ConnectionState.DISCONNECTED;
                        runCallbacks();
                    });
        }

        @Override
        public void onConnectionSuspended() {
            mRunHandler.post(
                    () -> {
                        Log.e(
                                TAG,
                                "MediaConnectionCallback: "
                                        + mPackageName
                                        + " onConnectionSuspended");
                        runCallbacks();
                        mWrappedBrowser.disconnect();
                    });
        }

        /**
         * Executes all the callbacks stored during the connection process
         *
         * <p>This has to run on constructor's Looper.
         */
        private void runCallbacks() {
            for (RequestCallback callback : mRequestsList) {
                callback.run();
            }
            mRequestsList.clear();
        }
    }

    private class BrowserSubscriptionCallback extends MediaBrowser.SubscriptionCallback {

        private final Runnable mTimeoutRunnable;
        private boolean mCallbacksExecuted = false;

        BrowserSubscriptionCallback(String mediaId) {
            mTimeoutRunnable =
                    () -> {
                        executeCallbacks(mediaId, new ArrayList<>());
                    };
            mRunHandler.postDelayed(mTimeoutRunnable, BROWSER_DISCONNECT_TIMEOUT.toMillis());
        }

        private void executeCallbacks(String parentId, List<ListItem> browsableContent) {
            if (mCallbacksExecuted) {
                return;
            }
            mCallbacksExecuted = true;
            mRunHandler.removeCallbacks(mTimeoutRunnable);
            for (GetFolderItemsCallback callback : mSubscribedIds.get(parentId)) {
                Log.v(
                        TAG,
                        "getFolderItems for "
                                + mPackageName
                                + " and "
                                + parentId
                                + ": callback called with "
                                + browsableContent.size()
                                + " items.");
                callback.run(parentId, browsableContent);
            }

            mSubscribedIds.remove(parentId);
            mWrappedBrowser.unsubscribe(parentId);
        }

        @Override
        public void onChildrenLoaded(String parentId, List<MediaItem> children) {
            List<ListItem> browsableContent = new ArrayList<>();

            for (MediaItem item : children) {
                if (item.isBrowsable()) {
                    String title = item.getDescription().getTitle().toString();
                    if (title.isEmpty()) {
                        title = mContext.getString(R.string.not_provided);
                    }
                    Folder f = new Folder(item.getMediaId(), false, title);
                    browsableContent.add(new ListItem(f));
                } else {
                    Metadata data = Util.toMetadata(mContext, item);
                    if (Util.isEmptyData(data)) {
                        continue;
                    }
                    browsableContent.add(new ListItem(data));
                }
            }

            mRunHandler.post(() -> executeCallbacks(parentId, browsableContent));
        }

        @Override
        public void onError(String parentId) {
            mRunHandler.post(() -> executeCallbacks(parentId, new ArrayList<>()));
        }

        @Override
        public Handler getTimeoutHandler() {
            return mRunHandler;
        }
    }

    @Override
    public String toString() {
        return "Browsable Package & Class Name: " + mPackageName + " " + mClassName + "\n";
    }
}
