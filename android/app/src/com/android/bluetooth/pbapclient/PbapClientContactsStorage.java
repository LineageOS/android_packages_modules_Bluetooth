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

import android.accounts.Account;
import android.bluetooth.BluetoothDevice;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntry.PhoneData;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class owns the interface to the contacts and call history storage mechanism, namely the
 * Contacts DB and Contacts Provider. It also owns the list of cached metadata and facilitates the
 * management of the AccountManagerService accounts that are required to store contacts on the
 * device. It provides functions to allow connected devices to create and manage accounts and store
 * and cache contacts and call logs.
 *
 * <p>Exactly one of these objects should exist, created by the PbapClientService at start up.
 *
 * <p>All contacts on Android are stored against an AccountManager Framework Account object. These
 * Accounts should be created by devices upon connecting. This Account is used on many of the
 * functions, in order to target the correct device's contacts.
 */
class PbapClientContactsStorage {
    private static final String TAG = PbapClientContactsStorage.class.getSimpleName();

    private static final int CONTACTS_INSERT_BATCH_SIZE = 250;

    private static final String CALL_LOG_TIMESTAMP_PROPERTY = "X-IRMC-CALL-DATETIME";
    private static final String TIMESTAMP_FORMAT = "yyyyMMdd'T'HHmmss";

    private final Context mContext;
    private final PbapClientAccountManager mAccountManager;

    private volatile boolean mStorageInitialized = false;

    private final List<Callback> mCallbacks = new ArrayList<Callback>();

    /** A Callback interface so clients can receive structured events about PBAP Contacts Storage */
    interface Callback {
        /**
         * Invoked when storage is initialized and ready for interaction
         *
         * <p>Storage related functions may not work before storage is ready.
         */
        void onStorageReady();

        /**
         * Receive account visibility updates
         *
         * @param oldAccounts The list of previously available accounts
         * @param newAccounts The list of newly available accounts
         */
        void onStorageAccountsChanged(List<Account> oldAccounts, List<Account> newAccounts);
    }

    class PbapClientAccountManagerCallback implements PbapClientAccountManager.Callback {
        @Override
        public void onAccountsChanged(List<Account> oldAccounts, List<Account> newAccounts) {
            if (oldAccounts == null) {
                Log.d(TAG, "Storage accounts initialized, accounts=" + newAccounts);
                initialize(newAccounts);
                notifyStorageReady();
                notifyStorageAccountsChanged(
                        Collections.emptyList(), mAccountManager.getAccounts());
            } else if (mStorageInitialized) {
                Log.d(TAG, "Storage accounts changed, old=" + oldAccounts + ", new=" + newAccounts);
                notifyStorageAccountsChanged(oldAccounts, newAccounts);
            } else {
                Log.d(TAG, "Storage not fully initialized, dropping accounts changed event");
            }
        }
    }

    PbapClientContactsStorage(Context context) {
        mContext = context;
        mAccountManager =
                new PbapClientAccountManager(context, new PbapClientAccountManagerCallback());
    }

    @VisibleForTesting
    PbapClientContactsStorage(Context context, PbapClientAccountManager accountManager) {
        mContext = context;
        mAccountManager = accountManager;
    }

    public void start() {
        mStorageInitialized = false;
        mAccountManager.start();
    }

    public void stop() {
        mAccountManager.stop();
    }

    // *********************************************************************************************
    // * Initialization
    // *********************************************************************************************

    /**
     * Determine if storage is ready or not.
     *
     * <p>Many storage functions won't work before storage is ready to be interacted with. Use the
     * callback interface to be told when storage is ready if it's not ready upon calling this.
     *
     * @return True is storage is ready, false otherwise.
     */
    public boolean isStorageReady() {
        return mStorageInitialized;
    }

    /**
     * Initialize storage with a set of accounts.
     *
     * <p>This function receives a set of accounts that our PBAP Client implementation knows about
     * and initializes our storage state based on this account list, using the following
     * rules/steps:
     *
     * <p>1. CHECK ACCOUNTS: Previous accounts should not exist. Delete them and all associated data
     *
     * <p>These rules help ensure that we clean up accounts that might persist after an ungraceful
     * shutdown
     *
     * @param accounts The list of accounts that exist following start up of the account manager
     */
    private void initialize(List<Account> accounts) {
        Log.i(TAG, "initialize(accounts=" + accounts + ")");
        if (mStorageInitialized) {
            Log.w(TAG, "initialize(accounts=" + accounts + "): Already initialized. Skipping");
            return;
        }

        for (Account account : accounts) {
            Log.w(TAG, "initialize(): Remove pre-existing account=" + account);
            mAccountManager.removeAccount(account);
        }

        mStorageInitialized = true;
    }

    // *********************************************************************************************
    // * Storage Accounts
    // *********************************************************************************************

    public Account getStorageAccountForDevice(BluetoothDevice device) {
        return mAccountManager.getAccountForDevice(device);
    }

    public List<Account> getStorageAccounts() {
        return mAccountManager.getAccounts();
    }

    public boolean addAccount(Account account) {
        return mAccountManager.addAccount(account);
    }

    public boolean removeAccount(Account account) {
        return mAccountManager.removeAccount(account);
    }

    // *********************************************************************************************
    // * Contacts DB Operations
    // *********************************************************************************************

    /** Insert contacts into the Contacts DB from a remote device's favorites phonebook */
    public boolean insertFavorites(Account account, List<VCardEntry> contacts) {
        if (contacts == null) {
            return false;
        }

        for (VCardEntry contact : contacts) {
            contact.setStarred(true);
        }
        return insertContacts(account, PbapPhonebook.FAVORITES_PATH, contacts);
    }

    /** Insert contacts into the Contacts DB from a remote device's local phonebook */
    public boolean insertLocalContacts(Account account, List<VCardEntry> contacts) {
        return insertContacts(account, PbapPhonebook.LOCAL_PHONEBOOK_PATH, contacts);
    }

    /** Insert contacts into the Contacts DB from a remote device's sim local phonebook */
    public boolean insertSimContacts(Account account, List<VCardEntry> contacts) {
        return insertContacts(account, PbapPhonebook.SIM_PHONEBOOK_PATH, contacts);
    }

    /**
     * Insert a list of contacts into the Contacts Provider/Contacts DB
     *
     * <p>This function also associates the phonebook metadata with the contact for easy
     * per-phonebook cleanup operations.
     *
     * <p>Contacts are inserted in smaller batches so they can be loaded in chunks as opposed to
     * shown all at once in the UI. This also prevents us from hitting the binder transaction limit.
     *
     * @param account The account to insert contacts against
     * @param phonebook The phonebook these contacts belong to
     * @param contacts The list of contacts to insert
     */
    private boolean insertContacts(Account account, String phonebook, List<VCardEntry> contacts) {
        if (!mStorageInitialized) {
            Log.w(TAG, "insertContacts: Failed, storage not ready");
            return false;
        }

        if (account == null) {
            Log.e(TAG, "insertContacts: account is null");
            return false;
        }

        if (contacts == null || contacts.size() == 0) {
            Log.e(TAG, "insertContacts: contacts provided are null or empty");
            return false;
        }

        try {
            Log.i(
                    TAG,
                    "insertContacts: inserting contacts, account="
                            + account
                            + ", count="
                            + contacts.size()
                            + ", for phonebook="
                            + phonebook);

            ContentResolver contactsProvider = mContext.getContentResolver();
            ArrayList<ContentProviderOperation> operations = new ArrayList<>();

            // Group insert operations together to minimize inter process communication and improve
            // processing time.
            for (VCardEntry contact : contacts) {
                if (Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "Interrupted during insert");
                    break;
                }

                // Associate the storage account with this contact
                contact.setAccount(account);

                // Append current vcard to list of insert operations.
                int numberOfOperations = operations.size();
                constructInsertOperationsForContact(contact, operations, contactsProvider);

                if (operations.size() >= CONTACTS_INSERT_BATCH_SIZE) {
                    Log.i(
                            TAG,
                            "insertContacts: batch full, operations.size()="
                                    + operations.size()
                                    + ", batch_size="
                                    + CONTACTS_INSERT_BATCH_SIZE);

                    // If we have exceeded the limit to the insert operation remove the latest vcard
                    // and submit.
                    operations.subList(numberOfOperations, operations.size()).clear();

                    contactsProvider.applyBatch(ContactsContract.AUTHORITY, operations);

                    // Re-add the current contact operation(s) to the list
                    operations =
                            constructInsertOperationsForContact(contact, null, contactsProvider);

                    Log.i(
                            TAG,
                            "insertContacts: batch complete, operations.size()="
                                    + operations.size());
                }
            }

            // Apply any unsubmitted vcards
            if (operations.size() > 0) {
                contactsProvider.applyBatch(ContactsContract.AUTHORITY, operations);
                operations.clear();
            }
            Log.i(TAG, "insertContacts: insert complete, count=" + contacts.size());
        } catch (OperationApplicationException | RemoteException | NumberFormatException e) {
            Log.e(TAG, "insertContacts: Exception occurred while processing phonebook pull: ", e);
            return false;
        }
        return true;
    }

    @SuppressWarnings("NonApiType") // For convenience, as applyBatch above takes an ArrayList above
    private static ArrayList<ContentProviderOperation> constructInsertOperationsForContact(
            VCardEntry contact,
            ArrayList<ContentProviderOperation> operations,
            ContentResolver contactsProvider) {
        operations = contact.constructInsertOperations(contactsProvider, operations);
        return operations;
    }

    public boolean removeAllContacts(Account account) {
        if (account == null) {
            Log.e(TAG, "removeAllContacts: account is null");
            return false;
        }

        Log.i(TAG, "removeAllContacts: requested for account=" + account);
        Uri contactsToDeleteUri =
                RawContacts.CONTENT_URI
                        .buildUpon()
                        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
                        .build();

        try {
            mContext.getContentResolver().delete(contactsToDeleteUri, null);
        } catch (IllegalArgumentException e) {
            Log.w(
                    TAG,
                    "removeAllContacts(uri="
                            + contactsToDeleteUri
                            + "): Contacts could not be deleted",
                    e);
            return false;
        }
        return true;
    }

    /**
     * Insert call logs into the incoming calls table
     *
     * @param account The account to insert call logs against
     * @param history The call history to insert
     */
    public boolean insertIncomingCallHistory(Account account, List<VCardEntry> history) {
        return insertCallHistory(account, CallLog.Calls.INCOMING_TYPE, history);
    }

    /**
     * Insert call logs into the outgoing calls table
     *
     * @param account The account to insert call logs against
     * @param history The call history to insert
     */
    public boolean insertOutgoingCallHistory(Account account, List<VCardEntry> history) {
        return insertCallHistory(account, CallLog.Calls.OUTGOING_TYPE, history);
    }

    /**
     * Insert call logs into the missed calls table
     *
     * @param account The account to insert call logs against
     * @param history The call history to insert
     */
    public boolean insertMissedCallHistory(Account account, List<VCardEntry> history) {
        return insertCallHistory(account, CallLog.Calls.MISSED_TYPE, history);
    }

    /**
     * Insert call history entries of a given type
     *
     * <p>These call logs are inserted in smaller batches so they can be loaded in chunks as opposed
     * to shown all at once in the UI. This also prevents us from hitting the binder transaction
     * limit
     *
     * @param account The account to insert call logs against
     * @param type The type of call provided
     * @param history The list of calls to add
     * @return True if successful, False otherwise
     */
    private boolean insertCallHistory(Account account, int type, List<VCardEntry> history) {
        if (!mStorageInitialized) {
            Log.w(TAG, "insertCallHistory: Failed, storage not ready");
            return false;
        }

        if (account == null) {
            Log.e(TAG, "insertCallHistory: Account is null");
            return false;
        }

        if (history == null || history.size() == 0) {
            Log.e(TAG, "insertCallHistory: No entries to insert");
            return false;
        }

        if (type != CallLog.Calls.INCOMING_TYPE
                && type != CallLog.Calls.OUTGOING_TYPE
                && type != CallLog.Calls.MISSED_TYPE) {
            Log.e(TAG, "insertCallHistory: Unknown type=" + type);
            return false;
        }

        try {
            Log.i(
                    TAG,
                    "insertCallHistory: inserting call history, account="
                            + account
                            + ", type="
                            + type
                            + ", count="
                            + history.size());

            ContentResolver contactsProvider = mContext.getContentResolver();
            ArrayList<ContentProviderOperation> operations = new ArrayList<>();

            // Group insert operations together to minimize inter process communication and improve
            // processing time.
            for (VCardEntry callLog : history) {
                if (Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "insertCallHistory: Interrupted during insert");
                    break;
                }

                // Append current call to list of insert operations.
                int numberOfOperations = operations.size();
                constructInsertOperationsForCallLog(account, type, callLog, operations);

                if (operations.size() >= CONTACTS_INSERT_BATCH_SIZE) {
                    Log.i(
                            TAG,
                            "insertCallHistory: batch full, operations.size()="
                                    + operations.size()
                                    + ", batch_size="
                                    + CONTACTS_INSERT_BATCH_SIZE);

                    // If we have exceeded the limit of the insert operations, remove the latest
                    // call and submit.
                    operations.subList(numberOfOperations, operations.size()).clear();

                    contactsProvider.applyBatch(CallLog.AUTHORITY, operations);

                    // Re-add the current call log operation(s) to the list
                    operations = constructInsertOperationsForCallLog(account, type, callLog, null);

                    Log.i(
                            TAG,
                            "insertCallHistory: batch complete, operations.size()="
                                    + operations.size());
                }
            }

            // Apply any unsubmitted calls
            if (operations.size() > 0) {
                contactsProvider.applyBatch(CallLog.AUTHORITY, operations);
                operations.clear();
            }
            Log.i(TAG, "insertCallHistory: insert complete, count=" + history.size());
        } catch (OperationApplicationException | RemoteException | NumberFormatException e) {
            Log.e(TAG, "insertCallHistory: Exception occurred while processing call log pull: ", e);
            return false;
        }
        return true;
    }

    // TODO: b/365629730 -- JavaUtilDate: prefer Instant or LocalDate
    // NonApiType: For convenience, as the applyBatch API actually takes an ArrayList above
    @SuppressWarnings({"JavaUtilDate", "NonApiType"})
    private static ArrayList<ContentProviderOperation> constructInsertOperationsForCallLog(
            Account account,
            int type,
            VCardEntry call,
            ArrayList<ContentProviderOperation> operations) {
        if (operations == null) {
            operations = new ArrayList<ContentProviderOperation>();
        }

        ContentValues values = new ContentValues();
        values.put(Calls.PHONE_ACCOUNT_ID, account.name);
        values.put(CallLog.Calls.TYPE, type);

        List<PhoneData> phones = call.getPhoneList();
        if (phones == null
                || phones.get(0).getNumber().equals(";")
                || phones.get(0).getNumber().length() == 0) {
            values.put(CallLog.Calls.NUMBER, "");
        } else {
            String phoneNumber = phones.get(0).getNumber();
            values.put(CallLog.Calls.NUMBER, phoneNumber);
        }

        List<Pair<String, String>> irmc = call.getUnknownXData();
        SimpleDateFormat parser = new SimpleDateFormat(TIMESTAMP_FORMAT);
        if (irmc != null) {
            for (Pair<String, String> pair : irmc) {
                if (pair.first.startsWith(CALL_LOG_TIMESTAMP_PROPERTY)) {
                    try {
                        values.put(CallLog.Calls.DATE, parser.parse(pair.second).getTime());
                    } catch (ParseException e) {
                        Log.d(TAG, "Failed to parse date, value=" + pair.second);
                    }
                }
            }
        }

        operations.add(
                ContentProviderOperation.newInsert(CallLog.Calls.CONTENT_URI)
                        .withValues(values)
                        .withYieldAllowed(true)
                        .build());

        return operations;
    }

    /**
     * Remove all call history associated with this client's account
     *
     * @param account The account to remove call history on behalf of
     */
    public boolean removeCallHistory(Account account) {
        if (account == null) {
            Log.e(TAG, "removeCallHistory: account is null");
            return false;
        }

        Log.i(TAG, "removeCallHistory: requested for account=" + account);
        try {
            mContext.getContentResolver()
                    .delete(
                            CallLog.Calls.CONTENT_URI,
                            CallLog.Calls.PHONE_ACCOUNT_ID + "=?",
                            new String[] {account.name});
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Call Logs could not be deleted, they may not exist yet.", e);
            return false;
        }
        return true;
    }

    // *********************************************************************************************
    // * Callbacks
    // *********************************************************************************************

    public void registerCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.add(callback);
        }
    }

    public void unregisterCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    /** Notify all client callbacks that the set of storage accounts has changed */
    private void notifyStorageReady() {
        Log.d(TAG, "notifyStorageReady");
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                callback.onStorageReady();
            }
        }
    }

    /** Notify all client callbacks that the set of storage accounts has changed */
    private void notifyStorageAccountsChanged(
            List<Account> oldAccounts, List<Account> newAccounts) {
        Log.d(TAG, "notifyAccountsChanged, old=" + oldAccounts + ", new=" + newAccounts);
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                callback.onStorageAccountsChanged(oldAccounts, newAccounts);
            }
        }
    }

    // *********************************************************************************************
    // * Debug and Dump Output
    // *********************************************************************************************

    @Override
    public String toString() {
        return "<" + TAG + " ready=" + isStorageReady() + ">";
    }

    /**
     * Get a summary of the total number of contacts stored for a given account
     *
     * <p>Query the Contacts Provider Data table for raw contact ids that below to a given account
     * type and name.
     *
     * @return a formatted string with the number of contacts stored for a given account
     */
    private String dumpContactsSummary(Account account) {
        StringBuilder sb = new StringBuilder();
        List<Long> rawContactIds = new ArrayList<>();
        try (Cursor cursor =
                mContext.getContentResolver()
                        .query(
                                ContactsContract.Data.CONTENT_URI,
                                new String[] {ContactsContract.Data.RAW_CONTACT_ID},
                                ContactsContract.RawContacts.ACCOUNT_TYPE
                                        + " = ? AND "
                                        + ContactsContract.RawContacts.ACCOUNT_NAME
                                        + " = ?",
                                new String[] {account.type, account.name},
                                null)) {

            if (cursor.moveToFirst()) {
                int rawContactIdIndex = cursor.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID);
                do {
                    long rawContactId = cursor.getLong(rawContactIdIndex);
                    rawContactIds.add(rawContactId);
                } while (cursor.moveToNext());
            }
        }

        sb.append("            ").append(rawContactIds.size()).append(" contacts\n");
        return sb.toString();
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(TAG + ":\n");
        sb.append("    Storage Ready: ").append(mStorageInitialized).append("\n\n");
        sb.append("    ").append(mAccountManager.dump()).append("\n");

        sb.append("\n    Database:\n");
        for (Account account : mAccountManager.getAccounts()) {
            sb.append("        Account ").append(account.name).append(":\n");
            sb.append(dumpContactsSummary(account));
        }

        return sb.toString();
    }
}
