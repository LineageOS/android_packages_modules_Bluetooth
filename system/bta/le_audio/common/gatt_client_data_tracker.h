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

#include <base/strings/string_number_conversions.h>
#include <bluetooth/types/hci_role.h>

#include <cstdint>
#include <map>
#include <memory>
#include <vector>

#include "bta_gatt_api.h"
#include "stack/include/acl_api.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_client_interface.h"

namespace bluetooth {
/**
 * GattClientDataTracker is a helper class for GATT server implementations. It
 * simplifies managing data and state for multiple connected GATT clients.
 *
 * Key features:
 * - Manages a custom data structure of type `T` for each connected device.
 * - Tracks connected devices using their connection ID (`conn_id`).
 * - Handles device connection and disconnection events, creating and managing
 *   device-specific data entries.
 * - When a device disconnects, its data entry is marked as 'stale' but is not
 *   immediately destroyed. This allows other parts of the system holding a
 *   shared pointer to the entry to gracefully handle the disconnection while
 *   still accessing the last known state.
 * - Provides an internal cache for GATT characteristic descriptor values (e.g.,
 *   CCCDs) written by clients.
 * - Includes handlers for GATT read/write descriptor requests, which operate on
 *   the internal cache, simplifying the GATT server implementation.
 *
 * @param T The type of the data container to be associated with each
 * connected device. This container should hold all device-specific state.
 */
template <typename T>
class GattClientDataTracker {
public:
  /**
   * Represents a connected device and its associated data.
   * An instance of this struct is created for each connected GATT client.
   */
  struct DeviceEntry {
    /** If true, the device has disconnected. */
    bool is_stale = true;
    /** The address of the connected device. */
    RawAddress pseudo_addr;
    /** Device-specific data container of type T. */
    T data;

    DeviceEntry(bool is_stale, RawAddress pseudo_addr, T data)
        : is_stale(is_stale), pseudo_addr(pseudo_addr), data(data) {}

    uint16_t GetDescriptorValueAsU16(uint16_t descriptor_handle) const {
      uint16_t u16_value = 0x0000;
      if (descriptor_value_by_handle_.count(descriptor_handle)) {
        auto const* pp = descriptor_value_by_handle_.at(descriptor_handle).data();
        if (descriptor_value_by_handle_.at(descriptor_handle).size() == sizeof(u16_value)) {
          STREAM_TO_UINT16(u16_value, pp);
        } else {
          log::warn("Invalid descriptor length: {}",
                    descriptor_value_by_handle_.at(descriptor_handle).size());
        }
      }
      return u16_value;
    }

    friend class GattClientDataTracker;

  private:
    /**
     * Internal cache for descriptor values, mapping attribute handle to value.
     */
    std::map<uint16_t, std::vector<uint8_t>> descriptor_value_by_handle_;
  };

  /**
   * Handles a GATT descriptor read request from a client.
   * It looks up the value in the internal cache for the given connection and
   * sends a response.
   *
   * @param p_data Pointer to the GATT server event data for the read request.
   */
  void OnGattReadDescriptor(tCONN_ID conn_id, uint32_t trans_id, uint16_t handle, uint16_t offset,
                            bool /*is_long*/) {
    log::info("conn_id:{}, read_req.handle:0x{:04x}", conn_id, handle);

    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    p_msg->attr_value.handle = handle;
    p_msg->attr_value.offset = offset;
    p_msg->attr_value.len = 0;

    auto device = FindConnectedDevice(conn_id);
    if (!device || device->is_stale) {
      log::error("Device unavailable for conn_id:{}, att_handle:{}, has_device_data_block:{}",
                 conn_id, handle, device.get() != nullptr);
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INTERNAL_ERROR, std::move(p_msg));
      return;
    }

    // Read response
    if (device->descriptor_value_by_handle_.count(handle)) {
      auto const& descriptor_value = device->descriptor_value_by_handle_.at(handle);

      if (offset > descriptor_value.size()) {
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_OFFSET, std::move(p_msg));
        return;
      }

      p_msg->attr_value.len =
              std::min(descriptor_value.size() - offset, sizeof(p_msg->attr_value.value));
      std::copy(descriptor_value.begin() + offset,
                descriptor_value.begin() + offset + p_msg->attr_value.len, p_msg->attr_value.value);
    } else {
      p_msg->attr_value.len = 2;
      p_msg->attr_value.value[0] = 0;
      p_msg->attr_value.value[1] = 0;
    }

    log::verbose("Send response with value {}",
                 base::HexEncode(p_msg->attr_value.value, p_msg->attr_value.len));
    BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
  }

  /**
   * Handles a GATT descriptor write request from a client.
   * It updates the value in the internal cache for the given connection and
   * sends a response if required.
   *
   * @param p_data Pointer to the GATT server event data for the write request.
   */
  void OnGattWriteDescriptor(tCONN_ID conn_id, uint32_t trans_id, uint16_t handle, uint16_t offset,
                             uint16_t len, bool need_rsp, bool /*is_prep*/, const uint8_t* value) {
    log::info("conn_id:{}, write_req.handle:0x{:04x}, len:{}", conn_id, handle, len);

    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    p_msg->handle = handle;

    auto device = FindConnectedDevice(conn_id);
    if (!device || device->is_stale) {
      log::error("Device unavailable for conn_id:{}, has_device_data_block:{}", conn_id,
                 device.get() != nullptr);
      if (need_rsp) {
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INTERNAL_ERROR, std::move(p_msg));
      }
      return;
    }

    if (device->descriptor_value_by_handle_.count(handle) == 0) {
      device->descriptor_value_by_handle_[handle] = std::vector<uint8_t>();
    }

    // Resize and fill the buffer
    auto& dest = device->descriptor_value_by_handle_[handle];
    dest.resize(offset + len);
    std::copy(value, value + len, dest.data() + offset);

    log::info("offset: {}, value: {}", offset, base::HexEncode(value + offset, len));
    if (need_rsp) {
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
    }
  }

  /**
   * Handles a GATT connection event.
   * Creates a new `DeviceEntry` for the newly connected device and stores it.
   * This method also verifies that the connection is an LE connection from a
   * peripheral, as this tracker is intended for GATT servers on a central
   * device.
   *
   * @param p_data Pointer to the GATT server event data for the connection.
   * @param init_data The initial data of type T to be associated with the new
   *                  device.
   * @return A shared pointer to the newly created `DeviceEntry`, or nullptr if
   *         the connection is invalid or not of the expected type.
   */
  std::shared_ptr<DeviceEntry> OnGattConnectedEventHandler(tCONN_ID conn_id,
                                                           const RawAddress& pseudo_addr,
                                                           tBT_TRANSPORT transport, T&& init_data) {
    if (conn_id == GATT_INVALID_CONN_ID) {
      log::warn("Invalid conn_id: {}", conn_id);
      return nullptr;
    }

    log::debug("Address: {}, conn_id:{}", pseudo_addr, conn_id);

    if (transport == BT_TRANSPORT_BR_EDR) {
      log::warn("Skipping BR/EDR connection, only LE is supported for LE Audio.");
      return nullptr;
    }

    tHCI_ROLE role;
    auto role_status =
            get_btm_client_interface().link_policy.BTM_GetRole(pseudo_addr, BT_TRANSPORT_LE, &role);
    if (role_status != tBTM_STATUS::BTM_SUCCESS || role != HCI_ROLE_PERIPHERAL) {
      log::warn("Unicast server is not available for this connection. {}, status: {}, AclRole: {}",
                pseudo_addr, btm_status_text(role_status), hci_role_text(role));
      return nullptr;
    }

    connected_devices_[conn_id] =
            std::make_shared<DeviceEntry>(false, pseudo_addr, std::move(init_data));
    return connected_devices_.at(conn_id);
  }

  /**
   * Handles a GATT disconnection event.
   * Marks the corresponding `DeviceEntry` as stale and removes it from the
   * active connections map.
   *
   * @param p_data Pointer to the GATT server event data for the disconnection.
   * @return A shared pointer to the now-stale `DeviceEntry`. The caller can
   *         use this to inspect the final state of the device. Returns
   *         nullptr if the connection ID was not found.
   */
  std::shared_ptr<DeviceEntry> OnGattDisconnectedEventHandler(tCONN_ID conn_id,
                                                              const RawAddress& pseudo_addr) {
    auto it = connected_devices_.find(conn_id);
    if (it == connected_devices_.end()) {
      log::error("Unknown conn_id:{}", conn_id);
      return nullptr;
    }

    log::debug("Address: {}, conn_id:{}", pseudo_addr, conn_id);

    auto result = it->second;
    result->is_stale = true;
    connected_devices_.erase(it);
    return result;
  }

  /**
   * Finds the `DeviceEntry` for a given connection ID.
   *
   * @param conn_id The connection ID of the device to find.
   * @return A shared pointer to the `DeviceEntry` if found, otherwise nullptr.
   */
  std::shared_ptr<DeviceEntry> FindConnectedDevice(uint16_t conn_id) const {
    if ((conn_id != GATT_INVALID_CONN_ID) && (connected_devices_.count(conn_id) != 0)) {
      return connected_devices_.at(conn_id);
    }
    log::error("Device with conn_id:{} not found", conn_id);
    return nullptr;
  }

  /**
   * Gets a map of all currently connected devices.
   *
   * @return A const reference to the map of connection IDs to `DeviceEntry`
   *         shared pointers.
   */
  const std::map<uint16_t, std::shared_ptr<DeviceEntry>>& GetConnectedDevices() const {
    return connected_devices_;
  }

  /**
   * Finds the connection ID for a given device address.
   *
   * @param pseudo_addr The address of the device.
   * @return The connection ID if the device is connected, otherwise
   *         `GATT_INVALID_CONN_ID`.
   */
  uint16_t FindConnectionId(RawAddress pseudo_addr) const {
    for (auto const& [conn_id, addr_val] : connected_devices_) {
      if (addr_val->pseudo_addr == pseudo_addr) {
        return conn_id;
      }
    }
    log::error("Device: {} not found", pseudo_addr);
    return GATT_INVALID_CONN_ID;
  }

  /**
   * @brief Dumps the state of the tracker and all connected devices to a
   * string stream.
   *
   * This method is useful for debugging and generating bug reports.
   *
   * @param stream The string stream to write the dump to.
   * @param indent A string to prepend to each line for indentation.
   * @param dump_device A lambda function to dump device-specific data.
   */
  void Dump(std::stringstream& stream, const std::string& indent,
            std::function<void(std::stringstream&, const T&)> dump_device) const {
    if (connected_devices_.empty()) {
      stream << indent << "No connected devices.\n";
      return;
    }

    stream << indent << "Connected devices (" << connected_devices_.size() << "):\n";
    for (auto const& [conn_id, device_entry] : connected_devices_) {
      stream << indent << "  - conn_id: 0x" << std::hex << conn_id
             << ", addr: " << device_entry->pseudo_addr.ToString() << "\n";
      dump_device(stream, device_entry->data);
    }
  }

private:
  /** Map of connection ID to device data entry for active connections. */
  std::map<uint16_t, std::shared_ptr<DeviceEntry>> connected_devices_;
};
}  // namespace bluetooth
