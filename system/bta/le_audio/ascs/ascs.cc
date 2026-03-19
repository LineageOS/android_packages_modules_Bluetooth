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

#include "ascs.h"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/memory/weak_ptr.h>
#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>

#include <map>

#include "bta/le_audio/common/gatt_client_data_tracker.h"
#include "bta/le_audio/common/le_audio_event_tracker.h"
#include "bta/le_audio/le_audio_types.h"

// Generated packet headers:
#include "ascs/ascs_packets.h"

using bluetooth::stack::tGATT_REQ_CBACK;

namespace bluetooth::le_audio {

static const uint8_t kInvalidAseId = 0x00;
static const uint16_t kGattInvalidHandle = 0x0000;

static const uint16_t kClientCharacteristicDescriptorUuidU16 = 0x2902;

namespace {
template <typename RequestView, typename AseEntryType, typename AscsRequestType>
bool ParseAseCtpRequestWithEntries(
        const ::bluetooth::ascs::AseControlPointRequestBaseView& request_packet,
        Ascs::AseCtpRequest& pending_request,
        std::function<AscsRequestType(const AseEntryType&)> converter) {
  auto request = RequestView::Create(request_packet);
  if (!request.IsValid()) {
    return false;
  }

  pending_request.request_params = std::vector<AscsRequestType>();
  auto& params = std::get<std::vector<AscsRequestType>>(pending_request.request_params);
  for (auto const& entry : request.GetAseEntries()) {
    params.push_back(converter(entry));
  }
  return true;
}

template <typename RequestView>
bool ParseAseCtpRequestWithAseIds(
        const ::bluetooth::ascs::AseControlPointRequestBaseView& request_packet,
        Ascs::AseCtpRequest& pending_request) {
  auto request = RequestView::Create(request_packet);
  if (!request.IsValid()) {
    return false;
  }

  pending_request.request_params = std::vector<uint8_t>();
  auto& params = std::get<std::vector<uint8_t>>(pending_request.request_params);
  for (auto const& entry : request.GetAseEntries()) {
    params.push_back(entry.ase_id_);
  }
  return true;
}
}  // namespace

static const char* EVT_LOG_TAG = "Asc Service";

// Static instance lifetime management
static std::shared_ptr<Ascs> instance = nullptr;
std::shared_ptr<Ascs> InstantiateAscs() {
  if (!instance) {
    class ConstructableAscs : public Ascs {};
    instance = std::make_shared<ConstructableAscs>();
  }
  return instance;
}

void ReleaseAscs(const std::shared_ptr<Ascs> shared_instance) {
  if (instance.get() != shared_instance.get()) {
    log::error("Not a valid LeAudioGattServer instance!");
  }
  // Just let the returned shared_instance to go out of scope to release it,
  // and release the static instance.
  instance.reset();
}

// Implementation
struct Ascs::service_impl {
  template <typename T>
  struct GattCharacteristicMetadata {
    Uuid uuid;
    uint16_t cccd_handle = kGattInvalidHandle;
    T svc_data;
  };
  struct AscsData {
    uint8_t ase_id = kInvalidAseId;
  };
  using AscCharacteristicMetadata = GattCharacteristicMetadata<AscsData>;

  struct AscDevice {
    std::map<uint8_t, Ascs::AseState> last_notified_ase_state_by_ase_id;
  };

  int server_if_ = 0;
  Ascs::Callbacks* callbacks_ = nullptr;
  GattClientDataTracker<AscDevice> device_tracker_;
  std::shared_ptr<LeAudioEventTracker> event_tracker_;

  ServiceDescriptor service_descriptor_;

  // Attribute handle mapping
  uint16_t service_handle_ = 0;
  uint16_t ase_ctp_characteristic_handle_ = kGattInvalidHandle;
  std::map<uint8_t, uint16_t> ase_char_handle_by_id_;
  std::map<uint16_t, AscCharacteristicMetadata> char_metadata_by_value_handle_;

  // Control point operation data
  std::map<RawAddress, AseCtpRequest> pending_request_by_address_;

  // Member variables should appear before the WeakPtrFactory, to ensure
  // that any WeakPtrs are invalidated before its members
  // variable's destructors are executed, rendering them invalid.
  base::WeakPtrFactory<Ascs::service_impl> weak_factory_{this};

  service_impl() { event_tracker_ = LeAudioEventTracker::GetLeAudioSinkInstance(); }
  ~service_impl() {
    if (server_if_ != 0) {
      BTA_GATTS_AppDeregister(server_if_);
    }
    log::info("ASCS GATT Server deregistered.");
  }

  void RegisterGattService(const ServiceDescriptor& service_descriptor,
                           Ascs::Callbacks* callbacks) {
    if (server_if_ != 0) {
      log::error("Already registered at server_if={}", server_if_);
      return;
    }

    if (callbacks == nullptr) {
      log::error("Null callbacks provided");
      return;
    }

    service_descriptor_ = service_descriptor;

    callbacks_ = callbacks;

    static bluetooth::stack::tGATT_REQ_CBACK ascs_req_cb = {
            .read_characteristic_cb = OnGattReadCharacteristicStatic,
            .read_descriptor_cb = OnGattReadDescriptorStatic,
            .write_characteristic_cb = OnGattWriteCharacteristicStatic,
            .write_descriptor_cb = OnGattWriteDescriptorStatic,
            .exec_write_cb = tGATT_REQ_CBACK::do_nothing,
            .mtu_changed_cb = tGATT_REQ_CBACK::do_nothing,
            .conf_cb = tGATT_REQ_CBACK::do_nothing,
    };
    static const stack::tGATT_CBACK ascs_ops = {
            .p_conn_cb = OnGattConnStatic,
            .p_req_cb = &ascs_req_cb,
    };

    server_if_ = BTA_GATTS_AppRegister(uuid::kAudioStreamControlServiceUuid, &ascs_ops, false);
    log::assert_that(server_if_ != stack::GATT_IF_INVALID, "Failed to register GATT Server");
    log::info("GATT Server Registered with server_if: {}", server_if_);

    auto gatt_db = BuildGattDatabase(service_descriptor_);

    log::info("Adding LE Audio Service {} service to GATT database.", gatt_db.begin()->uuid);
    auto status = BTA_GATTS_AddService(server_if_, &gatt_db);
    log::info("GATT Service Add status: {}, server_if: {}", gatt_status_text(status), server_if_);
    event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::POINT,
                            "GATT Service Add status: {}, server_if: {}", gatt_status_text(status),
                            server_if_);

    log::assert_that(status == GATT_SERVICE_STARTED, "Unable to add GATT service");
    log::assert_that(gatt_db.size() != 0, "Service is empty");
    log::assert_that(gatt_db.begin()->uuid == uuid::kAudioStreamControlServiceUuid,
                     "Service not mine!");

    AscCharacteristicMetadata* last_char_metadata = nullptr;
    uint8_t ase_id = 0x01;

    std::set<uint8_t> sink_ases;
    std::set<uint8_t> source_ases;

    for (const auto& element : gatt_db) {
      if (element.type == BTGATT_DB_CHARACTERISTIC) {
        log::info("Characteristic added: UUID {}, handle:0x{:04x}", element.uuid.ToString(),
                  element.attribute_handle);
        char_metadata_by_value_handle_[element.attribute_handle] = {.uuid = element.uuid};
        // Keep the pointer to the last discovered characteristic metadata to add CCCD handle info
        last_char_metadata = &char_metadata_by_value_handle_.at(element.attribute_handle);

        if (element.uuid == uuid::kAudioStreamEndpointControlPointCharacteristicUuid) {
          ase_ctp_characteristic_handle_ = element.attribute_handle;

        } else {
          // Assign ASE IDs
          if (element.uuid == uuid::kSinkAudioStreamEndpointUuid) {
            sink_ases.insert(ase_id);
            last_char_metadata->svc_data.ase_id = ase_id++;
            ase_char_handle_by_id_[last_char_metadata->svc_data.ase_id] = element.attribute_handle;

          } else if (element.uuid == uuid::kSourceAudioStreamEndpointUuid) {
            source_ases.insert(ase_id);
            last_char_metadata->svc_data.ase_id = ase_id++;
            ase_char_handle_by_id_[last_char_metadata->svc_data.ase_id] = element.attribute_handle;

          } else {
            log::assert_that(false, "Unknown characteristic uuid: {} found",
                             element.uuid.ToString());
            continue;
          }
        }

      } else if (element.type == BTGATT_DB_DESCRIPTOR) {
        log::assert_that(element.uuid == Uuid::From16Bit(kClientCharacteristicDescriptorUuidU16),
                         "Unknown descriptor uuid: {} found at handle: 0x{:04x}",
                         element.uuid.ToString(), element.attribute_handle);

        // Match the descriptor with the previous characteristic declaration
        log::assert_that(last_char_metadata, "No known characteristic for the added descriptor");
        last_char_metadata->cccd_handle = element.attribute_handle;

      } else if (element.type == BTGATT_DB_PRIMARY_SERVICE) {
        log::info("Service handle:0x{:04x}, UUID: {}", element.attribute_handle,
                  element.uuid.ToString());
        if (element.uuid == uuid::kAudioStreamControlServiceUuid) {
          service_handle_ = element.attribute_handle;
        }
      }
    }

    callbacks_->OnAscsRegistered(sink_ases, source_ases);
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

  // Prepares the attribute database structure according to the requirements
  static std::vector<btgatt_db_element_t> BuildGattDatabase(ServiceDescriptor service_descriptor) {
    std::vector<btgatt_db_element_t> ascs_service_db;

    // ASCS Service
    btgatt_db_element_t ascs_service;
    ascs_service.uuid = uuid::kAudioStreamControlServiceUuid;
    ascs_service.type = BTGATT_DB_PRIMARY_SERVICE;
    ascs_service_db.push_back(ascs_service);

    // ASE Sink State Characteristic (Notifiable, Readable)
    for (auto count = service_descriptor.num_sink_ases; count; --count) {
      btgatt_db_element_t ase_sink_state_char;
      ase_sink_state_char.uuid = uuid::kSinkAudioStreamEndpointUuid;
      ase_sink_state_char.type = BTGATT_DB_CHARACTERISTIC;
      ase_sink_state_char.properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY;
      ase_sink_state_char.permissions = GATT_PERM_READ_ENCRYPTED;
      ascs_service_db.push_back(ase_sink_state_char);

      // Client Characteristic Configuration Descriptor (CCCD) for Notifications
      btgatt_db_element_t ase_sink_state_cccd;
      ase_sink_state_cccd.type = BTGATT_DB_DESCRIPTOR;
      ase_sink_state_cccd.uuid = Uuid::From16Bit(kClientCharacteristicDescriptorUuidU16);
      ase_sink_state_cccd.permissions = GATT_PERM_READ | GATT_PERM_WRITE_ENCRYPTED;
      ascs_service_db.push_back(ase_sink_state_cccd);
    }

    // ASE Source State Characteristic (Notifiable, Readable)
    for (auto count = service_descriptor.num_source_ases; count; --count) {
      btgatt_db_element_t ase_source_state_char;
      ase_source_state_char.uuid = uuid::kSourceAudioStreamEndpointUuid;
      ase_source_state_char.type = BTGATT_DB_CHARACTERISTIC;
      ase_source_state_char.properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY;
      ase_source_state_char.permissions = GATT_PERM_READ_ENCRYPTED;
      ascs_service_db.push_back(ase_source_state_char);

      // Client Characteristic Configuration Descriptor (CCCD) for Notifications
      btgatt_db_element_t ase_source_state_cccd;
      ase_source_state_cccd.type = BTGATT_DB_DESCRIPTOR;
      ase_source_state_cccd.uuid = Uuid::From16Bit(kClientCharacteristicDescriptorUuidU16);
      ase_source_state_cccd.permissions = GATT_PERM_READ | GATT_PERM_WRITE_ENCRYPTED;
      ascs_service_db.push_back(ase_source_state_cccd);
    }

    // ASE Control Point Characteristic (Writable, Notifiable)
    btgatt_db_element_t ase_control_point_char;
    ase_control_point_char.uuid = uuid::kAudioStreamEndpointControlPointCharacteristicUuid;
    ase_control_point_char.type = BTGATT_DB_CHARACTERISTIC;
    ase_control_point_char.properties =
            GATT_CHAR_PROP_BIT_WRITE | GATT_CHAR_PROP_BIT_WRITE_NR | GATT_CHAR_PROP_BIT_NOTIFY;
    ase_control_point_char.permissions = GATT_PERM_WRITE_ENCRYPTED;
    ascs_service_db.push_back(ase_control_point_char);
    // Client Characteristic Configuration Descriptor (CCCD) for Notifications
    btgatt_db_element_t ase_control_point_cccd;
    ase_control_point_cccd.type = BTGATT_DB_DESCRIPTOR;
    ase_control_point_cccd.uuid = Uuid::From16Bit(kClientCharacteristicDescriptorUuidU16);
    ase_control_point_cccd.permissions = GATT_PERM_READ | GATT_PERM_WRITE_ENCRYPTED;
    ascs_service_db.push_back(ase_control_point_cccd);

    return ascs_service_db;
  }

  static std::vector<uint8_t> BuildAseStateCharValue(uint8_t ase_id,
                                                     const Ascs::AseState& ase_state) {
    std::vector<uint8_t> ase_state_data;
    switch (ase_state.state) {
      case ascs::AseState::IDLE:
        ase_state_data =
                ::bluetooth::ascs::AseIdleCharValueBuilder::Create(ase_id)->SerializeToBytes();
        break;

      case ascs::AseState::CODEC_CONFIGURED: {
        auto const& codec_config_params =
                std::get<ascs::AseStateCodecConfiguration>(ase_state.state_params);
        ase_state_data =
                ::bluetooth::ascs::AseCodecConfiguredCharValueBuilder::Create(
                        ase_id, ::bluetooth::ascs::Framing(codec_config_params.framing),
                        ::bluetooth::ascs::PhyBitmask(codec_config_params.preferred_phy),
                        codec_config_params.preferred_retrans_nb,
                        codec_config_params.max_transport_latency,
                        codec_config_params.pres_delay_min, codec_config_params.pres_delay_max,
                        codec_config_params.preferred_pres_delay_min,
                        codec_config_params.preferred_pres_delay_max,
                        ::bluetooth::ascs::CodecId(codec_config_params.codec_id.coding_format,
                                                   codec_config_params.codec_id.vendor_company_id,
                                                   codec_config_params.codec_id.vendor_codec_id),
                        codec_config_params.codec_spec_conf)
                        ->SerializeToBytes();
      } break;

      case ascs::AseState::QOS_CONFIGURED: {
        auto const& qos_config_params =
                std::get<ascs::AseStateQosConfiguration>(ase_state.state_params);
        ase_state_data =
                ::bluetooth::ascs::AseQosConfiguredCharValueBuilder::Create(
                        ase_id, qos_config_params.cig_id, qos_config_params.cis_id,
                        qos_config_params.sdu_interval,
                        ::bluetooth::ascs::Framing(qos_config_params.framing),
                        ::bluetooth::ascs::PhyBitmask(qos_config_params.phy),
                        qos_config_params.max_sdu, qos_config_params.retrans_nb,
                        qos_config_params.max_transport_latency, qos_config_params.pres_delay)
                        ->SerializeToBytes();
      } break;

      case ascs::AseState::ENABLING: {
        auto const& enabling_params =
                std::get<ascs::AseStateTransientParams>(ase_state.state_params);
        ase_state_data = ::bluetooth::ascs::AseEnablingCharValueBuilder::Create(
                                 ase_id, enabling_params.cig_id, enabling_params.cis_id,
                                 enabling_params.metadata)
                                 ->SerializeToBytes();
      } break;

      case ascs::AseState::STREAMING: {
        auto const& streaming_params =
                std::get<ascs::AseStateTransientParams>(ase_state.state_params);
        ase_state_data = ::bluetooth::ascs::AseStreamingCharValueBuilder::Create(
                                 ase_id, streaming_params.cig_id, streaming_params.cis_id,
                                 streaming_params.metadata)
                                 ->SerializeToBytes();
      } break;

      case ascs::AseState::DISABLING: {
        auto const& disabling_params =
                std::get<ascs::AseStateTransientParams>(ase_state.state_params);
        ase_state_data = ::bluetooth::ascs::AseDisablingCharValueBuilder::Create(
                                 ase_id, disabling_params.cig_id, disabling_params.cis_id,
                                 disabling_params.metadata)
                                 ->SerializeToBytes();
      } break;

      case ascs::AseState::RELEASING: {
        ase_state_data =
                ::bluetooth::ascs::AseReleasingCharValueBuilder::Create(ase_id)->SerializeToBytes();
      } break;
    }

    return ase_state_data;
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

  void OnReadAseCharacteristic(tCONN_ID conn_id, uint32_t trans_id, uint16_t handle,
                               uint16_t offset) {
    log::info("handle: 0x{:04x}", handle);

    log::assert_that(callbacks_ != nullptr, "Callbacks not set");
    log::assert_that(char_metadata_by_value_handle_.count(handle) != 0,
                     "Invalid handle 0x{:04x} for read request.", handle);

    auto asc_device = device_tracker_.FindConnectedDevice(conn_id);
    log::assert_that(asc_device != nullptr, "Invalid ASC device at conn_id 0x{:04x}", conn_id);

    // Get the ASE state from the upper layer
    auto ase_id = char_metadata_by_value_handle_.at(handle).svc_data.ase_id;
    auto ase_state = callbacks_->OnGetAseState(asc_device->pseudo_addr, ase_id);
    auto ase_state_data = BuildAseStateCharValue(ase_id, ase_state);
    log::assert_that(!ase_state_data.empty(), "No ASE State data available for handle 0x{:04x}",
                     handle);

    asc_device->data.last_notified_ase_state_by_ase_id[ase_id] = ase_state;

    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    auto status = FillGattReadReqRspValue(p_msg->attr_value, handle, offset, ase_state_data);
    BTA_GATTS_SendRsp(conn_id, trans_id, status, std::move(p_msg));
  }

  void OnGattReadCharacteristic(tCONN_ID conn_id, uint32_t trans_id,
                                const RawAddress& /*remote_bda*/, uint16_t handle, uint16_t offset,
                                bool /*is_long*/) {
    log::info("Read request for handle: 0x{:04x}", handle);

    log::assert_that(char_metadata_by_value_handle_.count(handle) != 0,
                     "Invalid handle 0x{:04x} for read request.", handle);
    auto char_uuid = char_metadata_by_value_handle_.at(handle).uuid;
    log::info("Read characteristic UUID: {}", char_uuid.ToString());

    if (char_uuid == uuid::kSinkAudioStreamEndpointUuid ||
        char_uuid == uuid::kSourceAudioStreamEndpointUuid) {
      OnReadAseCharacteristic(conn_id, trans_id, handle, offset);
    } else {
      log::assert_that(false, "Unhandled characteristic UUID for read request: {}",
                       char_uuid.ToString());
    }
  }

  void OnGattWriteCharacteristic(tCONN_ID conn_id, uint32_t trans_id,
                                 const RawAddress& /*remote_bda*/, uint16_t handle,
                                 uint16_t /*offset*/, bool need_rsp, bool /*is_prep*/,
                                 uint8_t* value, uint16_t len) {
    log::info("Write request for handle: 0x{:04x}, conn_id: {}", handle, conn_id);

    if (len == 0) {
      log::error("Invalid ASE control point request");

      if (need_rsp) {
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_ATTR_LEN, nullptr);
      }
      return;
    }

    log::assert_that(ase_ctp_characteristic_handle_ != kGattInvalidHandle,
                     "ASE control point characteristic is not initialized");
    log::assert_that(callbacks_ != nullptr, "Callbacks not set for LeAudioGattServer!");

    auto asc_device = device_tracker_.FindConnectedDevice(conn_id);
    log::assert_that(asc_device != nullptr, "Could not find the requesting device!");

    auto packet_data = std::make_shared<std::vector<uint8_t>>(value, value + len);
    auto request_packet = ::bluetooth::ascs::AseControlPointRequestBaseView::Create(
            packet::PacketView<true>(packet_data));
    if (!request_packet.IsValid()) {
      log::error("Invalid request!");
      if (need_rsp) {
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_VALUE_NOT_ALLOWED, nullptr);
      }
      return;
    }

    bool success = false;
    AseCtpRequest pending_request = {.opcode = ascs::AseCtpOpcode(request_packet.GetOpcode()),
                                     .request_params = std::monostate{}};
    switch (request_packet.GetOpcode()) {
      case ::bluetooth::ascs::AseOpcode::CONFIG_CODEC: {
        success = ParseAseCtpRequestWithEntries<
                ::bluetooth::ascs::AseControlPointConfigCodecRequestView,
                ::bluetooth::ascs::AseControlPointConfigCodecRequestAseEntry,
                ascs::AseCodecConfigurationReq>(
                request_packet, pending_request,
                [](const ::bluetooth::ascs::AseControlPointConfigCodecRequestAseEntry& entry) {
                  return ascs::AseCodecConfigurationReq{
                          .ase_id = entry.ase_id_,
                          .codec_configuration =
                                  {.target_latency = (uint8_t)entry.target_latency_,
                                   .target_phy = (uint8_t)entry.target_phy_,
                                   .codec_id =
                                           types::LeAudioCodecId{
                                                   .coding_format = entry.codec_id_.coding_format_,
                                                   .vendor_company_id =
                                                           entry.codec_id_.vendor_company_id_,
                                                   .vendor_codec_id =
                                                           entry.codec_id_.vendor_codec_id_},
                                   .codec_spec_conf =
                                           std::move(entry.codec_specific_configuration_)},
                  };
                });
      } break;

      case ::bluetooth::ascs::AseOpcode::CONFIG_QOS: {
        success = ParseAseCtpRequestWithEntries<
                ::bluetooth::ascs::AseControlPointConfigQosRequestView,
                ::bluetooth::ascs::AseControlPointConfigQosRequestAseEntry,
                ascs::AseQosConfigurationReq>(
                request_packet, pending_request,
                [](const ::bluetooth::ascs::AseControlPointConfigQosRequestAseEntry& entry) {
                  return ascs::AseQosConfigurationReq{
                          .ase_id = entry.ase_id_,
                          .qos_configuration = {.cig_id = entry.cig_id_,
                                                .cis_id = entry.cis_id_,
                                                .sdu_interval = entry.sdu_interval_,
                                                .framing = static_cast<uint8_t>(entry.framing_),
                                                .phy = static_cast<uint8_t>(entry.phy_),
                                                .max_sdu = entry.max_sdu_,
                                                .retrans_nb = entry.rtn_,
                                                .max_transport_latency =
                                                        entry.max_transport_latency_,
                                                .pres_delay = entry.presentation_delay_},
                  };
                });
      } break;

      case ::bluetooth::ascs::AseOpcode::ENABLE: {
        success = ParseAseCtpRequestWithEntries<::bluetooth::ascs::AseControlPointEnableRequestView,
                                                ::bluetooth::ascs::AseControlPointAseMetadataEntry,
                                                ascs::AseEnableReq>(
                request_packet, pending_request,
                [](const ::bluetooth::ascs::AseControlPointAseMetadataEntry& entry) {
                  return ascs::AseEnableReq{.ase_id = entry.ase_id_,
                                            .metadata = std::move(entry.metadata_)};
                });
      } break;

      case ::bluetooth::ascs::AseOpcode::UPDATE_METADATA: {
        success = ParseAseCtpRequestWithEntries<
                ::bluetooth::ascs::AseControlPointUpdateMetadataRequestView,
                ::bluetooth::ascs::AseControlPointAseMetadataEntry, ascs::AseUpdateMetadataReq>(
                request_packet, pending_request,
                [](const ::bluetooth::ascs::AseControlPointAseMetadataEntry& entry) {
                  return ascs::AseUpdateMetadataReq{.ase_id = entry.ase_id_,
                                                    .metadata = std::move(entry.metadata_)};
                });
      } break;

      case ::bluetooth::ascs::AseOpcode::RECEIVER_START_READY: {
        success = ParseAseCtpRequestWithAseIds<
                ::bluetooth::ascs::AseControlPointReceiverStartReadyRequestView>(request_packet,
                                                                                 pending_request);
      } break;

      case ::bluetooth::ascs::AseOpcode::DISABLE: {
        success =
                ParseAseCtpRequestWithAseIds<::bluetooth::ascs::AseControlPointDisableRequestView>(
                        request_packet, pending_request);
      } break;

      case ::bluetooth::ascs::AseOpcode::RECEIVER_STOP_READY: {
        success = ParseAseCtpRequestWithAseIds<
                ::bluetooth::ascs::AseControlPointReceiverStopReadyRequestView>(request_packet,
                                                                                pending_request);
      } break;

      case ::bluetooth::ascs::AseOpcode::RELEASE: {
        success =
                ParseAseCtpRequestWithAseIds<::bluetooth::ascs::AseControlPointReleaseRequestView>(
                        request_packet, pending_request);
      } break;
    }

    if (!success) {
      log::error("Invalid request!");
      if (need_rsp) {
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_VALUE_NOT_ALLOWED, nullptr);
      }
      event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::POINT,
                              "Invalid ASE control point request, con_id: {}, request opcode: {}",
                              conn_id, pending_request.opcode);
      return;
    }

    event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::POINT,
                            "ASE control point request, con_id: {}, request opcode: {}", conn_id,
                            pending_request.opcode);

    if (pending_request_by_address_.count(asc_device->pseudo_addr)) {
      log::warn("Device {} has a pending request, rejecting new one.", asc_device->pseudo_addr);
      if (need_rsp) {
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, nullptr);
      }

      AseCtpResponse response = {.opcode = pending_request.opcode, .entries = {}};
      auto add_entry = [&](uint8_t ase_id) {
        response.entries.push_back({.ase_id = ase_id,
                                    .response_code = ascs::AseCtpResponseCode::UNSPECIFIED_ERROR,
                                    .reason = ascs::AseCtpResponseReason::NO_REASON});
      };

      std::visit(
              [&](auto&& arg) {
                using T = std::decay_t<decltype(arg)>;
                if constexpr (std::is_same_v<T, std::vector<uint8_t>>) {
                  for (uint8_t ase_id : arg) {
                    add_entry(ase_id);
                  }
                } else if constexpr (!std::is_same_v<T, std::monostate>) {
                  for (const auto& req : arg) {
                    add_entry(req.ase_id);
                  }
                }
              },
              pending_request.request_params);

      // Note: This is a new request, so there is no pending request to clear.
      // We are just sending a notification response for the incoming command.
      SendAseCtpNotification(asc_device->pseudo_addr, response);
      return;
    }

    /* TODO check here if subscribed for all required CCC then send Connected native event */
    if (need_rsp) {
      log::debug("Sending Ctp write response to transaction: {}!", trans_id);
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, nullptr);
    }

    // Store the pending request for the peer device and call the request callback
    pending_request_by_address_[asc_device->pseudo_addr] = pending_request;
    log::assert_that(callbacks_ != nullptr, "Callback is not set!");
    callbacks_->OnAseControlPointRequest(asc_device->pseudo_addr, pending_request);
  }

  void OnGattConnect(const RawAddress& remote_bda, tCONN_ID conn_id, tBT_TRANSPORT transport) {
    // TODO: Inject an initial state of the connected PAC device from the persistent storage
    //       (e.g. CCCD values). For now, start with an empty state for all devices and notify
    //       the device as connected, when it subscribes to the ASE Control Point notifications
    if (auto asc_device = device_tracker_.OnGattConnectedEventHandler(conn_id, remote_bda,
                                                                      transport, AscDevice())) {
      auto const& char_meta = char_metadata_by_value_handle_.at(ase_ctp_characteristic_handle_);

      // Notify as connected if the control point notifications are enabled
      if (asc_device->GetDescriptorValueAsU16(char_meta.cccd_handle) != GATT_CLT_CONFIG_NONE) {
        event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::START,
                                "GATT device {} connected with conn_id: {}",
                                asc_device->pseudo_addr,
                                device_tracker_.FindConnectionId(asc_device->pseudo_addr));
        callbacks_->OnDeviceConnected(asc_device->pseudo_addr);
      }
    }
  }

  void OnGattDisconnect(const RawAddress& /*remote_bda*/, tCONN_ID conn_id) {
    auto asc_device = device_tracker_.FindConnectedDevice(conn_id);
    if (device_tracker_.OnGattDisconnectedEventHandler(conn_id, RawAddress::kEmpty)) {
      auto const& char_meta = char_metadata_by_value_handle_.at(ase_ctp_characteristic_handle_);

      pending_request_by_address_.erase(asc_device->pseudo_addr);

      // Notify as disconnected if the control point notifications were enabled
      if (asc_device->GetDescriptorValueAsU16(char_meta.cccd_handle) != GATT_CLT_CONFIG_NONE) {
        event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::END,
                                "GATT device {} disconnected", asc_device->pseudo_addr);
        callbacks_->OnDeviceDisconnected(asc_device->pseudo_addr);
      }
    }
  }

  void OnGattWriteDescriptor(tCONN_ID conn_id, uint32_t trans_id, uint16_t handle, uint16_t offset,
                             bool need_rsp, bool is_prep, uint8_t* value, uint16_t len) {
    if (auto asc_device = device_tracker_.FindConnectedDevice(conn_id)) {
      auto const& char_meta = char_metadata_by_value_handle_.at(ase_ctp_characteristic_handle_);
      auto const old_descr_val = asc_device->GetDescriptorValueAsU16(char_meta.cccd_handle);

      device_tracker_.OnGattWriteDescriptor(conn_id, trans_id, handle, offset, len, need_rsp,
                                            is_prep, value);

      // Notify as connected if the control point notifications are enabled
      if (handle == char_meta.cccd_handle) {
        auto const new_descr_val = asc_device->GetDescriptorValueAsU16(char_meta.cccd_handle);
        if (new_descr_val != old_descr_val) {
          if (new_descr_val == GATT_CLT_CONFIG_NONE) {
            callbacks_->OnDeviceDisconnected(asc_device->pseudo_addr);
          } else if (old_descr_val == GATT_CLT_CONFIG_NONE) {
            callbacks_->OnDeviceConnected(asc_device->pseudo_addr);
          }
        }
      }
    }
  }

  void UpdateAseState(const RawAddress& pseudo_addr, uint8_t ase_id,
                      const Ascs::AseState& ase_state) {
    auto conn_id = device_tracker_.FindConnectionId(pseudo_addr);
    if (conn_id == GATT_INVALID_CONN_ID) {
      log::warn("Device {} not connected", pseudo_addr);
      return;
    }

    if (ase_char_handle_by_id_.count(ase_id) == 0) {
      log::error("Unknown ASE id: {} state update requested", ase_id);
      return;
    }

    auto const char_value_handle = ase_char_handle_by_id_.at(ase_id);
    auto const& char_meta = char_metadata_by_value_handle_.at(char_value_handle);
    auto const& asc_device = device_tracker_.FindConnectedDevice(conn_id);
    log::assert_that(asc_device.get() != nullptr, "Missing connected device data");

    auto ccc_value = asc_device->GetDescriptorValueAsU16(char_meta.cccd_handle);
    if ((ccc_value & GATT_CLT_CONFIG_NOTIFICATION) == 0) {
      log::verbose("Notification to {} skipped as device is not subscribed ", pseudo_addr);
      return;
    }

    auto last_known_state_it = asc_device->data.last_notified_ase_state_by_ase_id.find(ase_id);
    if (last_known_state_it != asc_device->data.last_notified_ase_state_by_ase_id.end()) {
      if (last_known_state_it->second == ase_state) {
        // Note: ASCS v1.0, Sec. 5: "the server shall: *Transition the ASE to the Codec Configured
        //       state and write a value of 0x01 (Codec Configured) to the ASE_State field"  - even
        //       if an equal value is already there. Also note this (same chapter):
        //       "...shall ... for that ASE Control operation and send notifications of any ASE
        //        characteristic values written during that ASE Control operation" - meaning it
        //       should notify even if the written value is the same.
        log::warn("Same value was already notified to device: {}", pseudo_addr);
      }
    }

    asc_device->data.last_notified_ase_state_by_ase_id[ase_id] = ase_state;
    auto notify_value = BuildAseStateCharValue(ase_id, ase_state);
    log::info("Sending ASE notification value: {}",
              base::HexEncode(notify_value.data(), notify_value.size()));

    event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::POINT,
                            "Sending ASE {} state notification", ase_state.state);

    BTA_GATTS_HandleValueIndication(conn_id, char_value_handle, notify_value, false);
  }

  uint16_t GetConnectionId(const RawAddress& pseudo_addr) const {
    return device_tracker_.FindConnectionId(pseudo_addr);
  }

  void SendAseCtpNotification(const RawAddress& pseudo_addr, const AseCtpResponse& response) {
    auto conn_id = device_tracker_.FindConnectionId(pseudo_addr);
    if (conn_id == GATT_INVALID_CONN_ID) {
      log::warn("Device {} not connected", pseudo_addr);
      return;
    }

    std::vector<::bluetooth::ascs::AseControlPointNotificationEntry> ase_entries;
    for (auto const& el : response.entries) {
      ase_entries.push_back(::bluetooth::ascs::AseControlPointNotificationEntry(
              el.ase_id, ::bluetooth::ascs::ResponseCode(el.response_code),
              std::holds_alternative<uint8_t>(el.reason)
                      ? std::get<uint8_t>(el.reason)
                      : static_cast<uint8_t>(std::get<ascs::AseCtpResponseReason>(el.reason))));
    }

    auto notify_value = ::bluetooth::ascs::AseControlPointNotificationBuilder::Create(
                                ::bluetooth::ascs::AseOpcode(response.opcode), ase_entries)
                                ->SerializeToBytes();

    // Check cccd
    auto const& char_meta = char_metadata_by_value_handle_.at(ase_ctp_characteristic_handle_);
    auto const& asc_device = device_tracker_.FindConnectedDevice(conn_id);
    log::assert_that(asc_device.get() != nullptr, "Missing connected device data");

    auto ccc_value = asc_device->GetDescriptorValueAsU16(char_meta.cccd_handle);
    if ((ccc_value & GATT_CLT_CONFIG_NOTIFICATION) == 0) {
      log::warn("Notification to {} skipped as device is not subscribed", pseudo_addr);
      return;
    }

    log::info("Sending ASE control point notification value: {}",
              base::HexEncode(notify_value.data(), notify_value.size()));
    BTA_GATTS_HandleValueIndication(conn_id, ase_ctp_characteristic_handle_, notify_value, false);
  }

  void Dump(std::stringstream& stream) const {
    stream << "  Audio Stream Control Service (ASCS):\n";
    stream << "    server_if: " << +server_if_ << "\n";
    stream << "    callbacks: " << (callbacks_ == nullptr ? "NOT SET" : "SET") << "\n";
    stream << "    service_handle: 0x" << std::hex << service_handle_ << "\n";
    stream << "    control_point_handle: 0x" << std::hex << ase_ctp_characteristic_handle_ << "\n";

    stream << "    ASEs (" << ase_char_handle_by_id_.size() << "):\n";
    for (auto const& [ase_id, handle] : ase_char_handle_by_id_) {
      auto const& char_meta = char_metadata_by_value_handle_.at(handle);
      std::string direction = "Unknown";
      if (char_meta.uuid == uuid::kSinkAudioStreamEndpointUuid) {
        direction = "Sink";
      } else if (char_meta.uuid == uuid::kSourceAudioStreamEndpointUuid) {
        direction = "Source";
      }
      stream << "      - ASE ID: " << +ase_id << ", handle: 0x" << std::hex << handle
             << ", direction: " << direction << "\n";
    }

    if (pending_request_by_address_.empty()) {
      stream << "    No pending requests.\n";
    } else {
      stream << "    Pending requests (" << pending_request_by_address_.size() << "):\n";
      for (auto const& [addr, req] : pending_request_by_address_) {
        stream << "      - addr: " << addr.ToString() << ", opcode: " << req.opcode << "\n";
      }
    }

    device_tracker_.Dump(stream, "    ", [](std::stringstream& stream, const AscDevice& device) {
      if (device.last_notified_ase_state_by_ase_id.empty()) {
        stream << "      last notified ASE states: None\n";
        return;
      }

      stream << "      last notified ASE states ("
             << device.last_notified_ase_state_by_ase_id.size() << "):\n";
      for (auto const& [ase_id, state] : device.last_notified_ase_state_by_ase_id) {
        stream << "        - ASE ID: " << +ase_id << ", state: " << state.state << "\n";
      }
    });
  }

  void AseCtpRequestResponse(const RawAddress& pseudo_addr, const AseCtpResponse& response) {
    auto pending_req_it = pending_request_by_address_.find(pseudo_addr);
    if (pending_req_it == pending_request_by_address_.end()) {
      log::error("No pending request from the device: {}", pseudo_addr);
      return;
    }

    if (pending_req_it->second.opcode != response.opcode) {
      log::error("Opcode mismatch for pending request. Expected {}, got {}",
                 pending_req_it->second.opcode, response.opcode);
      /* Note: We still send the notification as the upper layer decided to do so. */
    }
    pending_request_by_address_.erase(pseudo_addr);

    SendAseCtpNotification(pseudo_addr, response);
  }
};

// Interface implementation
Ascs::Ascs() : service_impl_(std::make_unique<service_impl>()) {}
Ascs::~Ascs() { service_impl_.reset(); }

void Ascs::Dump(std::stringstream& stream) const { service_impl_->Dump(stream); }

void Ascs::RegisterGattService(const ServiceDescriptor& service_descriptor, Callbacks* callbacks) {
  log::info("");
  service_impl_->RegisterGattService(service_descriptor, callbacks);
}

void Ascs::UpdateAseState(const RawAddress& pseudo_addr, uint8_t ase_id,
                          const AseState& ase_state) {
  log::info("pseudo_addr {}, ase_id {}, state {}", pseudo_addr, ase_id, ase_state.state);
  service_impl_->UpdateAseState(pseudo_addr, ase_id, ase_state);
}

uint16_t Ascs::GetConnectionId(const RawAddress& pseudo_addr) const {
  log::info("pseudo_addr {}", pseudo_addr);
  return service_impl_->GetConnectionId(pseudo_addr);
}

void Ascs::AseCtpRequestResponse(const RawAddress& pseudo_addr, const AseCtpResponse& response) {
  log::info("pseudo_addr {}, opcode {}", pseudo_addr, response.opcode);
  service_impl_->AseCtpRequestResponse(pseudo_addr, response);
}

}  // namespace bluetooth::le_audio
