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

#pragma once

#include <bluetooth/types/address.h>

#include <memory>
#include <vector>

#include "bta/le_audio/le_audio_types.h"
#include "stack/include/gatt_api.h"

namespace bluetooth::le_audio {

/**
 * @brief Published Audio Capabilities (PAC) Service.
 *
 * This class provides an API to manage the PAC GATT service, which is part of
 * the Basic Audio Profile (BAP). It handles the registration of the GATT
 * service, manages connections from clients, and allows for dynamic updates of
 * characteristic values.
 *
 * This service exposes:
 * - Sink/Source PAC characteristics
 * - Sink/Source Audio Locations
 * - Available Audio Contexts
 * - Supported Audio Contexts
 */
class Pacs {
public:
  /**
   * @brief Represents a single Published Audio Capability (PAC) record.
   */
  struct PacRecord {
    /** The unique identifier for the audio codec (e.g., LC3). */
    le_audio::types::LeAudioCodecId codec_id;
    /** Codec-specific capabilities (e.g., supported sampling frequencies). */
    std::vector<uint8_t> codec_spec_caps;
    /** Codec-specific metadata. */
    std::vector<uint8_t> metadata;

    bool operator==(const PacRecord& other) const {
      return codec_id == other.codec_id && codec_spec_caps == other.codec_spec_caps &&
             metadata == other.metadata;
    }
  };

  /**
   * @brief A set of PAC records.
   *
   * Each set of PAC records will become a separate PAC characteristic in the
   * GATT service.
   */
  struct PacSet {
    /** Unique identifier for this set of PAC records. */
    uint8_t id;
    /** A list of PAC records in this set. */
    std::vector<PacRecord> records;

    bool operator==(const PacSet& other) const {
      return id == other.id && records == other.records;
    }
  };

  /**
   * @brief Defines the GATT database structure and initial values for the PAC
   * service.
   *
   * This struct describes the static (loaded at init) values for PAC, Audio
   * Locations, and Supported Audio Contexts characteristics. In contrast, the
   * Available Audio Context characteristic value is dynamic and managed
   * separately.
   */
  struct ServiceDescriptor {
    // TODO: Need to limit the PAC characteristic count and reserve set of immutable handles
    /** Sink and Source PAC sets. */
    le_audio::types::BidirectionalPair<std::vector<PacSet>> pac_sets;
    /** Sink and Source audio locations. */
    le_audio::types::BidirectionalPair<le_audio::types::AudioLocations> audio_locations;
    /**
     * @brief Whether the audio locations characteristics should be writable.
     *
     * If set to true, the corresponding Audio Location characteristic (Sink or
     * Source) will be writable by remote devices. Defaults to false.
     */
    le_audio::types::BidirectionalPair<bool> audio_locations_writable = {false, false};
    /** Sink and Source supported audio contexts. */
    le_audio::types::BidirectionalPair<le_audio::types::AudioContexts> supported_audio_contexts;
  };

  /**
   * @brief Callbacks for the PAC service to notify of events.
   *
   * An implementation of this interface must be provided to the
   * `RegisterGattService` method.
   */
  struct Callbacks {
    virtual ~Callbacks() = default;
    /**
     * @brief Called when the PAC GATT service has been registered.
     */
    virtual void OnPacsRegistered(void) = 0;
    /**
     * @brief Called when a remote device connects to the service.
     * @param pseudo_addr The address of the connected device.
     */
    virtual void OnDeviceConnected(const RawAddress& pseudo_addr) = 0;
    /**
     * @brief Called when a remote device disconnects from the service.
     * @param pseudo_addr The address of the disconnected device.
     */
    virtual void OnDeviceDisconnected(const RawAddress& pseudo_addr) = 0;

    /**
     * @brief Called when a remote device reads the Available Audio Contexts
     * characteristic.
     *
     * This allows for a customized audio context availability response for a
     * specific device.
     * @param pseudo_addr The address of the device requesting the contexts.
     * @return A pair of sink and source available audio contexts.
     */
    virtual le_audio::types::BidirectionalPair<le_audio::types::AudioContexts>
    OnGetAvailableAudioContexts(const RawAddress& pseudo_addr) = 0;
    /**
     * @brief Called when a remote device writes to the Audio Locations
     * characteristic.
     *
     * This allows for handling of audio location changes initiated by a remote
     * device.
     * @param pseudo_addr The address of the device writing the locations.
     * @param direction The direction of the characteristic (sink or source).
     * @param audio_locations The new audio locations.
     */
    virtual void OnAudioLocationsWritten(
            const RawAddress& pseudo_addr, uint8_t direction,
            const le_audio::types::AudioLocations& audio_locations) = 0;
  };

  /**
   * @brief Destroys the Pacs instance and cleans up resources.
   */
  virtual ~Pacs();
  /**
   * @brief Registers the PAC GATT service with the given descriptor and
   * callbacks.
   *
   * This method should be called once to initialize the service.
   *
   * @param service_descriptor The initial static values for the service
   * characteristics.
   * @param callbacks The callback handler for service events.
   */
  virtual void RegisterGattService(const ServiceDescriptor& service_descriptor,
                                   Callbacks* callbacks);

  /**
   * @brief Updates the current audio context availability for a specific
   * device.
   *
   * GATT attribute value notifications will be sent automatically if the remote
   * device is subscribed for notifications or indications.
   *
   * @param pseudo_addr The address of the device to notify.
   * @param contexts The new available audio contexts for sink and source.
   */
  virtual void UpdateAvailableAudioContexts(
          const RawAddress& pseudo_addr,
          const le_audio::types::BidirectionalPair<le_audio::types::AudioContexts>& contexts);
  /**
   * @brief Updates the audio channel locations for both sink and source.
   *
   * This will notify subscribed clients about the change.
   *
   * @param audio_locations The new audio locations for sink and source.
   */
  virtual void UpdateAudioChannelLocations(
          const le_audio::types::BidirectionalPair<le_audio::types::AudioLocations>&
                  audio_locations);
  /**
   * @brief Updates a specific PAC set with new records.
   *
   * This will notify subscribed clients about the change.
   *
   * @param pac_set_id The ID of the PAC set to update.
   * @param records The new list of PAC records for the set.
   */
  virtual void UpdatePacSet(uint8_t pac_set_id, const std::vector<PacRecord>& records);
  /**
   * @brief Gets the connection ID for a connected device.
   *
   * @param pseudo_addr The address of the device.
   * @return The connection ID if the device is connected, otherwise
   * `GATT_INVALID_CONN_ID`.
   */
  uint16_t GetConnectionId(const RawAddress& pseudo_addr) const;
  /**
   * @brief Confirms that the audio locations write operation has been
   * processed.
   *
   * @param pseudo_addr The address of the device.
   * @param accepted Whether the change was accepted.
   */
  virtual void ConfirmAudioLocationsWritten(const RawAddress& pseudo_addr, bool accepted);
  /**
   * @brief Dumps the state of the service to the given stream.
   *
   * @param stream The string stream to write the dump to.
   */
  virtual void Dump(std::stringstream& stream) const;

protected:
  /**
   * @brief Constructs a Pacs instance.
   */
  Pacs();

  /**
   * @brief Disallow cloning due to static GATT callbacks
   */
  Pacs(const Pacs&) = delete;
  Pacs& operator=(const Pacs&) = delete;

private:
  // Separates the implementation details from the interface
  struct service_impl;
  std::unique_ptr<service_impl> service_impl_;
};

/**
 * @brief Factory method to get a shared instance of the Pacs.
 *
 * This is required to manage a single static (but shared) instance, needed by
 * the static GATT callback API.
 *
 * @return A shared pointer to the Pacs instance.
 */
std::shared_ptr<Pacs> InstantiatePacs();
/**
 * @brief Releases the shared instance of the Pacs.
 *
 * @param shared_instance The shared pointer obtained from
 * `InstantiatePacs`.
 */
void ReleasePacs(std::shared_ptr<Pacs> shared_instance);

}  // namespace bluetooth::le_audio
