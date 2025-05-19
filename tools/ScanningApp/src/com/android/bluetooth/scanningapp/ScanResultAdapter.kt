/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.scanningapp

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

@SuppressLint("SetTextI18n")
class ScanResultAdapter : RecyclerView.Adapter<ScanResultAdapter.ScanResultViewHolder>() {

    private val scanResults = mutableListOf<ScanResult>()

    fun addScanResult(result: ScanResult) {
        result.device?.address?.let { newDeviceAddress ->
            val existingIndex = scanResults.indexOfFirst { it.device?.address == newDeviceAddress }

            if (existingIndex == -1) {
                scanResults.add(result)
                notifyItemInserted(scanResults.size - 1)
            } else {
                scanResults[existingIndex] = result
                notifyItemChanged(existingIndex)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clearResults() {
        scanResults.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScanResultViewHolder {
        val itemView =
            LayoutInflater.from(parent.context).inflate(R.layout.item_scan_result, parent, false)
        return ScanResultViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ScanResultViewHolder, position: Int) {
        holder.bind(scanResults[position])
    }

    override fun getItemCount(): Int = scanResults.size

    class ScanResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val deviceNameTextView: TextView = itemView.findViewById(R.id.deviceNameTextView)
        private val deviceAddressTextView: TextView =
            itemView.findViewById(R.id.deviceAddressTextView)
        private val rssiTextView: TextView = itemView.findViewById(R.id.rssiTextView)

        fun bind(result: ScanResult) {
            deviceNameTextView.text = result.device?.name ?: "N/A"
            deviceAddressTextView.text = result.device?.address ?: "N/A"
            rssiTextView.text = "RSSI: ${result.rssi} dBm"
        }
    }
}
