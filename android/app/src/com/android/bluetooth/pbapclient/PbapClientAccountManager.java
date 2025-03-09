/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

import static java.util.Objects.requireNonNull;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.UserManager;
import android.util.Log;

import com.android.bluetooth.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This class abstracts away interactions and management of the AccountManager Account objects that
 * we need to store contacts and call logs on Android. This object provides functions to get/create
 * an account, as well as remove or cleanup accounts.
 *
 * <p>Most AccountManager functions we want to use require the caller (us) to have a signature match
 * with the authenticator that owns the specified account. AccountManager knowing this is contingent
 * on our AuthenticationService being started (Our PbapClientAccountAuthenticatorService, which owns
 * our PbapClientAccountAuthenticator) and AccountManagerService being notified of it so it can
 * update its cache. This happens asynchronously and can sometimes take as long as 30 seconds after
 * stack startup to be available. This object also abstracts this issue away, handling the timing
 * and notifying clients when accounts are ready.
 *
 * <p>Once the account list has been initialized, clients can begin making calls to add, remove and
 * list accounts.
 */
class PbapClientAccountManager {
    private static final String TAG = PbapClientAccountManager.class.getSimpleName();

    private final Context mContext;
    private final AccountManager mAccountManager;
    private final UserManager mUserManager;
    private final String mAccountType;
    private final AccountManagerReceiver mAccountManagerReceiver = new AccountManagerReceiver();

    private HandlerThread mHandlerThread = null;
    private AccountHandler mAccountHandler = null;

    private final Object mAccountLock = new Object();

    @GuardedBy("mAccountLock")
    private final Set<Account> mAccounts = new HashSet<Account>();

    private boolean mIsUserReady = false;
    private volatile boolean mAccountsInitialized = false;

    // For sending events back to the object owner
    private final Callback mCallback;

    /** A Callback interface so clients can receive structured events from this account manager */
    interface Callback {
        /**
         * Receive account visibility updates
         *
         * @param oldAccounts The list of previously available accounts, or null if this is the
         *     first account update after initialization
         * @param newAccounts The list of newly available accounts
         */
        void onAccountsChanged(List<Account> oldAccounts, List<Account> newAccounts);
    }

    PbapClientAccountManager(Context context, Callback callback) {
        this(context, null, callback);
    }

    @VisibleForTesting
    PbapClientAccountManager(Context context, HandlerThread handlerThread, Callback callback) {
        mContext = requireNonNull(context);
        mAccountManager = mContext.getSystemService(AccountManager.class);
        mUserManager = mContext.getSystemService(UserManager.class);
        mAccountType = mContext.getResources().getString(R.string.pbap_client_account_type);
        mHandlerThread = handlerThread;
        mCallback = callback;
    }

    public void start() {
        Log.d(TAG, "start()");

        mAccountsInitialized = false;
        synchronized (mAccountLock) {
            mAccounts.clear();
        }

        // Allow injecting a TestLooper
        if (mHandlerThread == null) {
            mHandlerThread = new HandlerThread(TAG);
        }

        mHandlerThread.start();
        mAccountHandler = new AccountHandler(mHandlerThread.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mAccountManagerReceiver, filter);

        if (isUserUnlocked()) {
            mAccountHandler.obtainMessage(AccountHandler.MSG_USER_UNLOCKED).sendToTarget();
        }
    }

    public void stop() {
        Log.d(TAG, "stop()");

        mContext.unregisterReceiver(mAccountManagerReceiver);
        if (mAccountHandler != null) {
            mAccountHandler.removeCallbacksAndMessages(null);
            mAccountHandler = null;
        }

        if (mHandlerThread != null) {
            mHandlerThread.quit();
            mHandlerThread = null;
        }

        mAccountsInitialized = false;
    }

    /**
     * Determine if this object has completed initialization of the accounts list.
     *
     * <p>Initialization happens once the user is unlock and our account type is recognized by the
     * AccountManager framework.
     *
     * @return True if the accounts list has been initialized, false otherwise.
     */
    public boolean isAccountTypeInitialized() {
        return mAccountsInitialized;
    }

    /**
     * Get a well-formed Pbap Client based Account object to add for a given remote device.
     *
     * <p>This account should be used when making storage calls. Be sure the account is added and
     * exists before using it for storage calls.
     *
     * @param device The remote device you would like a PBAP Client account for
     * @return an Account object corresponding to the given remote device
     */
    public Account getAccountForDevice(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        return new Account(device.getAddress(), mAccountType);
    }

    /**
     * Get the list of available PBAP Client accounts
     *
     * @return A list of all available PBAP Client based accounts on this device
     */
    public List<Account> getAccounts() {
        if (!mAccountsInitialized) {
            Log.w(TAG, "getAccounts(): Not initialized");
            return Collections.emptyList();
        }
        synchronized (mAccountLock) {
            return Collections.unmodifiableList(new ArrayList<>(mAccounts));
        }
    }

    /**
     * Request for an account to be added
     *
     * <p>Storage must be initialized before calls to this function will be successful
     *
     * @param account The account to add
     * @return True if the account is successfully added, False otherwise
     */
    public boolean addAccount(Account account) {
        if (!mAccountsInitialized) {
            Log.w(TAG, "addAccount(account=" + account + "): Cannot add account, not initialized");
            return false;
        }
        synchronized (mAccountLock) {
            List<Account> oldAccounts = new ArrayList<>(mAccounts);
            if (addAccountInternal(account)) {
                notifyAccountsChanged(oldAccounts, new ArrayList<>(mAccounts));
                return true;
            }
            return false;
        }
    }

    /**
     * Request for an account to be removed
     *
     * <p>Storage must be initialized before calls to this function will be successful
     *
     * @param account The account to remove
     * @return True if the account is successfully removed, False otherwise
     */
    public boolean removeAccount(Account account) {
        if (!mAccountsInitialized) {
            Log.w(
                    TAG,
                    "removeAccount(account="
                            + account
                            + "): Cannot remove account, not initialized");
            return false;
        }
        synchronized (mAccountLock) {
            List<Account> oldAccounts = new ArrayList<>(mAccounts);
            if (removeAccountInternal(account)) {
                notifyAccountsChanged(oldAccounts, new ArrayList<>(mAccounts));
                return true;
            }
            return false;
        }
    }

    /** Receive user lifecycle events and forward them to the handler for processing */
    private class AccountManagerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(TAG, "onReceive action=" + action);
            if (action.equals(Intent.ACTION_USER_UNLOCKED)) {
                mAccountHandler.obtainMessage(AccountHandler.MSG_USER_UNLOCKED).sendToTarget();
            }
        }
    }

    /**
     * A handler to serialize account events. This allows us to wait for our authentication service
     * to be available until we interact with accounts, and then safely create and remove accounts
     * as needed.
     */
    private class AccountHandler extends Handler {
        // There's an ~1-2 second latency between when our Authentication service is set as
        // available to the system and when the Authentication/Account framework code will recognize
        // it and allow us to alter accounts. In lieu of the Accounts team dealing with this race
        // condition, we're going to periodically poll over 3 seconds until our accounts are
        // visible, remove old accounts, and then notify device state machines that they can create
        // accounts and download contacts.
        //
        // TODO(233361365): Remove this pattern when the framework solves their race condition
        private static final int ACCOUNT_ADD_RETRY_MS = 1000;

        public static final int MSG_USER_UNLOCKED = 0;
        public static final int MSG_ACCOUNT_CHECK = 1;

        AccountHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.v(TAG, "Process message=" + messageToString(msg.what));
            switch (msg.what) {
                case MSG_USER_UNLOCKED:
                    handleUserUnlocked();
                    break;
                case MSG_ACCOUNT_CHECK:
                    handleAccountCheck();
                    break;
                default:
                    Log.e(TAG, "received an unknown message : " + msg.what);
            }
        }

        private void handleUserUnlocked() {
            if (mIsUserReady) {
                Log.i(TAG, "Notified user unlocked, but we've already processed this event. Skip");
                return;
            }

            Log.i(TAG, "User is unlocked. Begin account check process");
            mIsUserReady = true;
            this.obtainMessage(MSG_ACCOUNT_CHECK).sendToTarget();
        }

        private void handleAccountCheck() {
            if (mAccountsInitialized) {
                Log.w(TAG, "Accounts already initialized. Skipping");
                return;
            }

            if (isAccountAuthenticationServiceReady()) {
                Log.d(TAG, "Account type ready to be interacted with. Initialize account list");

                Account[] availableAccounts = mAccountManager.getAccountsByType(mAccountType);
                synchronized (mAccountLock) {
                    for (Account account : availableAccounts) {
                        Log.i(TAG, "Loaded saved account, account=" + account);
                        mAccounts.add(account);
                    }

                    mAccountsInitialized = true;

                    Log.d(TAG, "Accounts list initialized");
                    notifyAccountsChanged(null, new ArrayList<>(mAccounts));
                }
            } else {
                Log.d(TAG, "Accounts not ready. Check again in " + ACCOUNT_ADD_RETRY_MS + "ms");
                sendMessageDelayed(obtainMessage(MSG_ACCOUNT_CHECK), ACCOUNT_ADD_RETRY_MS);
            }
        }

        private static String messageToString(int msg) {
            switch (msg) {
                case MSG_USER_UNLOCKED:
                    return "MSG_USER_UNLOCKED";
                case MSG_ACCOUNT_CHECK:
                    return "MSG_ACCOUNT_CHECK";
                default:
                    return "MSG_RESERVED_" + msg;
            }
        }
    }

    /**
     * Determine if the user is unlocked
     *
     * <p>AccountManager functionality doesn't work until the user is unlocked. We need to hold our
     * calls until we know the user is unlocked.
     *
     * @return True if the use it unlocked, False otherwise
     */
    private boolean isUserUnlocked() {
        return mUserManager.isUserUnlocked();
    }

    /**
     * Determine if we're able to interact with our own account type
     *
     * <p>We're able to interact with our account when our account service is up and the
     * AccountManagerService has finished updating itself such that it also knows our service is
     * ready. The AccountManager framework doesn't have a good way for us to know _exactly_ when
     * this is, so the best we can do is try to interact with our account type and see if it works.
     *
     * <p>We use a fake device address and our account type here to see if our account is visible
     * yet.
     *
     * <p>This function is used in conjunction with the handler and a polling scheme to see
     * determine when we're finally ready.
     *
     * <p>Note: that this function uses the same restrictions as the other add and remove functions,
     * but is *also* available to all system apps instead of throwing a runtime SecurityException.
     * AccountManagerService makes an !isSystemUid check before throwing.
     *
     * @return True if our PBAP Client Account type is ready to use, False otherwise.
     */
    private boolean isAccountAuthenticationServiceReady() {
        Account account = new Account("00:00:00:00:00:00", mAccountType);
        int visibility = mAccountManager.getAccountVisibility(account, mContext.getPackageName());
        Log.d(TAG, "Checking visibility, visibility=" + visibility);
        return visibility == AccountManager.VISIBILITY_VISIBLE
                || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;
    }

    /**
     * Explicitly add an account. Returns true is successful, false otherwise.
     *
     * <p>Any exceptions generated cause this function to fail silently. In particular,
     * SecurityExceptions due to the fact that our authentication service isn't recognized by the
     * AccountManager framework yet are dropped. Our handler is setup to make it so we shouldn't
     * make these calls unless we know AccountManager knows of us though.
     *
     * @param account The account to add
     * @return True on success, false otherwise
     */
    private boolean addAccountInternal(Account account) {
        try {
            synchronized (mAccountLock) {
                if (mAccountManager.addAccountExplicitly(account, null, null)) {
                    mAccounts.add(account);
                    Log.i(TAG, "Added account=" + account);
                    return true;
                }
                Log.w(TAG, "Failed to add account=" + account);
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception while trying to add account=" + account, e);
            return false;
        }
    }

    /**
     * Explicitly remove an account. Returns true is successful, false otherwise.
     *
     * <p>Any exceptions generated cause this function to fail silently. In particular,
     * SecurityExceptions due to the fact that our authentication service isn't recognized by the
     * AccountManager framework yet are dropped. Our handler is setup to make it so we shouldn't
     * make these calls unless we know AccountManager knows of us though.
     *
     * @param account the account to explicitly remove
     * @return True on success, false otherwise
     */
    private boolean removeAccountInternal(Account account) {
        try {
            synchronized (mAccountLock) {
                if (mAccountManager.removeAccountExplicitly(account)) {
                    mAccounts.remove(account);
                    Log.i(TAG, "Removed account=" + account);
                    return true;
                }
                Log.w(TAG, "Failed to remove account=" + account);
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception while trying to remove account=" + account, e);
            return false;
        }
    }

    /**
     * Notify all client callbacks that the set of accounts has changed
     *
     * @param oldAccounts The previous list of accounts available, or null if this is the first
     *     update
     * @param newAccounts The new list of accounts available
     */
    private void notifyAccountsChanged(List<Account> oldAccounts, List<Account> newAccounts) {
        Log.v(TAG, "notifyAccountsChanged, old=" + oldAccounts + ", new=" + newAccounts);
        if (mCallback != null) {
            mCallback.onAccountsChanged(oldAccounts, newAccounts);
        }
    }

    /** Get a debug dump of this class, containing the accounts on the device */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(TAG).append(":\n");
        sb.append("        Account Type: ").append(mAccountType).append("\n");
        sb.append("        User Unlocked: ").append(isUserUnlocked()).append("\n");
        sb.append("        Account Type Ready: ")
                .append(isAccountAuthenticationServiceReady())
                .append("\n");
        sb.append("        Accounts Initialized: ").append(mAccountsInitialized).append("\n");
        sb.append("        Accounts:\n");
        for (Account account : getAccounts()) {
            sb.append("          ").append(account).append("\n");
        }
        return sb.toString();
    }
}
