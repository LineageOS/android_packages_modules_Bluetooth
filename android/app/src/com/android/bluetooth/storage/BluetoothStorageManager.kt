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

package com.android.bluetooth.storage

import android.bluetooth.BluetoothAdapter.AUDIO_MODE_DUPLEX
import android.bluetooth.BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothLeAudioCodecConfig
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothProfile.getProfileName
import android.bluetooth.BluetoothSinkAudioPolicy
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Pair
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.android.bluetooth.BluetoothEventLogger
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.storage.ActiveAudioPolicy.Type as ActiveAudioPolicy
import com.android.bluetooth.storage.MediaProfile.Type as MediaProfile
import com.android.bluetooth.storage.VoiceProfile.Type as VoiceProfile
import com.android.bluetooth.util.Column
import com.android.bluetooth.util.indent
import com.android.bluetooth.util.toTable
import com.android.internal.annotations.GuardedBy
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "BluetoothStorageManager"
private const val COMPACTION_THRESHOLD = 100_000

private val PATTERN_TO_OBFUSCATE = "(?:[0-9A-F]{2}:){4}([0-9A-F]{2}:[0-9A-F]{2})".toRegex()
private val PATTERN_DELETE_VALUE_FIELD = "^.*_value: \\d+$".toRegex()

private fun String.cleanProtoDump(): String {
    return this.lineSequence()
        .filterNot { it.matches(PATTERN_DELETE_VALUE_FIELD) }
        .filterNot { it.trimStart().startsWith("#") }
        .joinToString("\n")
}

private fun String.anonymizeAddress() = this.replace(PATTERN_TO_OBFUSCATE, "XX:XX:XX:XX:$1")

// The new storage manager for Bluetooth user data.
// This class is responsible for storing and retrieving user data using Proto DataStore.
class BluetoothStorageManager
@JvmOverloads
constructor(
    private val adapterService: AdapterService,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ioScope = CoroutineScope(dispatcher + SupervisorJob())

    private val eventLog = BluetoothEventLogger(50, "$TAG.EventLog") // Dumpsys logger

    private val MAX_UNBONDED_CACHE_SIZE = 20

    @GuardedBy("memoryOnlyCache")
    private val memoryOnlyCache =
        object : java.util.LinkedHashMap<String, Device>(MAX_UNBONDED_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Device>) =
                size > MAX_UNBONDED_CACHE_SIZE
        }

    // Represent current index of the connection.
    // This is only relevant to track most recently active devices.
    @Volatile private var currentConnectionCounter: Long = 0

    private fun UserStorage.Builder.getExistingOrNewDeviceBuilder(
        device: BluetoothDevice
    ): Device.Builder {
        return this.devicesMap[device.address]?.toBuilder()
            ?: run {
                val newDevice = Device.newBuilder()
                newDevice.connectionCounter = ++currentConnectionCounter
                newDevice
            }
    }

    // The DataStore instance that handles the UserStorage proto.
    // Data is stored in a file named "user_storage" in the app's device protected storage.
    private val dataStore by lazy {
        DataStoreFactory.createInDeviceProtectedStorage(
            context = adapterService,
            fileName = "user_storage",
            serializer = UserStorageSerializer,
            corruptionHandler =
                ReplaceFileCorruptionHandler {
                    // This handler is only trigger by a backwards incompatible Schema Changes
                    if (Build.TYPE == "userdebug" || Build.TYPE == "eng") {
                        // Loud crash on debug builds to facilitate error detection
                        throw it
                    }
                    Log.wtf(TAG, "Data corrupted. Resetting to default value.", it)
                    UserStorage.getDefaultInstance()
                },
            migrations =
                listOf(
                    MigrationFromRoomDatabase(adapterService),
                    MigrationFromAccessPermissions(adapterService),
                    MigrationFromAvrcpVolume(adapterService),
                ),
            scope = ioScope,
        )
    }

    // Synchronously reads the current state from DataStore.
    // [runBlocking] is used to bridge the suspend world of coroutines to the synchronous
    // world of the caller.
    // [dataStore.data.first()] is efficient because the [initialize] call
    // eagerly triggers the initial disk read, warming DataStore's in-memory cache.
    private val currentStorage: UserStorage
        get() {
            val storage = runBlocking { dataStore.data.first() }
            return storage
                .toBuilder()
                .apply {
                    val cacheCopy = synchronized(memoryOnlyCache) { memoryOnlyCache.toMap() }
                    putAllDevices(cacheCopy)
                }
                .build()
        }

    // Eagerly launch a coroutine to trigger the DataStore's serializer. This will perform the
    // initial disk read, run migrations, and populate the in-memory cache on a background thread.
    // This operation require the Context to be ready, hence why it is not started during the
    // constructor as the AdapterService doesn't have an attached context yet
    fun initialize() = ioScope.launch {
        val userStorage = dataStore.data.first()
        currentConnectionCounter =
            userStorage.devicesMap.values.maxOfOrNull { it.connectionCounter } ?: 0L
        userStorage.devicesMap.keys.forEach {
            Log.v(TAG, "Device loaded from disk: ${it.anonymizeAddress()}")
        }

        if (currentConnectionCounter > COMPACTION_THRESHOLD) {
            recompactConnectionCounter()
        }
        Log.v(TAG, "User storage ready")
    }

    /** Dump metadata changes for debugging purposes while keeping the address anonymized. */
    fun dump(sb: StringBuilder) {
        eventLog.dump(sb)

        val storage = currentStorage
        sb.appendLine("\nBluetoothStorageManager.Database:")

        val databaseDump = StringBuilder()
        databaseDump.appendLine(
            "active_a2dp_devices: ${storage.activeA2DpDevicesList.map { it.anonymizeAddress() }}"
        )
        databaseDump.appendLine(
            "active_hfp_devices: ${storage.activeHfpDevicesList.map { it.anonymizeAddress() }}"
        )

        val cachedAddresses = synchronized(memoryOnlyCache) { memoryOnlyCache.keys.toSet() }
        for ((address, device) in storage.devicesMap.toSortedMap()) {
            val isInMemory = cachedAddresses.contains(address)
            val locationTag =
                when {
                    cachedAddresses.contains(address) -> "[Memory Cache]"
                    else -> "[Disk]"
                }
            databaseDump.appendLine("\nDevice: ${address.anonymizeAddress()} $locationTag {")
            databaseDump.appendLine(dumpDevice(device).indent("  "))
            databaseDump.appendLine("}")
        }
        sb.appendLine(databaseDump.toString().indent("  "))
    }

    private fun dumpDevice(device: Device): String = buildString {
        appendLine("connection_counter: ${device.connectionCounter}")

        if (device.hasProfileConnectionPolicies()) {
            appendLine("Profile Connection Policies:")
            appendLine(
                device.profileConnectionPolicies
                    .toString()
                    .cleanProtoDump()
                    .replace("a2_dp", "a2dp") // Fix proto parsing of letter after a digit
                    .indent("  ")
            )
        }
        if (device.hasPermissions()) {
            appendLine("Permissions:")
            appendLine(device.permissions.toString().cleanProtoDump().indent("  "))
        }
        if (device.hasA2DpSettings()) {
            appendLine("A2DP Settings:")
            appendLine(device.a2DpSettings.toString().cleanProtoDump().indent("  "))
        }
        if (device.hasAvrcpSettings()) {
            appendLine("AVRCP Settings:")
            appendLine(device.avrcpSettings.toString().cleanProtoDump().indent("  "))
        }
        if (device.hasHfpClientSettings()) {
            appendLine("HFP Client Settings:")
            appendLine(device.hfpClientSettings.toString().cleanProtoDump().indent("  "))
        }
        if (device.hasLeAudioSettings()) {
            appendLine("LE Audio Settings:")
            appendLine(device.leAudioSettings.toString().cleanProtoDump().indent("  "))
        }
        if (device.hasMicrophonePreferredForCalls()) {
            appendLine("microphone_preferred_for_calls: ${device.microphonePreferredForCalls}")
        }
        if (device.keyMissingCount > 0) {
            appendLine("key_missing_count: ${device.keyMissingCount}")
        }

        if (device.customMetadataMap.isNotEmpty()) {
            appendLine("Custom Metadata:")
            val table =
                device.customMetadataMap.entries.toTable(
                    Column("Key", 4) { it.key },
                    Column("Value") { it.value.toStringUtf8() },
                )
            appendLine(table.indent("  "))
        }
    }

    fun onBondStateChanged(device: BluetoothDevice, fromState: Int, toState: Int) {
        if (toState == BOND_NONE) {
            removeDevice(device)
        } else if (fromState == BOND_BONDED) {
            // Remove the permissions for unbonded devices
            setMessageAccessPermission(device, BluetoothDevice.ACCESS_UNKNOWN)
            setPhonebookAccessPermission(device, BluetoothDevice.ACCESS_UNKNOWN)
            setSimAccessPermission(device, BluetoothDevice.ACCESS_UNKNOWN)
        } else if (toState == BOND_BONDED) {
            // Leverage blockingUpdateData logic to migrate to persistent storage
            dataStore.blockingUpdateData { storage ->
                logEvent(device, "Migrated to persistent storage")
                storage
            }
        }
    }

    /**
     * Initiates a graceful shutdown.
     *
     * This method waits for any ongoing storage operations to complete, and then cancels the
     * coroutine scope.
     */
    fun cleanup() {
        Log.d(TAG, "Cleanup started")

        runBlocking {
            removeUnbondedDevices()

            ioScope.cancel()
            Log.v(TAG, "ioScope cancelled.")
        }
    }

    fun getPreferredAudioProfiles(device: BluetoothDevice): Bundle {
        val proto = currentStorage.devicesMap[device.address]
        val leAudioSettings = proto?.leAudioSettings

        val outputProfileProto = leAudioSettings?.preferredOutputProfile ?: MediaProfile.UNKNOWN
        val inputProfileProto = leAudioSettings?.preferredInputProfile ?: VoiceProfile.UNKNOWN

        return Bundle().apply {
            if (outputProfileProto != MediaProfile.UNKNOWN) {
                putInt(AUDIO_MODE_OUTPUT_ONLY, fromMediaProfile(outputProfileProto))
            }
            if (inputProfileProto != VoiceProfile.UNKNOWN) {
                putInt(AUDIO_MODE_DUPLEX, fromVoiceProfile(inputProfileProto))
            }
        }
    }

    fun setPreferredAudioProfiles(groupDevices: List<BluetoothDevice>, bundle: Bundle) {
        val mediaProfile = toMediaProfile(bundle.getInt(AUDIO_MODE_OUTPUT_ONLY))
        val voiceProfile = toVoiceProfile(bundle.getInt(AUDIO_MODE_DUPLEX))

        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()

            groupDevices.forEach { device ->
                val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)
                val leAudioBuilder = deviceBuilder.leAudioSettings.toBuilder()

                if (mediaProfile != MediaProfile.UNKNOWN) {
                    leAudioBuilder.preferredOutputProfile = mediaProfile
                    logEvent(device, "preferred media profile is $mediaProfile")
                }
                if (voiceProfile != VoiceProfile.UNKNOWN) {
                    leAudioBuilder.preferredInputProfile = voiceProfile
                    logEvent(device, "preferred voice profile is $voiceProfile")
                }

                deviceBuilder.setLeAudioSettings(leAudioBuilder.build())
                builder.putDevices(device.address, deviceBuilder.build())
            }
            builder.build()
        }
    }

    fun getActiveAudioPolicy(device: BluetoothDevice): Int {
        val proto = currentStorage.devicesMap[device.address]
        val policy = proto?.leAudioSettings?.activeAudioPolicy ?: ActiveAudioPolicy.UNKNOWN
        return fromActiveAudioPolicy(policy)
    }

    fun setActiveAudioPolicy(device: BluetoothDevice, value: Int) {
        val newPolicy = toActiveAudioPolicy(value)
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val settingsBuilder = deviceBuilder.leAudioSettings.toBuilder()
            settingsBuilder.activeAudioPolicy = newPolicy

            logEvent(device, "active audio policy to $newPolicy")

            deviceBuilder.setLeAudioSettings(settingsBuilder.build())
            builder.putDevices(device.address, deviceBuilder.build()).build()
        }
    }

    private fun validateMetadataKey(key: Int) {
        if (key >= 0 && key <= BluetoothDevice.getMaxMetadataKey()) {
            return
        }
        throw IllegalArgumentException("Invalid metadata key: $key")
    }

    fun getCustomMetadata(device: BluetoothDevice, key: Int): ByteArray? {
        validateMetadataKey(key)
        val metadataMap = currentStorage.devicesMap[device.address]?.customMetadataMap
        val value = metadataMap?.get(key)
        if (value == null || value == ByteString.EMPTY) {
            return null
        }
        return value.toByteArray()
    }

    fun setCustomMetadata(device: BluetoothDevice, key: Int, value: ByteArray): Boolean {
        validateMetadataKey(key)

        var status = true
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val newByteString =
                if (value.isEmpty()) ByteString.EMPTY else ByteString.copyFrom(value)
            val oldByteString = deviceBuilder.customMetadataMap?.get(key) ?: ByteString.EMPTY

            if (oldByteString == newByteString) {
                status = false
                return@blockingUpdateData storage
            }

            adapterService.onMetadataChanged(device, key, value)
            logEvent(device, "Custom metadata changed for $key")

            val metadataBuilder = deviceBuilder.customMetadataMap.toMutableMap()
            if (value.isEmpty()) {
                metadataBuilder.remove(key)
            } else {
                metadataBuilder[key] = newByteString
            }
            deviceBuilder.clearCustomMetadata()
            deviceBuilder.putAllCustomMetadata(metadataBuilder)

            builder.putDevices(device.address, deviceBuilder.build()).build()
        }
        return status
    }

    fun getProfileConnectionPolicy(device: BluetoothDevice, profile: Int): Int {
        val accessor = getPolicyAccessor(profile)
        val proto = currentStorage.devicesMap[device.address] ?: return CONNECTION_POLICY_UNKNOWN
        return accessor.getter(proto.profileConnectionPolicies).toConnectionPolicy()
    }

    fun setProfileConnectionPolicy(device: BluetoothDevice, profile: Int, policy: Int) {
        val newValue = fromConnectionPolicy(policy)
        val accessor = getPolicyAccessor(profile)

        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val previousValue = accessor.getter(deviceBuilder.profileConnectionPolicies)
            if (previousValue == newValue) {
                return@blockingUpdateData storage
            }

            val oldPolicy = previousValue.toConnectionPolicy()
            logEvent(device, "${getProfileName(profile)} policy changed: $oldPolicy -> $policy")

            val policiesBuilder = deviceBuilder.profileConnectionPolicies.toBuilder()
            val newPolicies = accessor.setter(policiesBuilder, newValue).build()
            deviceBuilder.setProfileConnectionPolicies(newPolicies)

            builder.putDevices(device.address, deviceBuilder.build()).build()
        }
    }

    fun getAudioPolicyMetadata(device: BluetoothDevice) =
        currentStorage.devicesMap[device.address]?.hfpClientSettings?.let {
            BluetoothSinkAudioPolicy.Builder()
                .setCallEstablishPolicy(toSinkAudioPolicy(it.callEstablish))
                .setActiveDevicePolicyAfterConnection(
                    toSinkAudioPolicy(it.setActiveAfterConnection)
                )
                .setInBandRingtonePolicy(toSinkAudioPolicy(it.inBandRingtone))
                .build()
        } ?: BluetoothSinkAudioPolicy.Builder().build()

    fun setAudioPolicyMetadata(device: BluetoothDevice, policy: BluetoothSinkAudioPolicy) =
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val settingsBuilder = deviceBuilder.hfpClientSettings.toBuilder()
            settingsBuilder.callEstablish = fromSinkAudioPolicy(policy.callEstablishPolicy)
            settingsBuilder.setActiveAfterConnection =
                fromSinkAudioPolicy(policy.activeDevicePolicyAfterConnection)
            settingsBuilder.inBandRingtone = fromSinkAudioPolicy(policy.inBandRingtonePolicy)

            logEvent(device, "audio policy metadata to $policy")

            deviceBuilder.setHfpClientSettings(settingsBuilder.build())
            builder.putDevices(device.address, deviceBuilder.build()).build()
        }

    fun getA2dpOptionalCodecsSupported(device: BluetoothDevice): Int {
        val a2dpSettings = currentStorage.devicesMap[device.address]?.a2DpSettings
        val status =
            if (a2dpSettings?.hasOptionalCodecsSupported() == true) {
                a2dpSettings.optionalCodecsSupported
            } else {
                null
            }
        return toSupported(status)
    }

    fun setA2dpOptionalCodecsSupported(device: BluetoothDevice, value: Int) =
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val settingsBuilder = deviceBuilder.a2DpSettings.toBuilder()
            val newStatus = fromSupported(value)
            if (newStatus == null) {
                settingsBuilder.clearOptionalCodecsSupported()
            } else {
                settingsBuilder.optionalCodecsSupported = newStatus
            }
            logEvent(device, "a2dp optional codec supported is $value")

            deviceBuilder.setA2DpSettings(settingsBuilder.build())
            builder.putDevices(device.address, deviceBuilder.build()).build()
        }

    fun getA2dpOptionalCodecsEnabled(device: BluetoothDevice): Int {
        val a2dpSettings = currentStorage.devicesMap[device.address]?.a2DpSettings
        val status =
            if (a2dpSettings?.hasOptionalCodecsEnabled() == true) {
                a2dpSettings.optionalCodecsEnabled
            } else {
                null
            }
        return toPreference(status)
    }

    fun setA2dpOptionalCodecsEnabled(device: BluetoothDevice, value: Int) =
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val settingsBuilder = deviceBuilder.a2DpSettings.toBuilder()
            val newStatus = fromPreference(value)
            if (newStatus == null) {
                settingsBuilder.clearOptionalCodecsEnabled()
            } else {
                settingsBuilder.optionalCodecsEnabled = newStatus
            }
            logEvent(device, "a2dp optional codec enabled is $value")

            deviceBuilder.setA2DpSettings(settingsBuilder.build())
            builder.putDevices(device.address, deviceBuilder.build()).build()
        }

    fun getPhonebookAccessPermission(device: BluetoothDevice): Int {
        val permissions = currentStorage.devicesMap[device.address]?.permissions
        val status = if (permissions?.hasPhonebook() == true) permissions.phonebook else null
        return toAccess(status)
    }

    fun setPhonebookAccessPermission(device: BluetoothDevice, value: Int) {
        val newStatus = fromAccess(value)
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val permissions = deviceBuilder.permissions
            val oldStatus = if (permissions.hasPhonebook()) permissions.phonebook else null

            if (oldStatus == newStatus) {
                return@blockingUpdateData storage
            }
            logEvent(device, "Phonebook permission changed: $oldStatus -> $newStatus")
            val permissionsBuilder = permissions.toBuilder()
            if (newStatus == null) {
                permissionsBuilder.clearPhonebook()
            } else {
                permissionsBuilder.phonebook = newStatus
            }
            deviceBuilder.setPermissions(permissionsBuilder.build())
            builder.putDevices(device.address, deviceBuilder.build()).build()
        }
    }

    fun getMessageAccessPermission(device: BluetoothDevice): Int {
        val permissions = currentStorage.devicesMap[device.address]?.permissions
        val status = if (permissions?.hasMessage() == true) permissions.message else null
        return toAccess(status)
    }

    fun setMessageAccessPermission(device: BluetoothDevice, value: Int) {
        val newStatus = fromAccess(value)
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val permissions = deviceBuilder.permissions
            val oldStatus = if (permissions.hasMessage()) permissions.message else null

            if (oldStatus == newStatus) {
                return@blockingUpdateData storage
            }
            logEvent(device, "Message permission changed: $oldStatus -> $newStatus")
            val permissionsBuilder = permissions.toBuilder()
            if (newStatus == null) {
                permissionsBuilder.clearMessage()
            } else {
                permissionsBuilder.message = newStatus
            }
            deviceBuilder.setPermissions(permissionsBuilder.build())
            builder.putDevices(device.address, deviceBuilder.build()).build()
        }
    }

    fun getSimAccessPermission(device: BluetoothDevice): Int {
        val permissions = currentStorage.devicesMap[device.address]?.permissions
        val status = if (permissions?.hasSim() == true) permissions.sim else null
        return toAccess(status)
    }

    fun setSimAccessPermission(device: BluetoothDevice, value: Int) {
        val newStatus = fromAccess(value)
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val permissions = deviceBuilder.permissions
            val oldStatus = if (permissions.hasSim()) permissions.sim else null

            if (oldStatus == newStatus) {
                return@blockingUpdateData storage
            }
            logEvent(device, "SIM permission changed: $oldStatus -> $newStatus")
            val permissionsBuilder = permissions.toBuilder()
            if (newStatus == null) {
                permissionsBuilder.clearSim()
            } else {
                permissionsBuilder.sim = newStatus
            }
            deviceBuilder.setPermissions(permissionsBuilder.build())
            builder.putDevices(device.address, deviceBuilder.build()).build()
        }
    }

    fun getKeyMissingCount(device: BluetoothDevice): Int =
        currentStorage.devicesMap[device.address]?.keyMissingCount ?: -1

    fun updateKeyMissingCount(device: BluetoothDevice, isKeyMissingDetected: Boolean) =
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val newCount =
                if (isKeyMissingDetected) {
                    deviceBuilder.keyMissingCount + 1
                } else {
                    0
                }

            if (deviceBuilder.keyMissingCount == newCount) {
                return@blockingUpdateData storage
            }

            logEvent(device, "Key missing count: ${deviceBuilder.keyMissingCount} -> $newCount")
            deviceBuilder.keyMissingCount = newCount

            builder.putDevices(device.address, deviceBuilder.build()).build()
        }

    fun isMicrophonePreferredForCalls(device: BluetoothDevice): Boolean {
        val proto = currentStorage.devicesMap[device.address]
        return if (proto?.hasMicrophonePreferredForCalls() == true) {
            proto.microphonePreferredForCalls
        } else {
            true // Default value is true for this field
        }
    }

    fun setMicrophonePreferredForCalls(device: BluetoothDevice, enabled: Boolean) =
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            if (
                deviceBuilder.hasMicrophonePreferredForCalls() &&
                    deviceBuilder.microphonePreferredForCalls == enabled
            ) {
                return@blockingUpdateData storage
            }

            logEvent(device, "Microphone preferred for calls set to $enabled")
            deviceBuilder.microphonePreferredForCalls = enabled

            builder.putDevices(device.address, deviceBuilder.build()).build()
        }

    fun getAvrcpVolume(device: BluetoothDevice, defaultValue: Int): Int {
        val avrcpSettings = currentStorage.devicesMap[device.address]?.avrcpSettings
        return if (avrcpSettings?.hasVolume() == true) {
            avrcpSettings.volume
        } else {
            defaultValue
        }
    }

    fun setAvrcpVolume(device: BluetoothDevice, newVolume: Int) =
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)
            val settingsBuilder = deviceBuilder.avrcpSettings.toBuilder()

            if (settingsBuilder.hasVolume() && settingsBuilder.volume == newVolume) {
                return@blockingUpdateData storage
            }

            logEvent(device, "Storing AVRCP Volume = $newVolume")
            settingsBuilder.volume = newVolume

            deviceBuilder.setAvrcpSettings(settingsBuilder.build())
            builder.putDevices(device.address, deviceBuilder.build()).build()
        }

    fun getLeAudioCodecPreferences(
        devices: List<BluetoothDevice>
    ): Map<Int, Pair<BluetoothLeAudioCodecConfig, BluetoothLeAudioCodecConfig>> {
        val codecPreferences =
            mutableMapOf<Int, Pair<BluetoothLeAudioCodecConfig, BluetoothLeAudioCodecConfig>>()

        devices.forEach { device ->
            val settings = currentStorage.devicesMap[device.address]?.leAudioSettings
            settings?.codecPreferencesList?.forEach { preference ->
                if (preference.hasInput() && preference.hasOutput()) {
                    val input = fromProtoCodecConfig(preference.input)
                    val output = fromProtoCodecConfig(preference.output)
                    codecPreferences[output.codecType] = Pair.create(input, output)
                }
            }
        }
        return codecPreferences
    }

    fun setLeAudioCodecPreferences(
        devices: List<BluetoothDevice>,
        codecPreferences: Map<Int, Pair<BluetoothLeAudioCodecConfig, BluetoothLeAudioCodecConfig>>,
    ) = dataStore.blockingUpdateData { storage ->
        val builder = storage.toBuilder()
        devices.forEach { device ->
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)

            val settingsBuilder = deviceBuilder.leAudioSettings.toBuilder()
            settingsBuilder.clearCodecPreferences()
            codecPreferences.values.forEach { pair ->
                settingsBuilder.addCodecPreferences(
                    LeAudioCodecPreference.newBuilder()
                        .setInput(toProtoCodecConfig(pair.first))
                        .setOutput(toProtoCodecConfig(pair.second))
                        .build()
                )
            }

            deviceBuilder.setLeAudioSettings(settingsBuilder.build())
            builder.putDevices(device.address, deviceBuilder.build())
        }
        builder.build()
    }

    /**
     * Gets the most recently connected bluetooth devices in order with most recently connected
     * first and least recently connected last.
     *
     * @return a [List] of [BluetoothDevice] representing connected bluetooth devices in order of
     *   most recently connected.
     */
    fun getMostRecentlyConnectedDevices(): List<BluetoothDevice> =
        currentStorage.devicesMap.entries
            .sortedByDescending { it.value.connectionCounter }
            .mapNotNull { (address, _) -> adapterService.getDeviceFromAddress(address) }

    /**
     * Gets the most recently connected bluetooth device in a given list.
     *
     * @param devicesList the list of [BluetoothDevice] to search in
     * @return the most recently connected [BluetoothDevice] in the given `devicesList`, or null if
     *   an error occurred
     */
    fun getMostRecentlyConnectedDeviceInList(devicesList: List<BluetoothDevice>): BluetoothDevice? =
        devicesList
            .mapNotNull { device ->
                currentStorage.devicesMap[device.address]?.let { deviceData ->
                    Pair(device, deviceData.connectionCounter)
                }
            }
            .maxByOrNull { it.second }
            ?.first

    /**
     * Gets the least recently connected bluetooth device in a given list.
     *
     * @param devicesList the list of [BluetoothDevice] to search in
     * @return the least recently connected [BluetoothDevice] in the given `devicesList`, or null
     */
    fun getLeastRecentlyConnectedDeviceInList(
        devicesList: List<BluetoothDevice>
    ): BluetoothDevice? =
        devicesList
            .mapNotNull { device ->
                currentStorage.devicesMap[device.address]?.let { deviceData ->
                    Pair(device, deviceData.connectionCounter)
                }
            }
            .minByOrNull { it.second }
            ?.first

    /**
     * Gets the last active a2dp device
     *
     * @return the most recently active a2dp device or null if the last a2dp device was null
     */
    fun getMostRecentlyActiveA2dpDevice(): BluetoothDevice? =
        currentStorage.activeA2DpDevicesList.lastOrNull()?.let {
            adapterService.getDeviceFromAddress(it)
        }

    /** @return the list of most recently active HFP devices */
    fun getMostRecentlyActiveHfpDevices(): List<BluetoothDevice> =
        currentStorage.activeHfpDevicesList
            .mapNotNull { adapterService.getDeviceFromAddress(it) }
            .reversed()

    /**
     * Updates the storage when a device connects.
     *
     * This method updates the `last_active_time` for the device and, if a `profileId` is provided,
     * sets the device as active for that profile.
     *
     * @param device The remote Bluetooth device that has connected.
     * @param profileId The profile ID from [BluetoothProfile] that is now active, or `null` if no
     *   profile's active status needs to be updated.
     */
    fun onDeviceConnected(device: BluetoothDevice, profileId: Int) =
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()
            val deviceBuilder = builder.getExistingOrNewDeviceBuilder(device)
            if (deviceBuilder.connectionCounter != currentConnectionCounter) {
                deviceBuilder.connectionCounter = ++currentConnectionCounter
            }
            val address = device.address
            builder.putDevices(address, deviceBuilder.build())

            // Update active device for the given profile
            when (profileId) {
                BluetoothProfile.A2DP -> {
                    val devices = builder.activeA2DpDevicesList.filter { it != address }
                    builder.clearActiveA2DpDevices().addAllActiveA2DpDevices(devices)
                    builder.addActiveA2DpDevices(address)
                    logEvent(device, "active A2DP contains: ${builder.activeA2DpDevicesList}")
                }
                BluetoothProfile.HEADSET -> {
                    val devices = builder.activeHfpDevicesList.filter { it != address }
                    builder.clearActiveHfpDevices().addAllActiveHfpDevices(devices)
                    builder.addActiveHfpDevices(address)
                    logEvent(device, "active HFP contains: ${builder.activeHfpDevicesList}")
                }
            }
            builder.build()
        }

    /**
     * Updates the storage when a device disconnects for a specific profile.
     *
     * This method removes the device from the active list for the given profile.
     *
     * @param device The remote Bluetooth device that has disconnected.
     * @param profileId The profile ID from [BluetoothProfile] that is no longer active.
     */
    fun onDeviceDisconnected(device: BluetoothDevice, profileId: Int) =
        dataStore.blockingUpdateData { storage ->
            val builder = storage.toBuilder()

            when (profileId) {
                BluetoothProfile.A2DP -> {
                    val devices = builder.activeA2DpDevicesList.filter { it != device.address }
                    builder.clearActiveA2DpDevices().addAllActiveA2DpDevices(devices)
                    logEvent(device, "no longer A2DP active. Remains $devices")
                }
                BluetoothProfile.HEADSET -> {
                    val devices = builder.activeHfpDevicesList.filter { it != device.address }
                    builder.clearActiveHfpDevices().addAllActiveHfpDevices(devices)
                    logEvent(device, "no longer HFP active. Remains $devices")
                }
            }
            builder.build()
        }

    /** Removes a device from storage */
    private fun removeDevice(device: BluetoothDevice) = dataStore.blockingUpdateData { storage ->
        logEvent(device, "Remove from storage")
        val builder = storage.toBuilder()

        builder.removeDevices(device.address)

        val a2dpDevices = builder.activeA2DpDevicesList.filter { it != device.address }
        builder.clearActiveA2DpDevices().addAllActiveA2DpDevices(a2dpDevices)

        val hfpDevices = builder.activeHfpDevicesList.filter { it != device.address }
        builder.clearActiveHfpDevices().addAllActiveHfpDevices(hfpDevices)

        builder.build()
    }

    private suspend fun recompactConnectionCounter() = dataStore.updateStorageData { storage ->
        Log.d(TAG, "Re-compacting the connection counter")

        val sortedDevices = storage.devicesMap.entries.sortedBy { it.value.connectionCounter }

        val builder = storage.toBuilder()

        var newConnectionNumber = 0L
        for (entry in sortedDevices) {
            val address = entry.key
            val proto = entry.value

            val deviceBuilder = proto.toBuilder()
            deviceBuilder.connectionCounter = ++newConnectionNumber
            builder.putDevices(address, deviceBuilder.build())
        }

        currentConnectionCounter = newConnectionNumber
        builder.build()
    }

    // TODO: Remove this method and its call in cleanup() in a few months after release.
    // It is kept for now to ensure devices released with the previous code still trigger the
    // removal of old unbonded devices from disk.
    private suspend fun removeUnbondedDevices() {
        val bondedAddresses = adapterService.bondedDevices.map { it.address }.toSet()

        dataStore.updateData { storage ->
            val storedAddresses = storage.devicesMap.keys

            val unbondedAddresses = storedAddresses.filter { !bondedAddresses.contains(it) }

            if (unbondedAddresses.isEmpty()) {
                return@updateData storage
            }

            Log.i(TAG, "Removing ${unbondedAddresses.size} unbonded devices from storage")

            // Remove the devices from the map in a single batch operation.
            val newBuilder = storage.toBuilder()
            unbondedAddresses.forEach { address -> newBuilder.removeDevices(address) }

            // Remove unbonded devices from active lists
            val newA2dpActives =
                newBuilder.activeA2DpDevicesList.filter { bondedAddresses.contains(it) }
            newBuilder.clearActiveA2DpDevices().addAllActiveA2DpDevices(newA2dpActives)

            val newHfpActives =
                newBuilder.activeHfpDevicesList.filter { bondedAddresses.contains(it) }
            newBuilder.clearActiveHfpDevices().addAllActiveHfpDevices(newHfpActives)

            newBuilder.build()
        }
    }

    private suspend fun DataStore<UserStorage>.updateStorageData(
        transform: suspend (UserStorage) -> UserStorage
    ): UserStorage {
        val bondedAddr = adapterService.bondedDevices.map { it.address }.toSet()

        var pendingCacheUpdates: Map<String, Device>? = null
        var pendingRemovals: Set<String>? = null

        // Note: DataStore is designed to retry `updateData` automatically.
        // We don't want to mutate the cache in it.
        val finalStorage = updateData { storageFromDisk ->
            val cacheCopy = synchronized(memoryOnlyCache) { memoryOnlyCache.toMap() }

            // Merge disk storage with memory cache
            val mergedStorage =
                storageFromDisk.toBuilder().apply { putAllDevices(cacheCopy) }.build()

            // Apply database transformation on all devices
            val updatedStorage = transform(mergedStorage)
            val builder = updatedStorage.toBuilder()

            // List all unbonded devices in the storage
            val unbondedAddr = updatedStorage.devicesMap.keys.filter { !bondedAddr.contains(it) }

            val tempCacheUpdates = mutableMapOf<String, Device>()

            unbondedAddr.forEach { address ->
                val deviceProto = updatedStorage.devicesMap[address]!!

                // Compare against the snapshot, to avoids accidental LRU bumps.
                val existing = cacheCopy[address]
                if (existing != deviceProto) {
                    tempCacheUpdates[address] = deviceProto
                }

                // Remove the non-bonded devices from persistent storage
                builder.removeDevices(address)
            }

            pendingCacheUpdates = tempCacheUpdates
            val deletedAddresses = mergedStorage.devicesMap.keys - updatedStorage.devicesMap.keys
            pendingRemovals = bondedAddr + deletedAddresses

            builder.build()
        }

        if (pendingCacheUpdates != null && pendingRemovals != null) {
            synchronized(memoryOnlyCache) {
                // Remove newly bonded devices from memoryOnlyCache
                memoryOnlyCache.keys.removeAll(pendingRemovals!!)

                // Putting back devices that actually changed will bumps their LRU order.
                pendingCacheUpdates!!.forEach { (address, deviceProto) ->
                    memoryOnlyCache[address] = deviceProto
                }
            }
        }

        return finalStorage
    }

    private fun DataStore<UserStorage>.blockingUpdateData(
        transform: suspend (UserStorage) -> UserStorage
    ) = runBlocking { updateStorageData(transform) }

    /** Logs a metadata change event for dumpsys. */
    private fun logEvent(device: BluetoothDevice, log: String) {
        eventLog.logi(TAG, "$device: ${log.anonymizeAddress()}")
    }

    // Serializer for the UserStorage proto to tells DataStore how to read and write the data.
    private object UserStorageSerializer : Serializer<UserStorage> {
        // The default value to be used if the file does not exist yet.
        override val defaultValue: UserStorage = UserStorage.getDefaultInstance()

        override suspend fun readFrom(input: InputStream): UserStorage {
            try {
                return UserStorage.parseFrom(input) // Method generated by the protobuf compiler.
            } catch (exception: InvalidProtocolBufferException) {
                throw CorruptionException("Cannot read proto.", exception)
            }
        }

        override suspend fun writeTo(t: UserStorage, output: OutputStream) {
            t.writeTo(output) // Method generated by the protobuf compiler.
        }
    }
}
