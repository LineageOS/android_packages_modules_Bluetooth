/*
 * Copyright 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.ComponentCaller;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.PeriodicAdvertisingCallback;
import android.bluetooth.le.PeriodicAdvertisingManager;
import android.bluetooth.le.ScanResult;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.provider.Telephony;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.bluetooth.bass_client.BassClientPeriodicAdvertisingManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.obex.HeaderSet;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

/** Proxy class for method calls to help with unit testing */
// TODO: Remove this entire class, as it is abused and provide helper to call framework code which
// should be avoided
public class BluetoothMethodProxy {
    private static final String TAG = BluetoothMethodProxy.class.getSimpleName();

    private static final Object INSTANCE_LOCK = new Object();
    private static BluetoothMethodProxy sInstance;

    private BluetoothMethodProxy() {}

    /**
     * Get the singleton instance of proxy
     *
     * @return the singleton instance, guaranteed not null
     */
    public static BluetoothMethodProxy getInstance() {
        synchronized (INSTANCE_LOCK) {
            if (sInstance == null) {
                sInstance = new BluetoothMethodProxy();
            }
        }
        return sInstance;
    }

    /**
     * Allow unit tests to substitute BluetoothPbapMethodCallProxy with a test instance
     *
     * @param proxy a test instance of the BluetoothPbapMethodCallProxy
     */
    @VisibleForTesting
    public static void setInstanceForTesting(BluetoothMethodProxy proxy) {
        Utils.enforceInstrumentationTestMode();
        synchronized (INSTANCE_LOCK) {
            Log.d(TAG, "setInstanceForTesting(), set to " + proxy);
            sInstance = proxy;
        }
    }

    /** Proxies {@link ContentResolver#query(Uri, String[], String, String[], String)}. */
    public Cursor contentResolverQuery(
            ContentResolver contentResolver,
            final Uri contentUri,
            final String[] projection,
            final String selection,
            final String[] selectionArgs,
            final String sortOrder) {
        try {
            return contentResolver.query(
                    contentUri, projection, selection, selectionArgs, sortOrder);
        } catch (Exception e) {
            Log.e(TAG, "Exception happened" + e + "\n" + Log.getStackTraceString(new Throwable()));
            return null;
        }
    }

    /** Proxies {@link ContentResolver#query(Uri, String[], Bundle, CancellationSignal)}. */
    public Cursor contentResolverQuery(
            ContentResolver contentResolver,
            final Uri contentUri,
            final String[] projection,
            final Bundle queryArgs,
            final CancellationSignal cancellationSignal) {
        try {
            return contentResolver.query(contentUri, projection, queryArgs, cancellationSignal);
        } catch (Exception e) {
            Log.e(TAG, "Exception happened " + e + "\n" + Log.getStackTraceString(new Throwable()));
            return null;
        }
    }

    /** Proxies {@link ContentResolver#insert(Uri, ContentValues)}. */
    public Uri contentResolverInsert(
            ContentResolver contentResolver,
            final Uri contentUri,
            final ContentValues contentValues) {
        return contentResolver.insert(contentUri, contentValues);
    }

    /** Proxies {@link ContentResolver#update(Uri, ContentValues, String, String[])}. */
    public int contentResolverUpdate(
            ContentResolver contentResolver,
            final Uri contentUri,
            final ContentValues contentValues,
            String where,
            String[] selectionArgs) {
        return contentResolver.update(contentUri, contentValues, where, selectionArgs);
    }

    /** Proxies {@link ContentResolver#delete(Uri, String, String[])}. */
    public int contentResolverDelete(
            ContentResolver contentResolver,
            final Uri url,
            final String where,
            final String[] selectionArgs) {
        return contentResolver.delete(url, where, selectionArgs);
    }

    /** Proxies {@link BluetoothAdapter#isEnabled()}. */
    public boolean bluetoothAdapterIsEnabled(BluetoothAdapter adapter) {
        return adapter.isEnabled();
    }

    /**
     * Proxies {@link BluetoothAdapter#getRemoteLeDevice(String, int)} on default Bluetooth Adapter.
     */
    public BluetoothDevice getDefaultAdapterRemoteLeDevice(String address, int addressType) {
        return BluetoothAdapter.getDefaultAdapter().getRemoteLeDevice(address, addressType);
    }

    /** Proxies {@link ContentResolver#openFileDescriptor(Uri, String)}. */
    public ParcelFileDescriptor contentResolverOpenFileDescriptor(
            ContentResolver contentResolver, final Uri uri, final String mode)
            throws FileNotFoundException {
        return contentResolver.openFileDescriptor(uri, mode);
    }

    /** Proxies {@link ContentResolver#openAssetFileDescriptor(Uri, String)}. */
    public AssetFileDescriptor contentResolverOpenAssetFileDescriptor(
            ContentResolver contentResolver, final Uri uri, final String mode)
            throws FileNotFoundException {
        return contentResolver.openAssetFileDescriptor(uri, mode);
    }

    /** Proxies {@link ContentResolver#openInputStream(Uri)}. */
    public InputStream contentResolverOpenInputStream(
            ContentResolver contentResolver, final Uri uri) throws FileNotFoundException {
        return contentResolver.openInputStream(uri);
    }

    /** Proxies {@link ContentResolver#acquireUnstableContentProviderClient(String)}. */
    public ContentProviderClient contentResolverAcquireUnstableContentProviderClient(
            ContentResolver contentResolver, @NonNull String name) {
        return contentResolver.acquireUnstableContentProviderClient(name);
    }

    /** Proxies {@link ContentResolver#openOutputStream(Uri)}. */
    public OutputStream contentResolverOpenOutputStream(ContentResolver contentResolver, Uri uri)
            throws FileNotFoundException {
        return contentResolver.openOutputStream(uri);
    }

    /** Proxies {@link Context#sendBroadcast(Intent)}. */
    @SuppressLint("AndroidFrameworkRequiresPermission") // only intent is ACTION_OPEN
    public void contextSendBroadcast(Context context, Intent intent) {
        context.sendBroadcast(intent);
    }

    /** Proxies {@link Handler#sendEmptyMessage(int)}}. */
    public boolean handlerSendEmptyMessage(Handler handler, final int what) {
        return handler.sendEmptyMessage(what);
    }

    /** Proxies {@link Handler#sendMessageDelayed(Message, long)}. */
    public boolean handlerSendMessageDelayed(
            Handler handler, final int what, final long delayMillis) {
        return handler.sendMessageDelayed(handler.obtainMessage(what), delayMillis);
    }

    /** Proxies {@link HeaderSet#getHeader}. */
    public Object getHeader(HeaderSet headerSet, int headerId) throws IOException {
        return headerSet.getHeader(headerId);
    }

    /** Proxies {@link Context#getSystemService(Class)}. */
    public <T> T getSystemService(Context context, Class<T> serviceClass) {
        return context.getSystemService(serviceClass);
    }

    /** Proxies {@link Telephony.Threads#getOrCreateThreadId(Context, Set <String>)}. */
    public long telephonyGetOrCreateThreadId(Context context, Set<String> recipients) {
        return Telephony.Threads.getOrCreateThreadId(context, recipients);
    }

    /**
     * Proxies {@link
     * BassClientPeriodicAdvertisingManager#initializePeriodicAdvertisingManagerOnDefaultAdapter}.
     */
    public boolean initializePeriodicAdvertisingManagerOnDefaultAdapter() {
        return BassClientPeriodicAdvertisingManager
                .initializePeriodicAdvertisingManagerOnDefaultAdapter();
    }

    /**
     * Proxies {@link PeriodicAdvertisingManager#registerSync(ScanResult, int, int,
     * PeriodicAdvertisingCallback, Handler)}.
     */
    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786
    public void periodicAdvertisingManagerRegisterSync(
            PeriodicAdvertisingManager manager,
            ScanResult scanResult,
            int skip,
            int timeout,
            PeriodicAdvertisingCallback callback,
            Handler handler) {
        manager.registerSync(scanResult, skip, timeout, callback, handler);
    }

    /** Proxies {@link PeriodicAdvertisingManager#unregisterSync(PeriodicAdvertisingCallback)}. */
    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786
    public void periodicAdvertisingManagerUnregisterSync(
            PeriodicAdvertisingManager manager, PeriodicAdvertisingCallback callback) {
        manager.unregisterSync(callback);
    }

    /** Proxies {@link PeriodicAdvertisingManager#transferSync}. */
    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786
    public void periodicAdvertisingManagerTransferSync(
            PeriodicAdvertisingManager manager,
            BluetoothDevice bda,
            int serviceData,
            int syncHandle) {
        manager.transferSync(bda, serviceData, syncHandle);
    }

    /** Proxies {@link PeriodicAdvertisingManager#transferSetInfo}. */
    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786
    public void periodicAdvertisingManagerTransferSetInfo(
            PeriodicAdvertisingManager manager,
            BluetoothDevice bda,
            int serviceData,
            int advHandle,
            PeriodicAdvertisingCallback callback) {
        manager.transferSetInfo(bda, serviceData, advHandle, callback);
    }

    /** Proxies {@link Thread#start()}. */
    public void threadStart(Thread thread) {
        thread.start();
    }

    /** Proxies {@link HandlerThread#getLooper()}. */
    public Looper handlerThreadGetLooper(HandlerThread handlerThread) {
        return handlerThread.getLooper();
    }

    /** Peoziws {@link MediaSessionManager#getActiveSessions} */
    public @NonNull List<MediaController> mediaSessionManagerGetActiveSessions(
            MediaSessionManager manager) {
        return manager.getActiveSessions(null);
    }

    /** Proxies {@link ComponentCaller#checkContentUriPermission(Uri, int)}. } */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public int componentCallerCheckContentUriPermission(
            ComponentCaller caller, Uri uri, int modeFlags) {
        return caller.checkContentUriPermission(uri, modeFlags);
    }
}
