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

package com.android.bluetooth.channelsoundingtestapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;

import java.text.DecimalFormat;
import java.util.ArrayList;

/** d The fragment holds the initiator of channel sounding. */
@SuppressWarnings("SetTextI18n")
public class InitiatorFragment extends Fragment {

    private static final DecimalFormat DISTANCE_DECIMAL_FMT = new DecimalFormat("0.00");

    private ArrayAdapter<String> mDmMethodArrayAdapter;
    private ArrayAdapter<String> mBondedBtDevicesArrayAdapter;
    private TextView mDistanceText;
    private CanvasView mDistanceCanvasView;
    private Spinner mSpinnerDmMethod;
    private Button mButtonUpdate;
    private Button mButtonCs;
    private Button mButtonGatt;
    private Spinner mSpinnerBtAddress;
    private LinearLayout mDistanceViewLayout;
    private TextView mLogText;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_initiator, container, false);
        mButtonUpdate = (Button) root.findViewById(R.id.btn_update_devices);
        mButtonCs = (Button) root.findViewById(R.id.btn_cs);
        mButtonGatt = (Button) root.findViewById(R.id.btn_connect_gatt);
        mSpinnerBtAddress = (Spinner) root.findViewById(R.id.spinner_bt_address);
        mSpinnerDmMethod = (Spinner) root.findViewById(R.id.spinner_dm_method);
        mDistanceViewLayout = (LinearLayout) root.findViewById(R.id.layout_distance_view);
        mDistanceText = new TextView(getContext());
        mDistanceViewLayout.addView(mDistanceText);
        mDistanceText.setText("0.00 m");
        mDistanceText.setTextSize(96);
        mDistanceText.setGravity(Gravity.END);
        mDistanceCanvasView = new CanvasView(getContext(), "Distance");
        mDistanceViewLayout.addView(mDistanceCanvasView);
        mDistanceViewLayout.setPadding(0, 0, 0, 600);
        mLogText = (TextView) root.findViewById(R.id.text_log);
        return root;
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mDmMethodArrayAdapter =
                new ArrayAdapter<String>(
                        getContext(), android.R.layout.simple_spinner_item, new ArrayList<>());
        mDmMethodArrayAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mSpinnerDmMethod.setAdapter(mDmMethodArrayAdapter);

        mBondedBtDevicesArrayAdapter =
                new ArrayAdapter<String>(
                        getContext(), android.R.layout.simple_spinner_item, new ArrayList<>());
        mBondedBtDevicesArrayAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mSpinnerBtAddress.setAdapter(mBondedBtDevicesArrayAdapter);

        InitiatorViewModel initiatorViewModel =
                ViewModelProviders.of(getActivity()).get(InitiatorViewModel.class);

        initiatorViewModel
                .getGattConnected()
                .observe(
                        getActivity(),
                        connected -> {
                            if (connected) {
                                mButtonGatt.setText("Disconnect Gatt");
                            } else {
                                mButtonGatt.setText("Connect Gatt");
                            }
                        });
        initiatorViewModel
                .getCsStarted()
                .observe(
                        getActivity(),
                        started -> {
                            if (started) {
                                mButtonCs.setText("Stop Distance Measurement");
                                mDistanceCanvasView.cleanUp();
                            } else {
                                mButtonCs.setText("Start Distance Measurement");
                            }
                        });
        initiatorViewModel
                .getBondedBtDeviceAddresses()
                .observe(
                        getActivity(),
                        deviceList -> {
                            mBondedBtDevicesArrayAdapter.clear();
                            mBondedBtDevicesArrayAdapter.addAll(deviceList);
                            if (mSpinnerBtAddress.getSelectedItem() != null) {
                                String selectedBtAddress =
                                        mSpinnerBtAddress.getSelectedItem().toString();
                                printLog("set target address: ");
                                initiatorViewModel.setCsTargetAddress(selectedBtAddress);
                            }
                        });
        mSpinnerBtAddress.setOnItemSelectedListener(
                new OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> adapterView, View view, int i, long l) {
                        String btAddress = mSpinnerBtAddress.getSelectedItem().toString();
                        printLog("Target Address: " + btAddress);
                        initiatorViewModel.setCsTargetAddress(btAddress);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {
                        initiatorViewModel.setCsTargetAddress("");
                    }
                });
        initiatorViewModel
                .getLogText()
                .observe(
                        getActivity(),
                        log -> {
                            mLogText.setText(log);
                        });

        initiatorViewModel
                .getDistanceResult()
                .observe(
                        getActivity(),
                        distanceMeters -> {
                            mDistanceCanvasView.addNode(distanceMeters, /* abort= */ false);
                            mDistanceText.setText(
                                    DISTANCE_DECIMAL_FMT.format(distanceMeters) + " m");
                        });

        mDmMethodArrayAdapter.addAll(initiatorViewModel.getSupportedDmMethods());

        mButtonUpdate.setOnClickListener(
                v -> {
                    printLog("click update Bonded Devices.");
                    initiatorViewModel.updateBondedDevices();
                });
        mButtonGatt.setOnClickListener(
                v -> {
                    if (!hasValidTarget()) return;
                    initiatorViewModel.toggleGattConnection();
                });
        mButtonCs.setOnClickListener(
                v -> {
                    if (!hasValidTarget()) return;
                    String methodName = mSpinnerDmMethod.getSelectedItem().toString();
                    if (TextUtils.isEmpty(methodName)) {
                        printLog("the device doesn't support any distance measurement methods.");
                    }
                    initiatorViewModel.toggleCsStartStop(methodName);
                });
    }

    private boolean hasValidTarget() {
        String btAddress = "";
        if (mSpinnerBtAddress.getSelectedItem() != null) {
            btAddress = mSpinnerBtAddress.getSelectedItem().toString();
        }
        if (TextUtils.isEmpty(btAddress)) {
            printLog("Pair and select a target device first!");
            return false;
        }
        return true;
    }

    private void printLog(String logMessage) {
        mLogText.setText("LOG: " + logMessage);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }
}
