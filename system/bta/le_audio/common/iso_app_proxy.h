/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include "stack/include/btm_iso_api.h"

namespace bluetooth {
/**
 * @class IsoAppProxy
 * @brief A helper class for applications to interact with the IsoManager.
 *
 * The IsoAppProxy module serves as an intermediary, or proxy,
 * between an application and the Bluetooth ISO manager. It simplifies
 * the interaction with the IsoManager, especially for applications (e.g., LE
 * Audio profiles) to manage Connected Isochronous Streams (CIS). It acts as a
 * proxy and filter between the application and the main IsoManager.
 * Currently, this proxy is designed for the Peripheral role. If a Central role
 * proxy is found useful, this class could be extended.
 *
 */
class IsoAppProxy {
public:
  /**
   * @brief Instantiates ISOAppProxy and sets the application's callbacks which are registered with
   * the IsoManager.
   * @param cig_callbacks Pointer to the application's CIG/CIS callback handler.
   * @param big_callbacks Pointer to the application's BIG callback handler.
   * @param iso_traffic_active_callback Callback to notify if ISO traffic is active.
   */
  IsoAppProxy(hci::iso_manager::CigCallbacks* cig_callbacks,
              hci::iso_manager::BigCallbacks* big_callbacks = nullptr,
              std::function<void(bool)> iso_traffic_active_callback = nullptr);

  virtual ~IsoAppProxy();

  /**
   * @brief Disallow cloning due to owning a dedicated ISO client handle and having a second client
   * with same client handle would break the message dispatching.
   */
  IsoAppProxy(const IsoAppProxy&) = delete;

  IsoAppProxy& operator=(const IsoAppProxy&) = delete;

  /**
   * @brief Registers interest in CIS events for a specific CIG/CIS.
   * @param device The address of the peer device.
   * @param cig_id The CIG identifier.
   * @param cis_id The CIS identifier.
   */
  virtual void AddIncomingCisEventsListener(const RawAddress& device, uint8_t cig_id,
                                            uint8_t cis_id);
  /**
   * @brief Unregisters interest in CIS events for a specific CIG/CIS.
   * @param device The address of the peer device.
   * @param cig_id The CIG identifier.
   * @param cis_id The CIS identifier.
   */
  virtual void RemoveIncomingCisEventsListener(const RawAddress& device, uint8_t cig_id,
                                               uint8_t cis_id);

  /**
   * @brief Requests to set up the ISO data path for a given CIS connection.
   * @param cis_conn_handle The connection handle of the CIS.
   * @param path_params The parameters for the ISO data path.
   */
  virtual void SetupIsoDataPath(uint16_t cis_conn_handle,
                                struct hci::iso_manager::iso_data_path_params path_params);

  /**
   * @brief Requests to remove the ISO data path for a given CIS connection.
   * @param cis_conn_handle The connection handle of the CIS.
   * @param data_path_dir The data path direction to remove.
   */
  virtual void RemoveIsoDataPath(uint16_t cis_conn_handle, uint8_t data_path_dir);

  /**
   * @brief Sends ISO data over a given CIS connection.
   * @param cis_conn_handle The connection handle of the CIS.
   * @param data Pointer to the data to be sent.
   * @param data_len Length of the data.
   */
  virtual void SendIsoData(uint16_t cis_conn_handle, const uint8_t* data, uint16_t data_len);

  /**
   * @brief Accepts an incoming CIS connection request.
   * @param cis_conn_handle The connection handle of the CIS to accept.
   */
  virtual void AcceptIncomingCisConnection(uint16_t cis_conn_handle);

  /**
   * @brief Rejects an incoming CIS connection request.
   * @param cis_conn_handle The connection handle of the CIS to reject.
   * @param reason The reason for rejection.
   */
  virtual void RejectIncomingCisConnection(uint16_t cis_conn_handle, uint8_t reason);

  /**
   * @brief Checks if a CIS connection is currently established.
   * @param cis_conn_handle The connection handle of the CIS.
   */
  virtual bool HasCisConnected(uint16_t cis_conn_handle) const;

private:
  class impl;
  std::unique_ptr<impl> pimpl_;
};

/**
 * @brief A factory function for creating IsoAppProxy instances.
 *
 * This is used to inject the proxy into the AseManager,
 * allowing for easier testing and dependency management.
 */
using IsoAppProxyFactory = base::RepeatingCallback<std::unique_ptr<IsoAppProxy>(
        hci::iso_manager::CigCallbacks* cig_callbacks)>;

}  // namespace bluetooth
