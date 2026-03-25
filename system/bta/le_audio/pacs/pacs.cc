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

#include "pacs.h"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/memory/weak_ptr.h>
#include <bluetooth/log.h>

#include <map>

#include "bta/include/bta_gatt_api.h"
#include "bta/le_audio/common/gatt_client_data_tracker.h"
#include "bta/le_audio/le_audio_types.h"

// Generated packet headers:
#include "pacs/pacs_packets.h"

using bluetooth::stack::tGATT_REQ_CBACK;

namespace bluetooth::le_audio {

using types::AudioContexts;
using types::AudioLocations;
using types::BidirectionalPair;

static const uint16_t kGattInvalidHandle = 0x0000;

// Static instance lifetime management
static std::shared_ptr<Pacs> instance = nullptr;
std::shared_ptr<Pacs> InstantiatePacs() {
  if (!instance) {
    class ConstructablePacs : public Pacs {};
    instance = std::make_shared<ConstructablePacs>();
  }
  return instance;
}

void ReleasePacs(std::shared_ptr<Pacs> shared_instance) {
  if (instance.get() != shared_instance.get()) {
    log::error("Not a valid LeAudioGattServer instance!");
    return;
  }
  // Just let the returned shared_instance to go out of scope to release it,
  // and release the static instance.
  instance.reset();
}

// Implementation
struct Pacs::service_impl {
  int server_if_ = 0;
  uint16_t service_handle_ = 0;
  Pacs::Callbacks* callbacks_ = nullptr;

  struct PacsDevice {
    // Used to avoid notifying the same value multiple times
    BidirectionalPair<AudioContexts> last_notified_audio_contexts;
    BidirectionalPair<AudioLocations> last_notified_audio_locations;
  };
  // Tracks the PAC service data for each individual remote device
  GattClientDataTracker<PacsDevice> device_tracker_;

  // Global (not-per-device) values for PAC service characteristics
  struct {
    BidirectionalPair<AudioContexts> supported_audio_contexts;
    BidirectionalPair<AudioLocations> audio_locations;
    BidirectionalPair<std::map<uint16_t, PacSet>> pac_sets_by_char_handle;
  } global_char_values_;

  // Cached local ATT handle needed for dynamic value update and notification sending
  uint16_t available_audio_context_handle_ = kGattInvalidHandle;
  BidirectionalPair<uint16_t> audio_channel_allocation_handle_ = {.sink = kGattInvalidHandle,
                                                                  .source = kGattInvalidHandle};

  // Used to find the characteristic type by ATT value handle
  struct GattCharacteristicMetadata {
    uint16_t uuid;
    uint16_t cccd_handle = kGattInvalidHandle;
  };
  std::map<uint16_t, GattCharacteristicMetadata> char_metadata_by_value_handle_;

  // Warning: This descriptor is used only for the purpose of GATT service instantiation
  std::optional<Pacs::ServiceDescriptor> pending_gatt_svc_descriptor_ = std::nullopt;

  // Control point operation data
  struct CtpRequest {
    uint32_t trans_id;
    tCONN_ID conn_id;
    bool need_rsp;
  };
  std::map<RawAddress, CtpRequest> pending_request_by_address_;

  // Member variables should appear before the WeakPtrFactory, to ensure
  // that any WeakPtrs are invalidated before its members
  // variable's destructors are executed, rendering them invalid.
  base::WeakPtrFactory<Pacs::service_impl> weak_factory_{this};

  service_impl() = default;
  ~service_impl() {
    if (server_if_ != 0) {
      BTA_GATTS_AppDeregister(server_if_);
    }
    log::info("PACS GATT Server deregistered.");
  }

  void RegisterGattService(const Pacs::ServiceDescriptor& service_descriptor,
                           Pacs::Callbacks* callbacks) {
    if (server_if_ != 0) {
      log::error("Already registered at server_if={}", server_if_);
      return;
    }

    if (callbacks == nullptr) {
      log::error("Null callbacks provided");
      return;
    }

    // Make sure that all records have unique IDs
    std::set<uint8_t> pac_set_ids;
    for (auto const& record : service_descriptor.pac_sets.sink) {
      if (pac_set_ids.count(record.id) != 0) {
        log::error("Duplicate Sink PAC record ID {} found", record.id);
        return;
      }
      pac_set_ids.insert(record.id);
    }
    for (auto const& record : service_descriptor.pac_sets.source) {
      if (pac_set_ids.count(record.id) != 0) {
        log::error("Duplicate Source PAC record ID {} found", record.id);
        return;
      }
      pac_set_ids.insert(record.id);
    }

    if (service_descriptor.pac_sets.sink.empty() && service_descriptor.pac_sets.source.empty()) {
      log::error("PACS must have at least one Sink or Source PAC set.");
      return;
    }

    pending_gatt_svc_descriptor_ = service_descriptor;

    callbacks_ = callbacks;

    static bluetooth::stack::tGATT_REQ_CBACK pacs_callbacks = {
            .read_characteristic_cb = OnGattReadCharacteristicStatic,
            .read_descriptor_cb = OnGattReadDescriptorStatic,
            .write_characteristic_cb = OnGattWriteCharacteristicStatic,
            .write_descriptor_cb = OnGattWriteDescriptorStatic,
            .exec_write_cb = tGATT_REQ_CBACK::do_nothing,
            .mtu_changed_cb = tGATT_REQ_CBACK::do_nothing,
            .conf_cb = tGATT_REQ_CBACK::do_nothing,
    };

    static const stack::tGATT_CBACK pacs_ops = {
            .p_conn_cb = OnGattConnStatic,
            .p_req_cb = &pacs_callbacks,
    };

    server_if_ = BTA_GATTS_AppRegister(uuid::kPublishedAudioCapabilityServiceUuid, &pacs_ops,
                                       true /* eatt_support */);
    log::assert_that(server_if_ != stack::GATT_IF_INVALID, "Failed to register GATT Server");
    log::info("GATT Server Registered with server_if: {}", server_if_);

    log::assert_that(pending_gatt_svc_descriptor_.has_value(), "Empty service descriptor!");
    auto gatt_db = BuildGattDatabase(pending_gatt_svc_descriptor_.value());

    log::info("Adding LE Audio Service {} service to GATT database.", gatt_db.begin()->uuid);
    auto status = BTA_GATTS_AddService(server_if_, &gatt_db);
    log::info("GATT Service Add status: {}, server_if: {}", gatt_status_text(status), server_if_);

    log::assert_that(status == GATT_SERVICE_STARTED, "Unable to add GATT service");
    log::assert_that(gatt_db.size() != 0, "Service is empty");
    log::assert_that(gatt_db.begin()->uuid == uuid::kPublishedAudioCapabilityServiceUuid,
                     "Service not mine!");
    log::assert_that(pending_gatt_svc_descriptor_.has_value(), "Empty service descriptor!");

    GattCharacteristicMetadata* last_char_metadata = nullptr;

    for (const auto& element : gatt_db) {
      if (element.type == BTGATT_DB_CHARACTERISTIC) {
        log::info("Characteristic added: UUID {}, handle:0x{:04x}", element.uuid.ToString(),
                  element.attribute_handle);
        char_metadata_by_value_handle_[element.attribute_handle] = {.uuid = element.uuid.As16Bit()};
        // Keep the pointer to the last discovered characteristic metadata to add CCCD handle info
        last_char_metadata = &char_metadata_by_value_handle_.at(element.attribute_handle);

        // Store the PAC set for this particular PAC characteristic, there is an equal
        // number of both since each PAC set maps to one PAC characteristic.
        if (element.uuid == uuid::kSinkPublishedAudioCapabilityCharacteristicUuid) {
          global_char_values_.pac_sets_by_char_handle.sink[element.attribute_handle] =
                  pending_gatt_svc_descriptor_->pac_sets.sink.at(
                          global_char_values_.pac_sets_by_char_handle.sink.size());
        } else if (element.uuid == uuid::kSourcePublishedAudioCapabilityCharacteristicUuid) {
          global_char_values_.pac_sets_by_char_handle.source[element.attribute_handle] =
                  pending_gatt_svc_descriptor_->pac_sets.source.at(
                          global_char_values_.pac_sets_by_char_handle.source.size());
        } else if (element.uuid == uuid::kAvailableAudioContextsCharacteristicUuid) {
          // Note: The value will be provided dynamically for each remote device -
          //       we need to keep the ATT handle for that.
          available_audio_context_handle_ = element.attribute_handle;
        } else if (element.uuid == uuid::kSupportedAudioContextsCharacteristicUuid) {
          global_char_values_.supported_audio_contexts =
                  pending_gatt_svc_descriptor_->supported_audio_contexts;
        } else if (element.uuid == uuid::kSinkAudioLocationCharacteristicUuid) {
          audio_channel_allocation_handle_.sink = element.attribute_handle;
          global_char_values_.audio_locations.sink =
                  pending_gatt_svc_descriptor_->audio_locations.sink;
        } else if (element.uuid == uuid::kSourceAudioLocationCharacteristicUuid) {
          audio_channel_allocation_handle_.source = element.attribute_handle;
          global_char_values_.audio_locations.source =
                  pending_gatt_svc_descriptor_->audio_locations.source;
        } else {
          log::assert_that(false, "Unknown characteristic uuid: {} found", element.uuid.ToString());
        }

      } else if (element.type == BTGATT_DB_DESCRIPTOR) {
        log::assert_that(element.uuid == Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG),
                         "Unknown descriptor uuid: {} found at handle: 0x{:04x}",
                         element.uuid.ToString(), element.attribute_handle);

        // Match the descriptor with the previous characteristic declaration
        log::assert_that(last_char_metadata, "No known characteristic for the added descriptor");
        last_char_metadata->cccd_handle = element.attribute_handle;

      } else if (element.type == BTGATT_DB_PRIMARY_SERVICE) {
        log::info("Service handle:0x{:04x}, UUID: {}", element.attribute_handle,
                  element.uuid.ToString());
        if (element.uuid == uuid::kPublishedAudioCapabilityServiceUuid) {
          service_handle_ = element.attribute_handle;
        }
      }
    }

    // We are done with service creation - any data from the descriptor if needed, were already
    // repacked to service data containers for the more optimal access.
    pending_gatt_svc_descriptor_.reset();
    callbacks_->OnPacsRegistered();
  }

  static void OnGattConnStatic(tGATT_IF /*server_if*/, const RawAddress& remote_bda,
                               tCONN_ID conn_id, bool connected, tGATT_DISCONN_REASON /*reason*/,
                               tBT_TRANSPORT transport) {
    if (instance) {
      if (connected) {
        instance->service_impl_->OnGattConnect(remote_bda, conn_id, transport);
      } else {
        instance->service_impl_->OnGattDisconnect(remote_bda, conn_id);
      }
    }
  }

  static void OnGattReadCharacteristicStatic(tCONN_ID conn_id, uint32_t trans_id,
                                             const RawAddress& remote_bda, uint16_t handle,
                                             uint16_t offset, bool is_long) {
    if (instance) {
      instance->service_impl_->OnGattReadCharacteristic(conn_id, trans_id, remote_bda, handle,
                                                        offset, is_long);
    }
  }

  static void OnGattWriteCharacteristicStatic(tCONN_ID conn_id, uint32_t trans_id,
                                              const RawAddress& remote_bda, uint16_t handle,
                                              uint16_t offset, bool need_rsp, bool is_prep,
                                              uint8_t* value, uint16_t len) {
    if (instance) {
      instance->service_impl_->OnGattWriteCharacteristic(conn_id, trans_id, remote_bda, handle,
                                                         offset, need_rsp, is_prep, value, len);
    }
  }

  static void OnGattReadDescriptorStatic(tCONN_ID conn_id, uint32_t trans_id,
                                         const RawAddress& /*remote_bda*/, uint16_t handle,
                                         uint16_t offset, bool is_long) {
    if (instance) {
      instance->service_impl_->device_tracker_.OnGattReadDescriptor(conn_id, trans_id, handle,
                                                                    offset, is_long);
    }
  }

  static void OnGattWriteDescriptorStatic(tCONN_ID conn_id, uint32_t trans_id,
                                          const RawAddress& /*remote_bda*/, uint16_t handle,
                                          uint16_t offset, bool need_rsp, bool is_prep,
                                          uint8_t* value, uint16_t len) {
    if (instance) {
      instance->service_impl_->OnGattWriteDescriptor(conn_id, trans_id, handle, offset, need_rsp,
                                                     is_prep, value, len);
    }
  }

  // Prepares the attribute database structure according to the service descriptor
  static std::vector<btgatt_db_element_t> BuildGattDatabase(
          const Pacs::ServiceDescriptor& svc_descriptor) {
    std::vector<btgatt_db_element_t> service_db = {
            {
                    // PACS Service declaration
                    .uuid = uuid::kPublishedAudioCapabilityServiceUuid,
                    .type = BTGATT_DB_PRIMARY_SERVICE,
            },
    };

    if (!svc_descriptor.pac_sets.sink.empty()) {
      // Sink PAC characteristic for each set of PACs
      for (auto const& _ : svc_descriptor.pac_sets.sink) {
        service_db.push_back({
                // PACS Sink PAC Characteristic (Readable, Notifiable)
                .uuid = uuid::kSinkPublishedAudioCapabilityCharacteristicUuid,
                .type = BTGATT_DB_CHARACTERISTIC,
                .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                .permissions = GATT_PERM_READ_ENCRYPTED,
        });
        service_db.push_back({
                // CCCD for PACS Sink PAC
                .uuid = Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG),
                .type = BTGATT_DB_DESCRIPTOR,
                .permissions = GATT_PERM_READ_ENCRYPTED | GATT_WRITE_ENCRYPTED_PERM,
        });
      }
      // Sink audio location
      btgatt_db_element_t sink_location_char = {
              // PACS Sink Audio Location Characteristic
              .uuid = uuid::kSinkAudioLocationCharacteristicUuid,
              .type = BTGATT_DB_CHARACTERISTIC,
              .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
              .permissions = GATT_PERM_READ_ENCRYPTED,
      };
      if (svc_descriptor.audio_locations_writable.sink) {
        sink_location_char.properties |= GATT_CHAR_PROP_BIT_WRITE;
        sink_location_char.permissions |= GATT_WRITE_ENCRYPTED_PERM;
      }
      service_db.push_back(sink_location_char);
      service_db.push_back({
              // CCCD for PACS Sink Audio Location
              .uuid = Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG),
              .type = BTGATT_DB_DESCRIPTOR,
              .permissions = GATT_PERM_READ_ENCRYPTED | GATT_WRITE_ENCRYPTED_PERM,
      });
    }

    if (!svc_descriptor.pac_sets.source.empty()) {
      // Source PAC characteristic for each set of PACs
      for (auto const& _ : svc_descriptor.pac_sets.source) {
        service_db.push_back({
                // PACS Source PAC Characteristic (Readable, Notifiable)
                .uuid = uuid::kSourcePublishedAudioCapabilityCharacteristicUuid,
                .type = BTGATT_DB_CHARACTERISTIC,
                .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                .permissions = GATT_PERM_READ_ENCRYPTED,
        });
        service_db.push_back({
                // CCCD for PACS Source PAC
                .uuid = Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG),
                .type = BTGATT_DB_DESCRIPTOR,
                .permissions = GATT_PERM_READ_ENCRYPTED | GATT_WRITE_ENCRYPTED_PERM,
        });
      }
      // Source audio location
      btgatt_db_element_t source_location_char = {
              // PACS Source Audio Location Characteristic
              .uuid = uuid::kSourceAudioLocationCharacteristicUuid,
              .type = BTGATT_DB_CHARACTERISTIC,
              .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
              .permissions = GATT_PERM_READ_ENCRYPTED,
      };
      if (svc_descriptor.audio_locations_writable.source) {
        source_location_char.properties |= GATT_CHAR_PROP_BIT_WRITE;
        source_location_char.permissions |= GATT_WRITE_ENCRYPTED_PERM;
      }
      service_db.push_back(source_location_char);
      service_db.push_back({
              // CCCD for PACS Source Audio Location
              .uuid = Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG),
              .type = BTGATT_DB_DESCRIPTOR,
              .permissions = GATT_PERM_READ_ENCRYPTED | GATT_WRITE_ENCRYPTED_PERM,
      });
    }

    std::vector<btgatt_db_element_t> contexts_db_section = {
            {
                    // PACS Available Audio Contexts Characteristic (Readable, Notifiable)
                    .uuid = uuid::kAvailableAudioContextsCharacteristicUuid,
                    .type = BTGATT_DB_CHARACTERISTIC,
                    .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                    .permissions = GATT_PERM_READ_ENCRYPTED,
            },
            {
                    // CCCD for PACS Available Audio Contexts
                    .uuid = Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG),
                    .type = BTGATT_DB_DESCRIPTOR,
                    .permissions = GATT_PERM_READ_ENCRYPTED | GATT_WRITE_ENCRYPTED_PERM,
            },
            {
                    // PACS Supported Audio Contexts Characteristic (Readable, Notifiable)
                    .uuid = uuid::kSupportedAudioContextsCharacteristicUuid,
                    .type = BTGATT_DB_CHARACTERISTIC,
                    .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                    .permissions = GATT_PERM_READ_ENCRYPTED,
            },
            {
                    // CCCD for PACS Supported Audio Contexts
                    .uuid = Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG),
                    .type = BTGATT_DB_DESCRIPTOR,
                    .permissions = GATT_PERM_READ_ENCRYPTED | GATT_WRITE_ENCRYPTED_PERM,
            },
    };
    service_db.insert(service_db.end(), contexts_db_section.begin(), contexts_db_section.end());
    return service_db;
  }

  static inline GattStatus FillGattReadReqRspValue(tGATT_VALUE& dest, uint16_t att_handle,
                                                   uint16_t offset,
                                                   const std::vector<uint8_t>& source_value) {
    dest.handle = att_handle;
    dest.offset = offset;

    if (offset > source_value.size()) {
      return GATT_INVALID_OFFSET;
    }

    dest.len = std::min(source_value.size() - offset, sizeof(dest.value));
    std::copy(source_value.begin() + offset, source_value.begin() + offset + dest.len,
              std::begin(dest.value));
    return GATT_SUCCESS;
  }

  static GattStatus FillPacCharacteristicReadReqRsp(tGATT_VALUE& dest, uint16_t att_handle,
                                                    uint16_t offset, const PacSet& pac_set) {
    std::vector<pacs::PacRecord> pac_gatt_value;
    // Repack the correct PACS set records to PDL data struct for the requested PAC characteristic
    for (auto const& record : pac_set.records) {
      pac_gatt_value.push_back(pacs::PacRecord(
              pacs::CodecId(record.codec_id.coding_format, record.codec_id.vendor_company_id,
                            record.codec_id.vendor_codec_id),
              record.codec_spec_caps, record.metadata));
    }

    return FillGattReadReqRspValue(
            dest, att_handle, offset,
            pacs::PacCharValueBuilder::Create(pac_gatt_value)->SerializeToBytes());
  }

  static void OnReadPacCharacteristic(tCONN_ID conn_id, uint32_t trans_id, uint16_t handle,
                                      uint16_t offset,
                                      const std::map<uint16_t, PacSet>& pacs_by_handle) {
    log::info("handle: 0x{:04x}", handle);

    log::assert_that(pacs_by_handle.count(handle) != 0,
                     "No matching PAC characteristic found for handle: {}", handle);

    // Respond with a global value
    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    auto status = FillPacCharacteristicReadReqRsp(p_msg->attr_value, handle, offset,
                                                  pacs_by_handle.at(handle));
    BTA_GATTS_SendRsp(conn_id, trans_id, status, std::move(p_msg));
  }

  static void OnReadAudioLocationCharacteristic(tCONN_ID conn_id, uint32_t trans_id,
                                                uint16_t handle, uint16_t offset,
                                                const AudioLocations& locations) {
    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    auto status = FillGattReadReqRspValue(
            p_msg->attr_value, handle, offset,
            pacs::AudioLocationsCharValueBuilder::Create(locations.to_ullong())
                    ->SerializeToBytes());

    log::info("handle: 0x{:04x}, status: {}", handle, status);
    BTA_GATTS_SendRsp(conn_id, trans_id, status, std::move(p_msg));
  }

  static void RespondWithAudioContexts(tCONN_ID conn_id, uint32_t trans_id, uint16_t handle,
                                       uint16_t offset,
                                       const BidirectionalPair<AudioContexts>& contexts) {
    log::info("handle: 0x{:04x}", handle);

    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    auto status = FillGattReadReqRspValue(p_msg->attr_value, handle, offset,
                                          pacs::AudioContextsCharValueBuilder::Create(
                                                  contexts.sink.value(), contexts.source.value())
                                                  ->SerializeToBytes());
    BTA_GATTS_SendRsp(conn_id, trans_id, status, std::move(p_msg));
  }

  void OnReadAvailableAudioContextsCharacteristic(
          tCONN_ID conn_id, uint32_t trans_id, uint16_t handle, uint16_t offset,
          std::function<const BidirectionalPair<AudioContexts>()> const&
                  audio_context_value_provider) {
    // Update the remote device cache with the last read or notified context
    auto pac_device = device_tracker_.FindConnectedDevice(conn_id);
    log::assert_that(pac_device.get() != nullptr, "Missing connected device data");

    pac_device->data.last_notified_audio_contexts = audio_context_value_provider();

    log::info("available sink_contexts: 0x{:04x}, source_contexts: 0x{:04x}",
              pac_device->data.last_notified_audio_contexts.sink.value(),
              pac_device->data.last_notified_audio_contexts.source.value());
    RespondWithAudioContexts(conn_id, trans_id, handle, offset,
                             pac_device->data.last_notified_audio_contexts);
  }

  void OnGattReadCharacteristic(tCONN_ID conn_id, uint32_t trans_id,
                                const RawAddress& /*remote_bda*/, uint16_t handle, uint16_t offset,
                                bool /*is_long*/) {
    log::info("handle: 0x{:04x}", handle);

    log::assert_that(char_metadata_by_value_handle_.count(handle) != 0,
                     "Invalid handle 0x{:04x} for read request.", handle);
    auto char_uuid = char_metadata_by_value_handle_.at(handle).uuid;

    auto pac_device = device_tracker_.FindConnectedDevice(conn_id);
    log::assert_that(pac_device.get() != nullptr, "Missing connected device data");

    // Dispatch to the proper read request handler
    if (char_uuid == uuid::kSinkPublishedAudioCapabilityCharacteristicUuid.As16Bit()) {
      OnReadPacCharacteristic(conn_id, trans_id, handle, offset,
                              global_char_values_.pac_sets_by_char_handle.sink);
    } else if (char_uuid == uuid::kSourcePublishedAudioCapabilityCharacteristicUuid.As16Bit()) {
      OnReadPacCharacteristic(conn_id, trans_id, handle, offset,
                              global_char_values_.pac_sets_by_char_handle.source);
    } else if (char_uuid == uuid::kSinkAudioLocationCharacteristicUuid.As16Bit()) {
      pac_device->data.last_notified_audio_locations.sink =
              global_char_values_.audio_locations.sink;
      OnReadAudioLocationCharacteristic(conn_id, trans_id, handle, offset,
                                        global_char_values_.audio_locations.sink);
    } else if (char_uuid == uuid::kSourceAudioLocationCharacteristicUuid.As16Bit()) {
      pac_device->data.last_notified_audio_locations.source =
              global_char_values_.audio_locations.source;
      OnReadAudioLocationCharacteristic(conn_id, trans_id, handle, offset,
                                        global_char_values_.audio_locations.source);
    } else if (char_uuid == uuid::kSupportedAudioContextsCharacteristicUuid.As16Bit()) {
      // Respond with a global value
      RespondWithAudioContexts(conn_id, trans_id, handle, offset,
                               global_char_values_.supported_audio_contexts);
    } else if (char_uuid == uuid::kAvailableAudioContextsCharacteristicUuid.As16Bit()) {
      // Get a device dedicated value from the upper layer
      OnReadAvailableAudioContextsCharacteristic(
              conn_id, trans_id, handle, offset, [conn_id, this]() {
                auto pac_device = device_tracker_.FindConnectedDevice(conn_id);
                return pac_device ? callbacks_->OnGetAvailableAudioContexts(pac_device->pseudo_addr)
                                  : BidirectionalPair<AudioContexts>();
              });
    } else {
      log::assert_that(false,
                       "Unhandled characteristic read request for handle 0x{:04x}, char_uuid: {}.",
                       handle, Uuid::From16Bit(char_uuid).ToString());
    }
  }

  void OnWriteAudioLocationCharacteristic(tCONN_ID conn_id, uint32_t trans_id, uint16_t handle,
                                          bool need_rsp, uint8_t* value, uint16_t len) {
    auto char_uuid = char_metadata_by_value_handle_.at(handle).uuid;

    auto value_vec = std::make_shared<std::vector<uint8_t>>(value, value + len);
    auto char_view = pacs::AudioLocationsCharValueView::Create(packet::PacketView<true>(value_vec));
    if (!char_view.IsValid()) {
      log::warn("Invalid value for Audio Location write.");
      if (need_rsp) {
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_WRITE_REQ_REJECTED, nullptr);
      }
      return;
    }

    auto pac_device = device_tracker_.FindConnectedDevice(conn_id);
    log::assert_that(pac_device.get() != nullptr, "Missing connected device data");

    if (pending_request_by_address_.count(pac_device->pseudo_addr)) {
      log::warn("Device {} has a pending request, rejecting new one.", pac_device->pseudo_addr);
      if (need_rsp) {
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_BUSY, nullptr);
      }
      return;
    }

    uint8_t direction = (char_uuid == uuid::kSinkAudioLocationCharacteristicUuid.As16Bit())
                                ? types::kLeAudioDirectionSink
                                : types::kLeAudioDirectionSource;

    AudioLocations new_locations(char_view.GetAudioLocations());

    pending_request_by_address_[pac_device->pseudo_addr] = CtpRequest{trans_id, conn_id, need_rsp};
    callbacks_->OnAudioLocationsWritten(pac_device->pseudo_addr, direction, new_locations);
  }

  void OnGattWriteCharacteristic(tCONN_ID conn_id, uint32_t trans_id,
                                 const RawAddress& /*remote_bda*/, uint16_t handle,
                                 uint16_t /* offset */, bool need_rsp, bool /* is_prep */,
                                 uint8_t* value, uint16_t len) {
    log::info("handle: 0x{:04x}", handle);

    if (char_metadata_by_value_handle_.count(handle) == 0) {
      log::warn("Invalid handle 0x{:04x} for write request.", handle);
      if (need_rsp) {
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, nullptr);
      }
      return;
    }

    auto char_uuid = char_metadata_by_value_handle_.at(handle).uuid;
    if (char_uuid == uuid::kSinkAudioLocationCharacteristicUuid.As16Bit() ||
        char_uuid == uuid::kSourceAudioLocationCharacteristicUuid.As16Bit()) {
      OnWriteAudioLocationCharacteristic(conn_id, trans_id, handle, need_rsp, value, len);
    } else {
      log::warn("Unhandled characteristic write request for handle 0x{:04x}, char_uuid: {}.",
                handle, Uuid::From16Bit(char_uuid).ToString());
      if (need_rsp) {
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_WRITE_NOT_PERMIT, nullptr);
      }
    }
  }

  void OnGattConnect(const RawAddress& remote_bda, tCONN_ID conn_id, tBT_TRANSPORT transport) {
    // TODO: Inject an initial state of the connected PAC device from the persistent storage
    //       Just for now, start with empty one also for all the bonded devices.
    auto pac_device = device_tracker_.OnGattConnectedEventHandler(conn_id, remote_bda, transport,
                                                                  PacsDevice());
    if (pac_device) {
      callbacks_->OnDeviceConnected(pac_device->pseudo_addr);
    }
  }

  void OnGattDisconnect(const RawAddress& /*remote_bda*/, tCONN_ID conn_id) {
    auto pac_device = device_tracker_.OnGattDisconnectedEventHandler(conn_id, RawAddress::kEmpty);
    if (pac_device) {
      callbacks_->OnDeviceDisconnected(pac_device->pseudo_addr);
      pending_request_by_address_.erase(pac_device->pseudo_addr);
    }
  }

  void OnGattWriteDescriptor(tCONN_ID conn_id, uint32_t trans_id, uint16_t handle, uint16_t offset,
                             bool need_rsp, bool is_prep, uint8_t* value, uint16_t len) {
    device_tracker_.OnGattWriteDescriptor(conn_id, trans_id, handle, offset, len, need_rsp, is_prep,
                                          value);
  }

  void UpdateAvailableAudioContexts(const RawAddress& pseudo_addr,
                                    const BidirectionalPair<AudioContexts>& available_contexts) {
    auto conn_id = device_tracker_.FindConnectionId(pseudo_addr);
    if (conn_id == GATT_INVALID_CONN_ID) {
      log::warn("Device {} not connected", pseudo_addr);
      return;
    }

    log::info("Address: {}, conn_id: {}", pseudo_addr, conn_id);

    if (available_audio_context_handle_ == kGattInvalidHandle) {
      log::error("No Available Audio Contexts characteristic registered");
      return;
    }

    log::assert_that(char_metadata_by_value_handle_.count(available_audio_context_handle_),
                     "No available audio context characteristic is available");

    auto const& char_meta = char_metadata_by_value_handle_.at(available_audio_context_handle_);
    auto const& pac_device = device_tracker_.FindConnectedDevice(conn_id);
    log::assert_that(pac_device.get() != nullptr, "Missing connected device data");

    auto ccc_value = pac_device->GetDescriptorValueAsU16(char_meta.cccd_handle);
    if (ccc_value == GATT_CLT_CONFIG_NONE) {
      log::verbose("Notification to {} skipped as device is not subscribed ", pseudo_addr);
      return;
    }

    // Proceed only if remote device is not yet aware of this value
    if (pac_device->data.last_notified_audio_contexts == available_contexts) {
      log::debug("No context availability change for device: {}", pseudo_addr);
      return;
    }

    // Update the last notified contexts for this device
    pac_device->data.last_notified_audio_contexts = available_contexts;
    auto contextsValue = pacs::AudioContextsCharValueBuilder::Create(
                                 available_contexts.sink.value(), available_contexts.source.value())
                                 ->SerializeToBytes();
    BTA_GATTS_HandleValueIndication(conn_id, available_audio_context_handle_, contextsValue,
                                    ccc_value == GATT_CLT_CONFIG_INDICATION);
  }

  void UpdateAudioChannelLocations(
          const types::BidirectionalPair<types::AudioLocations>& audio_locations) {
    log::debug("current locations sink: {}, source: {}",
               global_char_values_.audio_locations.sink.to_ullong(),
               global_char_values_.audio_locations.source.to_ullong());
    log::debug("new locations sink: {}, source: {}", audio_locations.sink.to_ullong(),
               audio_locations.source.to_ullong());

    if (global_char_values_.audio_locations == audio_locations) {
      log::debug("Audio locations unchanged.");
      return;
    }

    global_char_values_.audio_locations = audio_locations;

    for (auto const& [conn_id, pac_device] : device_tracker_.GetConnectedDevices()) {
      log::debug("pac_device: {}", pac_device->pseudo_addr);
      for (auto direction : {types::kLeAudioDirectionSink, types::kLeAudioDirectionSource}) {
        // Skip if device knows the value
        if (pac_device->data.last_notified_audio_locations.get(direction) ==
            global_char_values_.audio_locations.get(direction)) {
          log::verbose("direction: {} - same value", direction);
          continue;
        }

        // Skip unsupported direction
        auto const locations_att_handle = audio_channel_allocation_handle_.get(direction);
        if (!GATT_HANDLE_IS_VALID(locations_att_handle)) {
          log::verbose("direction: {} - invalid ATT handle", direction);
          continue;
        }

        // Skip if notifications disabled
        auto const& char_meta = char_metadata_by_value_handle_.at(locations_att_handle);
        auto ccc_value = pac_device->GetDescriptorValueAsU16(char_meta.cccd_handle);
        if (ccc_value == GATT_CLT_CONFIG_NONE) {
          log::debug("Notification to {} skipped as device is not subscribed ",
                     pac_device->pseudo_addr);
          continue;
        }
        log::debug("direction: {} - sending indication", direction);
        auto locations = global_char_values_.audio_locations.get(direction);
        pac_device->data.last_notified_audio_locations.get(direction) = locations;
        auto locations_value = pacs::AudioLocationsCharValueBuilder::Create(locations.to_ullong())
                                       ->SerializeToBytes();
        BTA_GATTS_HandleValueIndication(conn_id, audio_channel_allocation_handle_.get(direction),
                                        locations_value, ccc_value == GATT_CLT_CONFIG_INDICATION);
      }
    }
  }

  void NotifyPacSetChange(uint16_t char_handle, const PacSet& pac_set) {
    for (auto const& [conn_id, pac_device] : device_tracker_.GetConnectedDevices()) {
      log::debug("pac_device: {}", pac_device->pseudo_addr);

      // Skip if notifications disabled
      auto const& char_meta = char_metadata_by_value_handle_.at(char_handle);
      auto ccc_value = pac_device->GetDescriptorValueAsU16(char_meta.cccd_handle);
      if (ccc_value == GATT_CLT_CONFIG_NONE) {
        log::debug("Notification to {} skipped as device is not subscribed ",
                   pac_device->pseudo_addr);
        continue;
      }

      log::debug("Sending indication for PAC set id: {}", pac_set.id);
      std::vector<pacs::PacRecord> pac_gatt_value;
      for (auto const& record : pac_set.records) {
        pac_gatt_value.push_back(pacs::PacRecord(
                pacs::CodecId(record.codec_id.coding_format, record.codec_id.vendor_company_id,
                              record.codec_id.vendor_codec_id),
                record.codec_spec_caps, record.metadata));
      }
      auto pac_value = pacs::PacCharValueBuilder::Create(pac_gatt_value)->SerializeToBytes();
      BTA_GATTS_HandleValueIndication(conn_id, char_handle, pac_value,
                                      ccc_value == GATT_CLT_CONFIG_INDICATION);
    }
  }

  void UpdatePacSet(uint8_t pac_set_id, const std::vector<PacRecord>& records) {
    for (auto& pac_set : global_char_values_.pac_sets_by_char_handle.sink) {
      if (pac_set.second.id == pac_set_id) {
        pac_set.second.records = records;
        NotifyPacSetChange(pac_set.first, pac_set.second);
        return;
      }
    }
    for (auto& pac_set : global_char_values_.pac_sets_by_char_handle.source) {
      if (pac_set.second.id == pac_set_id) {
        pac_set.second.records = records;
        NotifyPacSetChange(pac_set.first, pac_set.second);
        return;
      }
    }

    log::error("Could not find PAC set with id: {}", pac_set_id);
  }

  uint16_t GetConnectionId(const RawAddress& pseudo_addr) const {
    return device_tracker_.FindConnectionId(pseudo_addr);
  }

  void ConfirmAudioLocationsWritten(const RawAddress& pseudo_addr, bool is_accepted) {
    auto conn_id = device_tracker_.FindConnectionId(pseudo_addr);
    if (conn_id == GATT_INVALID_CONN_ID) {
      log::warn("Device {} not connected", pseudo_addr);
      return;
    }

    auto const& pac_device = device_tracker_.FindConnectedDevice(conn_id);
    log::assert_that(pac_device.get() != nullptr, "Missing connected device data");

    auto request = pending_request_by_address_.find(pseudo_addr);
    if (request != pending_request_by_address_.end()) {
      log::warn("Device {} has a pending request, rejecting new one.", pac_device->pseudo_addr);
      const auto& req = request->second;
      if (req.need_rsp) {
        BTA_GATTS_SendRsp(req.conn_id, req.trans_id,
                          is_accepted ? GATT_SUCCESS : GATT_WRITE_REQ_REJECTED, nullptr);
      }
      pending_request_by_address_.erase(pseudo_addr);
    }
  }
};

// Interface implementation
Pacs::Pacs() : service_impl_(std::make_unique<service_impl>()) {}
Pacs::~Pacs() { service_impl_.reset(); }

void Pacs::RegisterGattService(const Pacs::ServiceDescriptor& service, Pacs::Callbacks* callbacks) {
  service_impl_->RegisterGattService(service, callbacks);
}

void Pacs::UpdateAvailableAudioContexts(const RawAddress& pseudo_addr,
                                        const BidirectionalPair<AudioContexts>& contexts) {
  service_impl_->UpdateAvailableAudioContexts(pseudo_addr, contexts);
}

void Pacs::UpdateAudioChannelLocations(const BidirectionalPair<AudioLocations>& audio_locations) {
  service_impl_->UpdateAudioChannelLocations(audio_locations);
}

void Pacs::UpdatePacSet(uint8_t pac_set_id, const std::vector<PacRecord>& records) {
  service_impl_->UpdatePacSet(pac_set_id, records);  // Find the PAC set to update
}

uint16_t Pacs::GetConnectionId(const RawAddress& pseudo_addr) const {
  return service_impl_->GetConnectionId(pseudo_addr);
}

void Pacs::ConfirmAudioLocationsWritten(const RawAddress& pseudo_addr, bool is_accepted) {
  service_impl_->ConfirmAudioLocationsWritten(pseudo_addr, is_accepted);
}

void Pacs::Dump(std::stringstream& stream) const {
  stream << "  Published Audio Capabilities Service (PACS):\n";
  if (!service_impl_) {
    stream << "    Not initialized\n";
    return;
  }

  stream << "    server_if: " << +service_impl_->server_if_ << "\n";
  stream << "    callbacks: " << (service_impl_->callbacks_ == nullptr ? "NOT SET" : "SET") << "\n";
  stream << "    service_handle: 0x" << std::hex << service_impl_->service_handle_ << "\n";
  stream << "    available_audio_context_handle: 0x"
         << service_impl_->available_audio_context_handle_ << "\n";
  stream << "    supported_audio_contexts:\n"
         << "      - sink= " << service_impl_->global_char_values_.supported_audio_contexts.sink
         << "\n"
         << "      - source= " << service_impl_->global_char_values_.supported_audio_contexts.source
         << "\n";
  stream << "    audio_locations: sink=" << service_impl_->global_char_values_.audio_locations.sink
         << ", source=" << service_impl_->global_char_values_.audio_locations.source << "\n";

  stream << "    PAC sets ("
         << service_impl_->global_char_values_.pac_sets_by_char_handle.sink.size() << " sink, "
         << service_impl_->global_char_values_.pac_sets_by_char_handle.source.size()
         << " source):\n";
  for (auto const& [handle, pac_set] :
       service_impl_->global_char_values_.pac_sets_by_char_handle.sink) {
    stream << "      - [Sink] handle: 0x" << std::hex << handle << ", id: " << +pac_set.id
           << ", records: " << std::dec << pac_set.records.size() << "\n";
  }
  for (auto const& [handle, pac_set] :
       service_impl_->global_char_values_.pac_sets_by_char_handle.source) {
    stream << "      - [Source] handle: 0x" << std::hex << handle << ", id: " << +pac_set.id
           << ", records: " << std::dec << pac_set.records.size() << "\n";
  }

  service_impl_->device_tracker_.Dump(
          stream, "    ",
          [](std::stringstream& stream, const Pacs::service_impl::PacsDevice& data) {
            stream << "      last_notified_audio_contexts:\n"
                   << "        -sink= " << data.last_notified_audio_contexts.sink << "\n"
                   << "        -source= " << data.last_notified_audio_contexts.source << "\n";
            stream << "      last_notified_audio_locations: sink="
                   << data.last_notified_audio_locations.sink
                   << ", source=" << data.last_notified_audio_locations.source << "\n";
          });
}

}  // namespace bluetooth::le_audio
