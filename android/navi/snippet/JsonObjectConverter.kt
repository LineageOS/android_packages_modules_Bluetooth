/*
 * Copyright 2025 The Android Open Source Project
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

package com.google.android.bluetooth.snippet

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import android.telecom.Call
import android.util.Base64
import androidx.core.util.forEach
import androidx.media3.common.AudioAttributes
import com.google.android.bluetooth.snippet.Utils.getOrNull
import com.google.android.bluetooth.snippet.Utils.toList
import com.google.android.bluetooth.snippet.Utils.toMap
import com.google.android.mobly.snippet.SnippetObjectConverter
import com.google.android.mobly.snippet.uiautomator.Converter as UiAutomatorObjectConverter
import java.lang.reflect.Type
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Converter converts between Android objects and JSON objects. */
class JsonObjectConverter : SnippetObjectConverter {
    val uiAutomatorObjectConverter = UiAutomatorObjectConverter()

    private fun BluetoothGattDescriptor.toJson() =
        JSONObject().also {
            it.put(SnippetConstants.FIELD_UUID, this.uuid.toString())
            it.put(SnippetConstants.GATT_FIELD_PERMISSIONS, this.permissions)
        }

    private fun BluetoothGattCharacteristic.toJson() =
        JSONObject().also {
            it.put(SnippetConstants.FIELD_HANDLE, this.instanceId)
            it.put(SnippetConstants.FIELD_UUID, this.uuid.toString())
            it.put(SnippetConstants.GATT_FIELD_PERMISSIONS, this.permissions)
            it.put(SnippetConstants.GATT_FIELD_PROPERTIES, this.properties)
            it.put(
                SnippetConstants.GATT_FIELD_DESCRIPTORS,
                JSONArray(this.descriptors.map { descriptor -> descriptor.toJson() }),
            )
        }

    private fun BluetoothGattService.toJson() =
        JSONObject().also {
            it.put(SnippetConstants.FIELD_HANDLE, this.instanceId)
            it.put(SnippetConstants.FIELD_UUID, this.uuid.toString())
            it.put(SnippetConstants.GATT_FIELD_TYPE, this.type)
            it.put(
                SnippetConstants.GATT_FIELD_CHARACTERISTICS,
                JSONArray(this.characteristics.map { characteristic -> characteristic.toJson() }),
            )
        }

    private fun Call.toJson() =
        JSONObject().also {
            it.put(SnippetConstants.FIELD_STATE, this.details.state)
            it.put(SnippetConstants.FIELD_HANDLE, this.details.handle)
            it.put(SnippetConstants.FIELD_NAME, this.details.callerDisplayName)
        }

    private fun ScanResult.toJson(): JSONObject =
        JSONObject().apply {
            put(SnippetConstants.FIELD_DEVICE, device?.address)
            put(SnippetConstants.ADV_PARAMETER_PRIMARY_PHY, primaryPhy)
            put(SnippetConstants.ADV_PARAMETER_SECONDARY_PHY, secondaryPhy)
            put(SnippetConstants.ADV_REPORT_SID, advertisingSid)
            put(SnippetConstants.ADV_PARAMETER_TX_POWER_LEVEL, txPower)
            put(SnippetConstants.ADV_REPORT_RSSI, rssi)
            put(SnippetConstants.ADV_REPORT_PA_INTERVAL, periodicAdvertisingInterval)
            put(SnippetConstants.ADV_REPORT_TIMESTAMP, timestampNanos)

            scanRecord?.let {
                put(SnippetConstants.ADV_DATA_FLAGS, it.advertiseFlags)
                put(SnippetConstants.FIELD_NAME, it.deviceName)
                it.serviceUuids
                    ?.map { uuid -> uuid.toString() }
                    ?.let { uuids -> put(SnippetConstants.ADV_DATA_SERVICE_UUID, JSONArray(uuids)) }
                it.serviceData
                    ?.map { (uuid, data) ->
                        uuid.toString() to Base64.encodeToString(data, Base64.NO_WRAP)
                    }
                    ?.toMap()
                    ?.let { data -> put(SnippetConstants.ADV_DATA_SERVICE_DATA, JSONObject(data)) }
                put(
                    SnippetConstants.ADV_DATA_SERVICE_SOLICITATION_UUIDS,
                    JSONArray(it.serviceSolicitationUuids.map { uuid -> uuid.toString() }),
                )
                it.manufacturerSpecificData?.let { manufacturerSpecificData ->
                    val jsonManufacturerSpecificData = JSONObject()
                    // SparseArray cannot be mapped, so we must take all of them out first.
                    manufacturerSpecificData.forEach { key, value ->
                        jsonManufacturerSpecificData.put(
                            key.toString(),
                            Base64.encodeToString(value, Base64.NO_WRAP),
                        )
                    }
                    put(SnippetConstants.ADV_DATA_MANUFACTURER_DATA, jsonManufacturerSpecificData)
                }
            }
        }

    private fun JSONObject.toAdvertiseSettings() =
        AdvertiseSettings.Builder()
            .apply {
                setConnectable(optBoolean(SnippetConstants.ADV_PARAMETER_CONNECTABLE, true))
                setOwnAddressType(
                    optInt(
                        SnippetConstants.ADV_PARAMETER_OWN_ADDRESS_TYPE,
                        AdvertisingSetParameters.ADDRESS_TYPE_RANDOM,
                    )
                )
                setTimeout(optInt(SnippetConstants.ADV_PARAMETER_TIMEOUT, 0))
                setTxPowerLevel(
                    optInt(
                        SnippetConstants.ADV_PARAMETER_TX_POWER_LEVEL,
                        AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                    )
                )
                setAdvertiseMode(
                    optInt(
                        SnippetConstants.ADV_PARAMETER_ADVERTISE_MODE,
                        AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
                    )
                )
                if (Build.VERSION.SDK_INT >= 34) {
                    setDiscoverable(optBoolean(SnippetConstants.ADV_PARAMETER_DISCOVERABLE, true))
                }
            }
            .build()

    private fun JSONObject.toAdvertiseData() =
        AdvertiseData.Builder()
            .apply {
                setIncludeDeviceName(
                    optBoolean(SnippetConstants.ADV_DATA_INCLUDE_DEVICE_NAME, true)
                )
                setIncludeTxPowerLevel(
                    optBoolean(SnippetConstants.ADV_DATA_INCLUDE_TX_POWER_LEVEL, true)
                )
                optJSONArray(SnippetConstants.ADV_DATA_SERVICE_UUID)?.toList<String>()?.forEach {
                    addServiceUuid(ParcelUuid.fromString(it))
                }
                optJSONArray(SnippetConstants.ADV_DATA_SERVICE_SOLICITATION_UUIDS)
                    ?.toList<String>()
                    ?.forEach { addServiceSolicitationUuid(ParcelUuid.fromString(it)) }
                optJSONObject(SnippetConstants.ADV_DATA_MANUFACTURER_DATA)
                    ?.toMap<String, JSONArray>()
                    ?.forEach { (id, data) ->
                        addManufacturerData(id.toInt(), data.toList<Byte>().toByteArray())
                    }
                optJSONObject(SnippetConstants.ADV_DATA_SERVICE_DATA)
                    ?.toMap<String, JSONArray>()
                    ?.forEach { (uuid, data) ->
                        addServiceData(
                            ParcelUuid.fromString(uuid),
                            data.toList<Byte>().toByteArray(),
                        )
                    }
            }
            .build()

    private fun JSONObject.toAdvertisingSetParameters() =
        AdvertisingSetParameters.Builder()
            .apply {
                setConnectable(optBoolean(SnippetConstants.ADV_PARAMETER_CONNECTABLE, false))
                setAnonymous(optBoolean(SnippetConstants.ADV_PARAMETER_ANONYMOUS, false))
                setIncludeTxPower(
                    optBoolean(SnippetConstants.ADV_DATA_INCLUDE_TX_POWER_LEVEL, false)
                )
                setScannable(optBoolean(SnippetConstants.ADV_PARAMETER_SCANNABLE, false))
                setLegacyMode(optBoolean(SnippetConstants.ADV_PARAMETER_LEGACY, false))
                setInterval(
                    optInt(
                        SnippetConstants.ADV_PARAMETER_INTERVAL,
                        AdvertisingSetParameters.INTERVAL_LOW,
                    )
                )
                setOwnAddressType(
                    optInt(
                        SnippetConstants.ADV_PARAMETER_OWN_ADDRESS_TYPE,
                        AdvertisingSetParameters.ADDRESS_TYPE_DEFAULT,
                    )
                )
                setPrimaryPhy(
                    optInt(SnippetConstants.ADV_PARAMETER_PRIMARY_PHY, BluetoothDevice.PHY_LE_1M)
                )
                setSecondaryPhy(
                    optInt(SnippetConstants.ADV_PARAMETER_SECONDARY_PHY, BluetoothDevice.PHY_LE_1M)
                )
                setTxPowerLevel(
                    optInt(
                        SnippetConstants.ADV_PARAMETER_TX_POWER_LEVEL,
                        AdvertisingSetParameters.TX_POWER_MEDIUM,
                    )
                )
                if (Build.VERSION.SDK_INT >= 34) {
                    setDiscoverable(optBoolean(SnippetConstants.ADV_PARAMETER_DISCOVERABLE, true))
                }
            }
            .build()

    private fun JSONObject.toBluetoothGattDescriptor() =
        BluetoothGattDescriptor(
            UUID.fromString(optString(SnippetConstants.FIELD_UUID)),
            optInt(SnippetConstants.GATT_FIELD_PERMISSIONS),
        )

    private fun JSONObject.toBluetoothGattCharacteristic() =
        BluetoothGattCharacteristic(
                UUID.fromString(optString(SnippetConstants.FIELD_UUID)),
                optInt(SnippetConstants.GATT_FIELD_PROPERTIES),
                optInt(SnippetConstants.GATT_FIELD_PERMISSIONS),
            )
            .apply {
                optJSONArray(SnippetConstants.GATT_FIELD_DESCRIPTORS)
                    ?.toList<JSONObject>()
                    ?.forEach { addDescriptor(it.toBluetoothGattDescriptor()) }
            }

    private fun JSONObject.toBluetoothGattService() =
        BluetoothGattService(
                UUID.fromString(optString(SnippetConstants.FIELD_UUID)),
                optInt(SnippetConstants.GATT_FIELD_TYPE),
            )
            .apply {
                optJSONArray(SnippetConstants.GATT_FIELD_CHARACTERISTICS)
                    ?.toList<JSONObject>()
                    ?.forEach { addCharacteristic(it.toBluetoothGattCharacteristic()) }
            }

    private fun JSONObject.toScanFilter() =
        ScanFilter.Builder()
            .apply {
                getOrNull<Int>(SnippetConstants.ADV_DATA_TYPE)?.let { setAdvertisingDataType(it) }
                getOrNull<String>(SnippetConstants.FIELD_NAME)?.let { setDeviceName(it) }
                getOrNull<String>(SnippetConstants.FIELD_DEVICE)?.let {
                    val addressType =
                        optInt(
                            SnippetConstants.FIELD_ADDRESS_TYPE,
                            BluetoothDevice.ADDRESS_TYPE_PUBLIC,
                        )
                    setDeviceAddress(it, addressType)
                }
                getOrNull<String>(SnippetConstants.ADV_DATA_SERVICE_UUID)?.let {
                    setServiceUuid(ParcelUuid.fromString(it))
                }
                getOrNull<String>(SnippetConstants.ADV_DATA_SERVICE_SOLICITATION_UUIDS)?.let {
                    setServiceSolicitationUuid(ParcelUuid.fromString(it))
                }
                optJSONObject(SnippetConstants.ADV_DATA_MANUFACTURER_DATA)?.let {
                    if (it.length() > 1) {
                        throw IllegalArgumentException(
                            "ScanFilter cannot have more than 1 manufacturerData"
                        )
                    }
                    val key = it.keys().asSequence().first()
                    setManufacturerData(
                        key.toInt(),
                        it.getJSONArray(key).toList<Byte>().toByteArray(),
                    )
                }
                optJSONObject(SnippetConstants.ADV_DATA_SERVICE_DATA)?.let {
                    if (it.length() > 1) {
                        throw IllegalArgumentException(
                            "ScanFilter cannot have more than 1 serviceData"
                        )
                    }
                    val key = it.keys().asSequence().first()
                    setServiceData(
                        ParcelUuid.fromString(key),
                        it.getJSONArray(key).toList<Byte>().toByteArray(),
                    )
                }
            }
            .build()

    private fun JSONObject.toScanSettings() =
        ScanSettings.Builder()
            .apply {
                getOrNull<Int>(SnippetConstants.SCAN_PARAM_SCAN_MODE)?.let { setScanMode(it) }
                getOrNull<Int>(SnippetConstants.SCAN_PARAM_CALLBACK_TYPE)?.let {
                    setCallbackType(it)
                }
                getOrNull<Int>(SnippetConstants.SCAN_PARAM_SCAN_RESULT_TYPE)?.let {
                    setScanResultType(it)
                }
                getOrNull<Int>(SnippetConstants.SCAN_PARAM_PHY)?.let { setPhy(it) }
                getOrNull<Boolean>(SnippetConstants.ADV_PARAMETER_LEGACY)?.let { setLegacy(it) }
                // org.json has a very strange behavior to get long value, so it's better to use
                // optString and do the conversion here.
                optString(SnippetConstants.SCAN_PARAM_REPORT_DELAY_MILLIS).let {
                    if (it.isNotEmpty() && it != "null") {
                        setReportDelay(it.toLong())
                    }
                }
            }
            .build()

    private fun JSONObject.toAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .apply {
                getOrNull<Int>(SnippetConstants.CONTENT_TYPE)?.let { setContentType(it) }
                getOrNull<Int>(SnippetConstants.FLAGS)?.let { setFlags(it) }
                getOrNull<Int>(SnippetConstants.USAGE)?.let { setUsage(it) }
                getOrNull<Int>(SnippetConstants.ALLOWED_CAPTURE_POLICY)?.let {
                    setAllowedCapturePolicy(it)
                }
                getOrNull<Int>(SnippetConstants.SPATIALIZATION_BEHAVIOR)?.let {
                    setSpatializationBehavior(it)
                }
            }
            .build()

    /**
     * Serializes JVM object [parameter] to a [JSONObject], or returns null if there is no viable
     * conversion.
     */
    override fun serialize(parameter: Any?): JSONObject? {
        uiAutomatorObjectConverter.serialize(parameter)?.let {
            return it
        }
        if (parameter is BluetoothGattService) {
            return parameter.toJson()
        }
        if (parameter is Call) {
            return parameter.toJson()
        }
        if (parameter is ScanResult) {
            return parameter.toJson()
        }
        return null
    }

    /** Deserializes [jsonObject] to [type], or returns null if there is no viable conversion. */
    override fun deserialize(jsonObject: JSONObject?, type: Type?): Any? {
        uiAutomatorObjectConverter.deserialize(jsonObject, type)?.let {
            return it
        }
        // TODO - b/313796355: Add parameter deserializers.
        if (type === AdvertiseSettings::class.java) {
            return jsonObject?.toAdvertiseSettings()
        }
        if (type === AdvertiseData::class.java) {
            return jsonObject?.toAdvertiseData()
        }
        if (type === AdvertisingSetParameters::class.java) {
            return jsonObject?.toAdvertisingSetParameters()
        }
        if (type === BluetoothGattService::class.java) {
            return jsonObject?.toBluetoothGattService()
        }
        if (type === ScanFilter::class.java) {
            return jsonObject?.toScanFilter()
        }
        if (type === ScanSettings::class.java) {
            return jsonObject?.toScanSettings()
        }
        if (type === AudioAttributes::class.java) {
            return jsonObject?.toAudioAttributes()
        }
        return null
    }
}
