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
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

/** Fragment to select the device role of channel sounding. */
public class RoleSelectionFragment extends Fragment {

    private Button mButtonInitiator;
    private Button mButtonReflector;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_role_selection, container, false);

        mButtonInitiator = (Button) root.findViewById(R.id.button_initiator);
        mButtonReflector = (Button) root.findViewById(R.id.button_reflector);
        return root;
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mButtonInitiator.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        NavHostFragment.findNavController(RoleSelectionFragment.this)
                                .navigate(R.id.action_RoleSelectionFragment_to_InitiatorFragment);
                    }
                });
        mButtonReflector.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        NavHostFragment.findNavController(RoleSelectionFragment.this)
                                .navigate(R.id.action_RoleSelectionFragment_to_ReflectorFragment);
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }
}
