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
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;

/** The fragment holds the reflector of channel sounding. */
@SuppressWarnings("SetTextI18n")
public class ReflectorFragment extends Fragment {

    private ReflectorViewModel mReflectorViewModel;
    private Button mBtnAdvertising;
    private TextView mLogText;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_reflector, container, false);

        mBtnAdvertising = (Button) root.findViewById(R.id.btn_advertising);
        mLogText = (TextView) root.findViewById(R.id.text_log);
        return root;
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mReflectorViewModel = ViewModelProviders.of(getActivity()).get(ReflectorViewModel.class);
        mReflectorViewModel
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
        mReflectorViewModel
                .getLogText()
                .observe(
                        getActivity(),
                        log -> {
                            mLogText.setText(log);
                        });

        mBtnAdvertising.setOnClickListener(
                new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        mReflectorViewModel.toggleAdvertising();
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }
}
