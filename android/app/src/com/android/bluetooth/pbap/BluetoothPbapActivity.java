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

package com.android.bluetooth.pbap;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.Preference;
import android.text.InputFilter;
import android.text.InputFilter.LengthFilter;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.R;
import com.android.bluetooth.util.AlertActivity;
import com.android.internal.annotations.VisibleForTesting;

/**
 * PbapActivity shows two dialogues: One for accepting incoming pbap request and the other prompts
 * the user to enter a session key for authentication with a remote Bluetooth device.
 */
public class BluetoothPbapActivity extends AlertActivity
        implements Preference.OnPreferenceChangeListener, TextWatcher {
    private static final String TAG = BluetoothPbapActivity.class.getSimpleName();

    private static final int BLUETOOTH_OBEX_AUTHKEY_MAX_LENGTH = 16;

    @VisibleForTesting static final int DIALOG_YES_NO_AUTH = 1;

    private static final String KEY_USER_TIMEOUT = "user_timeout";

    private View mView;

    private EditText mKeyView;

    private TextView mMessageView;

    private String mSessionKey = "";

    @VisibleForTesting int mCurrentDialog;

    private boolean mTimeout = false;

    @VisibleForTesting static final int DISMISS_TIMEOUT_DIALOG = 0;

    @VisibleForTesting static final long DISMISS_TIMEOUT_DIALOG_DELAY_MS = 2_000;

    private BluetoothDevice mDevice;

    @VisibleForTesting
    BroadcastReceiver mReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!BluetoothPbapService.USER_CONFIRM_TIMEOUT_ACTION.equals(
                            intent.getAction())) {
                        return;
                    }
                    onTimeout();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        Intent i = getIntent();
        String action = i.getAction();
        mDevice = i.getParcelableExtra(BluetoothPbapService.EXTRA_DEVICE);
        if (action != null && action.equals(BluetoothPbapService.AUTH_CHALL_ACTION)) {
            showPbapDialog(DIALOG_YES_NO_AUTH);
            mCurrentDialog = DIALOG_YES_NO_AUTH;
        } else {
            Log.e(
                    TAG,
                    "Error: this activity may be started only with intent "
                            + "PBAP_ACCESS_REQUEST or PBAP_AUTH_CHALL ");
            finish();
        }
        IntentFilter filter = new IntentFilter(BluetoothPbapService.USER_CONFIRM_TIMEOUT_ACTION);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(mReceiver, filter);
    }

    private void showPbapDialog(int id) {
        if (id != DIALOG_YES_NO_AUTH) {
            return;
        }
        mAlertBuilder.setTitle(getString(R.string.pbap_session_key_dialog_header));
        mAlertBuilder.setView(createView(DIALOG_YES_NO_AUTH));
        mAlertBuilder.setPositiveButton(android.R.string.ok, (dialog, which) -> onPositive());
        mAlertBuilder.setNegativeButton(android.R.string.cancel, (dialog, which) -> onNegative());
        setupAlert();
        changeButtonEnabled(DialogInterface.BUTTON_POSITIVE, false);
    }

    private String createDisplayText(final int id) {
        return switch (id) {
            case DIALOG_YES_NO_AUTH -> getString(R.string.pbap_session_key_dialog_title, mDevice);
            default -> null;
        };
    }

    private View createView(final int id) {
        return switch (id) {
            case DIALOG_YES_NO_AUTH -> {
                mView = getLayoutInflater().inflate(R.layout.auth, null);
                mMessageView = (TextView) mView.findViewById(R.id.message);
                mMessageView.setText(createDisplayText(id));
                mKeyView = (EditText) mView.findViewById(R.id.text);
                mKeyView.addTextChangedListener(this);
                mKeyView.setFilters(
                        new InputFilter[] {new LengthFilter(BLUETOOTH_OBEX_AUTHKEY_MAX_LENGTH)});
                yield mView;
            }
            default -> null;
        };
    }

    @VisibleForTesting
    void onPositive() {
        if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
            mSessionKey = mKeyView.getText().toString();
        }

        if (!mTimeout) {
            if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
                sendIntentToReceiver(
                        BluetoothPbapService.AUTH_RESPONSE_ACTION,
                        BluetoothPbapService.EXTRA_SESSION_KEY,
                        mSessionKey);
                mKeyView.removeTextChangedListener(this);
            }
        }
        mTimeout = false;
        finish();
    }

    @VisibleForTesting
    void onNegative() {
        if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
            sendIntentToReceiver(BluetoothPbapService.AUTH_CANCELLED_ACTION, null, null);
            mKeyView.removeTextChangedListener(this);
        }
        finish();
    }

    private void sendIntentToReceiver(
            final String intentName, final String extraName, final String extraValue) {
        Intent intent = new Intent(intentName);
        intent.setPackage(getPackageName());
        intent.putExtra(BluetoothPbapService.EXTRA_DEVICE, mDevice);
        if (extraName != null) {
            intent.putExtra(extraName, extraValue);
        }
        BluetoothMethodProxy.getInstance().contextSendBroadcast(this, intent);
    }

    @VisibleForTesting
    private void onTimeout() {
        mTimeout = true;
        if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
            mMessageView.setText(getString(R.string.pbap_authentication_timeout_message, mDevice));
            mKeyView.setVisibility(View.GONE);
            mKeyView.clearFocus();
            mKeyView.removeTextChangedListener(this);
            changeButtonEnabled(DialogInterface.BUTTON_POSITIVE, true);
            changeButtonVisibility(DialogInterface.BUTTON_NEGATIVE, View.GONE);
        }

        BluetoothMethodProxy.getInstance()
                .handlerSendMessageDelayed(
                        mTimeoutHandler, DISMISS_TIMEOUT_DIALOG, DISMISS_TIMEOUT_DIALOG_DELAY_MS);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mTimeout = savedInstanceState.getBoolean(KEY_USER_TIMEOUT);
        Log.v(TAG, "onRestoreInstanceState() mTimeout: " + mTimeout);
        if (mTimeout) {
            onTimeout();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_USER_TIMEOUT, mTimeout);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return true;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int before, int after) {}

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {}

    @Override
    public void afterTextChanged(android.text.Editable s) {
        if (s.length() > 0) {
            changeButtonEnabled(DialogInterface.BUTTON_POSITIVE, true);
        }
    }

    private final Handler mTimeoutHandler =
            new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (msg.what != DISMISS_TIMEOUT_DIALOG) {
                        return;
                    }
                    Log.v(TAG, "Received DISMISS_TIMEOUT_DIALOG msg.");
                    finish();
                }
            };
}
