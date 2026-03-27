/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

#include "btm_iso_api_types.h"

namespace bluetooth {
namespace hci {
namespace iso_manager {

class CigCallbacks {
public:
  virtual ~CigCallbacks() = default;
  virtual void OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) = 0;
  virtual void OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) = 0;
  virtual void OnIsoLinkQualityRead(uint16_t conn_handle, uint8_t cig_id,
                                    uint32_t tx_unacked_packets, uint32_t tx_flushed_packets,
                                    uint32_t tx_last_subevent_packets,
                                    uint32_t retransmitted_packets, uint32_t crc_error_packets,
                                    uint32_t rx_unreceived_packets, uint32_t duplicate_packets) = 0;

  virtual void OnCisEvent(uint8_t event, void* data) = 0;
  virtual void OnCigEvent(uint8_t event, void* data) = 0;
};

class BigCallbacks {
public:
  virtual ~BigCallbacks() = default;
  virtual void OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t big_handle) = 0;
  virtual void OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t big_handle) = 0;

  virtual void OnBisEvent(uint8_t event, void* data) = 0;
  virtual void OnBigSourceEvent(BigSourceEvent event, void* data) = 0;
  virtual void OnBigSinkEvent(BigSinkEvent event, void* data) = 0;
};

struct IsoManagerCallbacks {
  CigCallbacks* cig_callbacks = nullptr;
  BigCallbacks* big_callbacks = nullptr;
  std::function<void(bool)> iso_traffic_active_callback;
};

}  // namespace iso_manager

class IsoManager {
public:
  IsoManager();
  IsoManager(const IsoManager&) = delete;
  IsoManager& operator=(const IsoManager&) = delete;

  virtual ~IsoManager();

  static IsoManager* GetInstance();

  /**
   * Registers iso manager callbacks for a new client.
   * @param callbacks A struct of function pointers for IsoManagerCallbacks.
   * @return A unique client handle or kInvalidIsoClientHandle on failure.
   */
  virtual iso_manager::IsoClientHandle RegisterCallbacks(
          iso_manager::IsoManagerCallbacks callbacks) const;

  /**
   * Unregisters a client and cleans up its resources.
   * @param client_handle The handle obtained from RegisterCallbacks.
   */
  virtual void DeregisterCallbacks(iso_manager::IsoClientHandle client_handle) const;

  /**
   * Creates connected isochronous group (CIG) according to given params.
   *
   * @param client_handle client handle
   * @param cig_id connected isochronous group id
   * @param cig_params CIG parameters
   */
  virtual void CreateCig(iso_manager::IsoClientHandle client_handle, uint8_t cig_id,
                         struct iso_manager::cig_create_params cig_params);

  /**
   * Reconfigures connected isochronous group (CIG) according to given params.
   *
   * @param cig_id connected isochronous group id
   * @param cig_params CIG parameters
   */
  virtual void ReconfigureCig(uint8_t cig_id, struct iso_manager::cig_create_params cig_params);

  /**
   * Initiates removing of connected isochronous group (CIG).
   *
   * @param cig_id connected isochronous group id
   * @param force do not check if CIG exist
   */
  virtual void RemoveCig(uint8_t cig_id, bool force = false);

  /**
   * Initiates creation of connected isochronous stream (CIS).
   *
   * @param conn_params A set of cis and acl connection handles
   */
  virtual void EstablishCis(struct iso_manager::cis_establish_params conn_params);

  /**
   * Initiates disconnection of connected isochronous stream (CIS).
   * Note: If function is used for Canceling CIS, which means, CIS was not yet established,
   * btm_iso will skip OnCisEvent(kIsoEventCisEstablishCmpl) and
   * will just send OnCisEvent(kIsoEventCisDisconnected) when CIS is canceled.
   *
   * @param conn_handle CIS connection handle
   * @param reason HCI reason for disconnection
   */
  virtual void DisconnectCis(uint16_t conn_handle, uint8_t reason);

  /**
   * Initiates creation of isochronous data path for connected isochronous
   * stream.
   *
   * @param conn_handle handle of BIS or CIS connection
   * @param path_params iso data path parameters
   */
  virtual void SetupIsoDataPath(uint16_t conn_handle,
                                struct iso_manager::iso_data_path_params path_params);

  /**
   * Initiates removal of isochronous data path for connected isochronous
   * stream.
   *
   * @param conn_handle handle of BIS or CIS connection
   * @param data_path_dir iso data path direction
   */
  virtual void RemoveIsoDataPath(uint16_t conn_handle, uint8_t data_path_dir);

  /**
   * Reads the ISO link quality. OnIsoLinkQualityRead callback is invoked only
   * if read is successful.
   *
   * @param conn_handle handle of ISO connection
   */
  virtual void ReadIsoLinkQuality(uint16_t conn_handle);

  /**
   * Sends iso data to the controller
   *
   * @param conn_handle handle of BIS or CIS connection
   * @param data data buffer. The ownership of data is not being transferred.
   * @param data_len data buffer length
   */
  virtual void SendIsoData(uint16_t conn_handle, const uint8_t* data, uint16_t data_len);

  /**
   * Creates the Broadcast Isochronous Group
   *
   * @param client_handle client handle
   * @param big_handle host assigned BIG identifier
   * @param big_params BIG parameters
   */
  virtual void CreateBig(iso_manager::IsoClientHandle client_handle, uint8_t big_handle,
                         struct iso_manager::big_create_params big_params);

  /**
   * Terminates the Broadcast Isochronous Group
   *
   * @param big_handle host assigned BIG identifier
   * @param reason termination reason data
   */
  virtual void TerminateBig(uint8_t big_handle, uint8_t reason);

  /**
   * Creates sync with Broadcast Isochronous Group
   *
   * @param client_handle client handle
   * @param sync_params BIG sync parameters
   */
  virtual void BigCreateSync(iso_manager::IsoClientHandle client_handle,
                             struct iso_manager::big_create_sync_params sync_params);

  /**
   * Terminates sync with Broadcast Isochronous Group
   *
   * @param big_handle BIG identifier
   */
  virtual void BigTerminateSync(uint8_t big_handle);

  /* Below are defined handlers called by the legacy code in btu_hcif.cc */

  /**
   * Handles Iso Data packets from the controller
   *
   * @param p_msg raw data packet. The ownership of p_msg is not being
   * transferred.
   */
  virtual void HandleIsoData(void* p_msg);

  /**
   * Handles disconnect HCI event
   *
   * <p> This callback can be called with handles other than ISO connection
   * handles.
   *
   * @param conn_handle connection handle
   * @param reason HCI reason for disconnection
   */
  virtual void HandleDisconnect(uint16_t conn_handle, uint8_t reason);

  /**
   * Handles the number of completed packets
   *
   * @param handle - the handle for which there are completed packets
   * @param credits - the number of packets completed
   */
  virtual void HandleNumComplDataPkts(uint16_t handle, uint16_t credits);

  /**
   * Handle CIS and BIG related HCI events
   *
   * @param sub_code ble subcode for the HCI event
   * @param params raw packet buffer for the event. The ownership of params is
   * not being transferred
   * @param length event packet buffer length
   */
  virtual void HandleHciEvent(uint8_t sub_code, uint8_t* params, uint16_t length);

  /**
   * Return the current number of ISO channels
   */
  virtual int GetNumberOfActiveIso();

  /**
   * Set the BIG Channel Map classification using a Vendor-Specific Command.
   *
   * @param action The action to perform (ADD, DELETE, CLEAR).
   * @param big_handle The handle of the BIG to be affected.
   * @param handles A list of connection handles to be added or deleted.
   */
  virtual void SetBigChannelMapClassificationByConnHandles(uint8_t action, uint8_t big_handle,
                                                           const std::vector<uint16_t>& handles);

  /**
   * Expects incoming CIS events for a specific client, pseudo address, CIG ID, and CIS ID.
   * This function registers a listener for incoming CIS connections that match the provided
   * criteria. Any CIS request without a listener registered for it, will automatically be
   * rejected by the stack.
   * Note: The listener is persistent and will remain active until explicitly
   *       removed by calling `RemoveIncomingCisEventsListener()`. Registration
   *       may fail if another client has already registered for the same CIS
   *       from the same device.
   *
   * @param client_handle The handle of the client expecting the events.
   * @param pseudo_address The pseudo address of the peer device.
   * @param cig_id The Connected Isochronous Group (CIG) ID.
   * @param cis_id The Connected Isochronous Stream (CIS) ID.
   * @return True if the listener was successfully added, false otherwise.
   */
  virtual bool AddIncomingCisEventsListener(iso_manager::IsoClientHandle client_handle,
                                            const RawAddress& pseudo_address, uint8_t cig_id,
                                            uint8_t cis_id);

  /**
   * Cancels the expectation of incoming CIS events for a specific client, pseudo address, CIG ID,
   * and CIS ID. This function unregisters a previously registered listener for incoming CIS
   * connections.
   * Note: After unregistering, no further events for this CIS will be routed
   *       to the client. The client cannot unregister the event listener for a
   *       connected CIS. The CIS must be disconnected before unregistering.
   *
   * @param client_handle The handle of the client that registered the expectation.
   * @param pseudo_address The pseudo address of the peer device.
   * @param cig_id The Connected Isochronous Group (CIG) ID.
   * @param cis_id The Connected Isochronous Stream (CIS) ID.
   */
  virtual void RemoveIncomingCisEventsListener(iso_manager::IsoClientHandle client_handle,
                                               const RawAddress& pseudo_address, uint8_t cig_id,
                                               uint8_t cis_id);

  /**
   * Accepts an incoming CIS connection.
   * @param conn_handle The connection handle of the incoming CIS.
   */
  virtual void AcceptIncomingCisConnection(uint16_t conn_handle);

  /**
   * Rejects an incoming CIS connection.
   * @param conn_handle The connection handle of the incoming CIS.
   * @param reason The reason for rejecting the connection.
   */
  virtual void RejectIncomingCisConnection(uint16_t conn_handle, uint8_t reason);

  /**
   * Starts the IsoManager module
   */
  void Start();

  /**
   * Stops the IsoManager module
   */
  void Stop();

  /**
   * Dumps the IsoManager module state
   */
  void Dump(int fd);

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;
};

}  // namespace hci
}  // namespace bluetooth
