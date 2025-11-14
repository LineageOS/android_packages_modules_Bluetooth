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
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;

/** Child fragment to handle BLE GATT connection. */
@SuppressWarnings("SetTextI18n")
public class BleConnectionFragment extends Fragment {

    private BleConnectionViewModel mViewModel;
    private Button mBtnAdvertising;

    private ArrayAdapter<String> mBondedBtDevicesArrayAdapter;
    private Button mButtonUpdate;
    private Button mButtonGatt;
    private Button mButtonScanConnect;
    private Spinner mSpinnerBtAddress;

    public static BleConnectionFragment newInstance() {
        return new BleConnectionFragment();
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {

        View root = inflater.inflate(R.layout.fragment_ble_connection, container, false);
        mBtnAdvertising = root.findViewById(R.id.btn_advertising);
        mButtonUpdate = (Button) root.findViewById(R.id.btn_update_devices);
        mButtonGatt = (Button) root.findViewById(R.id.btn_connect_gatt);
        mButtonScanConnect = (Button) root.findViewById(R.id.btn_scan_connect);
        mSpinnerBtAddress = (Spinner) root.findViewById(R.id.spinner_bt_address);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mBondedBtDevicesArrayAdapter =
                new ArrayAdapter<String>(
                        getContext(), android.R.layout.simple_spinner_item, new ArrayList<>());
        mBondedBtDevicesArrayAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mSpinnerBtAddress.setAdapter(mBondedBtDevicesArrayAdapter);

        mViewModel =
                new ViewModelProvider(requireParentFragment()).get(BleConnectionViewModel.class);
        mViewModel
                .getGattState()
                .observe(
                        getActivity(),
                        gattSate -> {
                            switch (gattSate) {
                                case CONNECTED_DIRECT:
                                    mButtonGatt.setText("Disconnect Gatt");
                                    break;
                                case SCANNING:
                                    mButtonScanConnect.setText("Stop Scan");
                                    break;
                                case CONNECTED_SCAN:
                                    mButtonScanConnect.setText("Disconnect Gatt");
                                    break;
                                case DISCONNECTED:
                                default:
                                    mButtonGatt.setText("Connect Gatt");
                                    mButtonScanConnect.setText("Scan and Connect");
                            }
                        });
        mViewModel.updateBondedDevices();
        mButtonUpdate.setOnClickListener(
                v -> {
                    mViewModel.updateBondedDevices();
                });
        mViewModel
                .getBondedBtDeviceAddresses()
                .observe(
                        getActivity(),
                        deviceList -> {
                            mBondedBtDevicesArrayAdapter.clear();
                            mBondedBtDevicesArrayAdapter.addAll(deviceList);
                            if (mSpinnerBtAddress.getSelectedItem() != null) {
                                String selectedBtAddress =
                                        mSpinnerBtAddress.getSelectedItem().toString();
                                mViewModel.setCsTargetAddress(selectedBtAddress);
                            }
                        });
        mSpinnerBtAddress.setOnItemSelectedListener(
                new OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> adapterView, View view, int i, long l) {
                        String btAddress = mSpinnerBtAddress.getSelectedItem().toString();
                        mViewModel.setCsTargetAddress(btAddress);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {
                        mViewModel.setCsTargetAddress("");
                    }
                });
        mButtonGatt.setOnClickListener(
                v -> {
                    mViewModel.toggleGattConnection();
                });
        mButtonScanConnect.setOnClickListener(
                v -> {
                    mViewModel.toggleScanConnect();
                });
        mViewModel
                .getIsAdvertising()
                .observe(
                        getActivity(),
                        isAdvertising -> {
                            if (isAdvertising) {
                                mBtnAdvertising.setText("Stop Advertising");
                            } else {
                                mBtnAdvertising.setText("Start Advertising");
                            }
                        });

        mBtnAdvertising.setOnClickListener(
                new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        mViewModel.toggleAdvertising();
                    }
                });
    }
}
