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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import java.text.DecimalFormat;
import java.util.ArrayList;

/** The fragment holds the initiator of channel sounding. */
@SuppressWarnings("SetTextI18n")
public class InitiatorFragment extends Fragment {

    private static final DecimalFormat DISTANCE_DECIMAL_FMT = new DecimalFormat("0.00");

    private ArrayAdapter<String> mDmMethodArrayAdapter;
    private ArrayAdapter<String> mFreqArrayAdapter;
    private ArrayAdapter<String> mDurationArrayAdapter;
    private TextView mDistanceText;
    private CanvasView mDistanceCanvasView;
    private Spinner mSpinnerDmMethod;
    private Spinner mSpinnerFreq;
    private Spinner mSpinnerDuration;
    private Button mButtonCs;
    private LinearLayout mDistanceViewLayout;
    private TextView mLogText;

    private BleConnectionViewModel mBleConnectionViewModel;
    private InitiatorViewModel mInitiatorViewModel;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_initiator, container, false);
        Fragment bleConnectionFragment = new BleConnectionFragment();
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        transaction.replace(R.id.init_ble_connection_container, bleConnectionFragment).commit();

        mButtonCs = (Button) root.findViewById(R.id.btn_cs);
        mSpinnerDmMethod = (Spinner) root.findViewById(R.id.spinner_dm_method);
        mSpinnerFreq = (Spinner) root.findViewById(R.id.spinner_freq);
        mSpinnerDuration = (Spinner) root.findViewById(R.id.spinner_duration);
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
        mFreqArrayAdapter =
                new ArrayAdapter<String>(
                        getContext(), android.R.layout.simple_spinner_item, new ArrayList<>());
        mFreqArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinnerFreq.setAdapter(mFreqArrayAdapter);
        mDurationArrayAdapter =
                new ArrayAdapter<String>(
                        getContext(), android.R.layout.simple_spinner_item, new ArrayList<>());
        mDurationArrayAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mSpinnerDuration.setAdapter(mDurationArrayAdapter);

        mInitiatorViewModel = new ViewModelProvider(this).get(InitiatorViewModel.class);
        mBleConnectionViewModel = new ViewModelProvider(this).get(BleConnectionViewModel.class);
        mBleConnectionViewModel
                .getLogText()
                .observe(
                        getActivity(),
                        log -> {
                            mLogText.setText(log);
                        });
        mBleConnectionViewModel
                .getTargetDevice()
                .observe(
                        getActivity(),
                        targetDevice -> {
                            mInitiatorViewModel.setTargetDevice(targetDevice);
                        });

        mInitiatorViewModel
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
        mInitiatorViewModel
                .getLogText()
                .observe(
                        getActivity(),
                        log -> {
                            mLogText.setText(log);
                        });

        mInitiatorViewModel
                .getDistanceResult()
                .observe(
                        getActivity(),
                        distanceMeters -> {
                            mDistanceCanvasView.addNode(distanceMeters, /* abort= */ false);
                            mDistanceText.setText(
                                    DISTANCE_DECIMAL_FMT.format(distanceMeters) + " m");
                        });

        mDmMethodArrayAdapter.addAll(mInitiatorViewModel.getSupportedDmMethods());
        mFreqArrayAdapter.addAll(mInitiatorViewModel.getMeasurementFreqs());
        mDurationArrayAdapter.addAll(mInitiatorViewModel.getMeasurementDurations());
        int position = mDmMethodArrayAdapter.getPosition("Channel Sounding");
        if (position != -1) {
            printLog("Found method Channel Sounding");
            mSpinnerDmMethod.setSelection(position);
        }
        mButtonCs.setOnClickListener(
                v -> {
                    String methodName = mSpinnerDmMethod.getSelectedItem().toString();
                    String freq = mSpinnerFreq.getSelectedItem().toString();
                    int duration = Integer.parseInt(mSpinnerDuration.getSelectedItem().toString());

                    if (TextUtils.isEmpty(methodName)) {
                        printLog("the device doesn't support any distance measurement methods.");
                    }

                    mInitiatorViewModel.toggleCsStartStop(methodName, freq, duration);
                });
    }

    private void printLog(String logMessage) {
        mLogText.setText("LOG: " + logMessage);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }
}
