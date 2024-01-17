/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.opp;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.StaleDataException;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.R;

/**
 * View showing the user's finished bluetooth opp transfers that the user does
 * not confirm. Including outbound and inbound transfers, both successful and
 * failed. *
 */
public class BluetoothOppTransferHistory extends Activity
        implements View.OnCreateContextMenuListener, OnItemClickListener {
    private static final String TAG = "BluetoothOppTransferHistory";

    private static final boolean V = Constants.VERBOSE;

    private ListView mListView;

    private Cursor mTransferCursor;

    private BluetoothOppTransferAdapter mTransferAdapter;

    private int mIdColumnId;

    private int mContextMenuPosition;

    private boolean mShowAllIncoming;

    private boolean mContextMenu = false;

    /** Class to handle Notification Manager updates */
    private BluetoothOppNotification mNotifier;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.bluetooth_transfers_page);
        mListView = (ListView) findViewById(R.id.list);
        mListView.setEmptyView(findViewById(R.id.empty));

        String direction;
        int dir = getIntent().getIntExtra(Constants.EXTRA_DIRECTION, 0);
        if (dir == BluetoothShare.DIRECTION_OUTBOUND) {
            setTitle(getText(R.string.outbound_history_title));
            direction = "(" + BluetoothShare.DIRECTION + " == " + BluetoothShare.DIRECTION_OUTBOUND
                    + ")";
        } else {
            setTitle(getText(R.string.inbound_history_title));
            direction = "(" + BluetoothShare.DIRECTION + " == " + BluetoothShare.DIRECTION_INBOUND
                    + ")";
        }

        String selection = BluetoothShare.STATUS + " >= '200' AND " + direction + " AND ("
                    + BluetoothShare.VISIBILITY + " IS NULL OR "
                    + BluetoothShare.VISIBILITY + " == '" + BluetoothShare.VISIBILITY_VISIBLE
                    + "')";

        final String sortOrder = BluetoothShare.TIMESTAMP + " DESC";
        mTransferCursor = BluetoothMethodProxy.getInstance().contentResolverQuery(
                getContentResolver(), BluetoothShare.CONTENT_URI, new String[]{
                "_id",
                BluetoothShare.FILENAME_HINT,
                BluetoothShare.STATUS,
                BluetoothShare.TOTAL_BYTES,
                BluetoothShare._DATA,
                BluetoothShare.TIMESTAMP,
                BluetoothShare.VISIBILITY,
                BluetoothShare.DESTINATION,
                BluetoothShare.DIRECTION
        }, selection, null, sortOrder);

        // only attach everything to the listbox if we can access
        // the transfer database. Otherwise, just show it empty
        if (mTransferCursor != null) {
            mIdColumnId = mTransferCursor.getColumnIndexOrThrow(BluetoothShare._ID);
            // Create a list "controller" for the data
            mTransferAdapter =
                    new BluetoothOppTransferAdapter(this, R.layout.bluetooth_transfer_item,
                            mTransferCursor);
            mListView.setAdapter(mTransferAdapter);
            mListView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
            mListView.setOnCreateContextMenuListener(this);
            mListView.setOnItemClickListener(this);
        }

        mNotifier = new BluetoothOppNotification(this);
        mContextMenu = false;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mTransferCursor != null) {
            MenuInflater inflater = getMenuInflater();
            inflater.inflate(R.menu.transferhistory, menu);
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.transfer_menu_clear_all).setEnabled(isTransferComplete());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.transfer_menu_clear_all:
                promptClearList();
                return true;
        }
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (mTransferCursor.getCount() == 0) {
            Log.i(TAG, "History is already cleared, not clearing again");
            return true;
        }
        mTransferCursor.moveToPosition(mContextMenuPosition);
        switch (item.getItemId()) {
            case R.id.transfer_menu_open:
                openCompleteTransfer();
                updateNotificationWhenBtDisabled();
                return true;

            case R.id.transfer_menu_clear:
                int sessionId = mTransferCursor.getInt(mIdColumnId);
                Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + sessionId);
                BluetoothOppUtility.updateVisibilityToHidden(this, contentUri);
                updateNotificationWhenBtDisabled();
                return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if (mTransferCursor != null) {
            mTransferCursor.close();
        }
        super.onDestroy();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        if (mTransferCursor != null) {
            mContextMenu = true;
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
            mTransferCursor.moveToPosition(info.position);
            mContextMenuPosition = info.position;

            String fileName = mTransferCursor.getString(
                    mTransferCursor.getColumnIndexOrThrow(BluetoothShare.FILENAME_HINT));
            if (fileName == null) {
                fileName = this.getString(R.string.unknown_file);
            }
            menu.setHeaderTitle(fileName);
            getMenuInflater().inflate(R.menu.transferhistorycontextfinished, menu);
        }
    }

    /**
     * Prompt the user if they would like to clear the transfer history
     */
    private void promptClearList() {
        new AlertDialog.Builder(this).setTitle(R.string.transfer_clear_dlg_title)
                .setMessage(R.string.transfer_clear_dlg_msg)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int whichButton) {
                        clearAllDownloads();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Returns true if the device has finished transfers, including error and success.
     */
    private boolean isTransferComplete() {
        try {
            if (mTransferCursor.moveToFirst()) {
                while (!mTransferCursor.isAfterLast()) {
                    int statusColumnId = mTransferCursor
                                             .getColumnIndexOrThrow(BluetoothShare.STATUS);
                    int status = mTransferCursor.getInt(statusColumnId);
                    if (BluetoothShare.isStatusCompleted(status)) {
                        return true;
                    }
                    mTransferCursor.moveToNext();
                }
            }
        } catch (StaleDataException e) {
        }
        return false;
    }

    /**
     * Clear all finished transfers, error and success transfer items.
     */
    private void clearAllDownloads() {
        if (mTransferCursor.moveToFirst()) {
            while (!mTransferCursor.isAfterLast()) {
                int sessionId = mTransferCursor.getInt(mIdColumnId);
                Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + sessionId);
                BluetoothOppUtility.updateVisibilityToHidden(this, contentUri);

                mTransferCursor.moveToNext();
            }
            updateNotificationWhenBtDisabled();
        }
    }

    /*
     * (non-Javadoc)
     * @see
     * android.widget.AdapterView.OnItemClickListener#onItemClick(android.widget
     * .AdapterView, android.view.View, int, long)
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        // Open the selected item
        if (V) {
            Log.v(TAG, "onItemClick: ContextMenu = " + mContextMenu);
        }
        if (!mContextMenu) {
            mTransferCursor.moveToPosition(position);
            openCompleteTransfer();
            updateNotificationWhenBtDisabled();
        }
        mContextMenu = false;
    }

    /**
     * Open the selected finished transfer. mDownloadCursor must be moved to
     * appropriate position before calling this function
     */
    private void openCompleteTransfer() {
        int sessionId = mTransferCursor.getInt(mIdColumnId);
        Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + sessionId);
        BluetoothOppTransferInfo transInfo = BluetoothOppUtility.queryRecord(this, contentUri);
        if (transInfo == null) {
            Log.e(TAG, "Error: Can not get data from db");
            return;
        }
        if (transInfo.mDirection == BluetoothShare.DIRECTION_INBOUND
                && BluetoothShare.isStatusSuccess(transInfo.mStatus)) {
            // if received file successfully, open this file
            BluetoothOppUtility.updateVisibilityToHidden(this, contentUri);
            BluetoothOppUtility.openReceivedFile(this, transInfo.mFileName, transInfo.mFileType,
                    transInfo.mTimeStamp, contentUri);
        } else {
            Intent in = new Intent(this, BluetoothOppTransferActivity.class);
            in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            in.setDataAndNormalize(contentUri);
            this.startActivity(in);
        }
    }

    /**
     * When Bluetooth is disabled, notification can not be updated by
     * ContentObserver in OppService, so need update manually.
     */
    private void updateNotificationWhenBtDisabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (!adapter.isEnabled()) {
            if (V) {
                Log.v(TAG, "Bluetooth is not enabled, update notification manually.");
            }
            mNotifier.updateNotification();
        }
    }
}
