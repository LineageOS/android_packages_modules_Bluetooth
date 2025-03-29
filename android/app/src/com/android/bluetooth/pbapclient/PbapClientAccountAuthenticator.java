/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.util.Arrays;

/**
 * An AccountAuthenticator class that allows us to register with the AccountManagerService framework
 *
 * <p>In order to store contacts on device, we need to associate them with an AccountManager
 * Account. This allows for easy management, including deletion.
 *
 * <p>This class is hosted by a service object, which AccountManagerService can use to interact with
 * us. In practice though, most/all of this class goes unused and is only an necessary evil so that
 * AccountManagerService will allow us to explicitly make accounts of the type we specify.
 */
public class PbapClientAccountAuthenticator extends AbstractAccountAuthenticator {
    private static final String TAG = PbapClientAccountAuthenticator.class.getSimpleName();

    public PbapClientAccountAuthenticator(Context context) {
        super(context);
    }

    // Editing properties is not supported
    @Override
    public Bundle editProperties(AccountAuthenticatorResponse r, String accountType) {
        Log.d(TAG, "editProperties(accountType=" + accountType + ")");
        throw new UnsupportedOperationException();
    }

    // Don't add additional accounts
    @Override
    public Bundle addAccount(
            AccountAuthenticatorResponse r,
            String accountType,
            String authTokenType,
            String[] requiredFeatures,
            Bundle options)
            throws NetworkErrorException {
        Log.d(
                TAG,
                "addAccount(accountType="
                        + accountType
                        + ", authTokenType="
                        + authTokenType
                        + ", requiredFeatures="
                        + Arrays.toString(requiredFeatures)
                        + ")");
        // Don't allow accounts to be added.
        throw new UnsupportedOperationException();
    }

    // Ignore attempts to confirm credentials
    @Override
    public Bundle confirmCredentials(
            AccountAuthenticatorResponse r, Account account, Bundle options)
            throws NetworkErrorException {
        Log.d(TAG, "confirmCredentials(account=" + account + ", options=" + options + ")");
        return null;
    }

    // Getting an authentication token is not supported
    @Override
    public Bundle getAuthToken(
            AccountAuthenticatorResponse r, Account account, String authTokenType, Bundle options)
            throws NetworkErrorException {
        Log.d(
                TAG,
                "getAuthToken(account="
                        + account
                        + ", authTokenType="
                        + authTokenType
                        + ", options="
                        + options
                        + ")");
        throw new UnsupportedOperationException();
    }

    // Getting a label for the auth token is not supported
    @Override
    public String getAuthTokenLabel(String authTokenType) {
        Log.d(TAG, "getAuthTokenLabel(authTokenType=" + authTokenType + ")");
        return null;
    }

    // Updating user credentials is not supported
    @Override
    public Bundle updateCredentials(
            AccountAuthenticatorResponse r, Account account, String authTokenType, Bundle options)
            throws NetworkErrorException {
        Log.d(
                TAG,
                "updateCredentials(account="
                        + account
                        + ", authTokenType="
                        + authTokenType
                        + ", options="
                        + options
                        + ")");
        return null;
    }

    // Checking features for the account is not supported
    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse r, Account account, String[] features)
            throws NetworkErrorException {
        Log.d(
                TAG,
                "hasFeatures(Account=" + account + ", features=" + Arrays.toString(features) + ")");

        final Bundle result = new Bundle();
        result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
        return result;
    }
}
