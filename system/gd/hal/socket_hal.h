/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "module.h"

namespace bluetooth::hal {

enum SocketStatus {
  SUCCESS = 0,
  FAILURE,
};

struct EndpointInfo {
  // The ID of the Hub to which the end point belongs for hardware offload data path.
  uint64_t hub_id;

  //  The ID of the Hub endpoint for hardware offload data path.
  uint64_t endpoint_id;
};

struct LeCocCapabilities {
  // Maximum number of LE COC sockets supported. If not supported, the value must be zero.
  int number_of_supported_sockets;

  // Local Maximum Transmission Unit size in octets.
  uint16_t mtu;
};

struct RfcommCapabilities {
  // Maximum number of RFCOMM sockets supported. If not supported, the value must be zero.
  int number_of_supported_sockets;

  // Maximum frame size in octets negotiated during DLCI establishment.
  uint16_t max_frame_size;
};

struct SocketCapabilities {
  LeCocCapabilities le_coc_capabilities;
  RfcommCapabilities rfcomm_capabilities;
};

struct LeCocChannelInfo {
  // L2cap local channel ID.
  uint16_t local_cid;

  // L2cap remote channel ID.
  uint16_t remote_cid;

  // PSM for L2CAP LE CoC.
  uint16_t psm;

  // Local Maximum Transmission Unit for LE COC specifying the maximum SDU size in bytes that the
  // local L2CAP layer can receive.
  uint16_t local_mtu;

  // Remote Maximum Transmission Unit for LE COC specifying the maximum SDU size in bytes that the
  // remote L2CAP layer can receive.
  uint16_t remote_mtu;

  // Local Maximum PDU payload Size in bytes that the local L2CAP layer can receive.
  uint16_t local_mps;

  // Remote Maximum PDU payload Size in bytes that the remote L2CAP layer can receive.
  uint16_t remote_mps;

  // Protocol initial credits at Rx path.
  uint16_t initial_rx_credits;

  // Protocol initial credits at Tx path.
  uint16_t initial_tx_credits;
};

struct RfcommChannelInfo {
  // L2cap local channel ID for RFCOMM.
  int local_cid;

  // L2cap remote channel ID for RFCOMM.
  int remote_cid;

  // Local Maximum Transmission Unit Size in bytes that the local L2CAP layer can receive.
  int local_mtu;

  // Remote Maximum Transmission Unit Size in bytes that the remote L2CAP layer can receive.
  int remote_mtu;

  // Protocol initial credits at Rx path.
  int initial_rx_credits;

  // Protocol initial credits at Tx path.
  int initial_tx_credits;

  // Data Link Connection Identifier (DLCI).
  int dlci;

  // Maximum frame size negotiated during DLCI establishment.
  int max_frame_size;

  // Flag of whether the Android stack initiated the RFCOMM multiplexer control channel.
  bool mux_initiator;
};

struct SocketContext {
  // Identifier assigned to the socket by the host stack when the socket is connected.
  uint64_t socket_id;

  // Descriptive socket name provided by the host app when it created this socket.
  std::string name;

  // ACL connection handle for the socket.
  uint16_t acl_connection_handle;

  // Channel information of different protocol used for the socket.
  std::variant<LeCocChannelInfo, RfcommChannelInfo> channel_info;

  // Endpoint information.
  EndpointInfo endpoint_info;
};

/**
 * SocketHalCallback provides an interface for receiving asynchronous events from socket HAL.
 * Implementations of this class can be registered with the stack to receive these callbacks.
 *
 * Callback methods in this interface are invoked from the binder thread. This means that
 * implementations must be thread-safe and handle any necessary synchronization to avoid race
 * conditions or other concurrency issues. The callee is solely responsible for ensuring thread
 * safety within the callback methods.
 */
class SocketHalCallback {
public:
  virtual ~SocketHalCallback() = default;

  /**
   * Invoked when IBluetoothSocket.opened() has been completed.
   *
   * @param socket_id Identifier assigned to the socket by the host stack
   * @param status Status indicating success or failure
   */
  virtual void SocketOpenedComplete(uint64_t socket_id, SocketStatus status) const = 0;

  /**
   * Invoked when offload app or stack requests host stack to close the socket.
   *
   * @param socket_id Identifier assigned to the socket by the host stack
   */
  virtual void SocketClose(uint64_t socket_id) const = 0;
};

/**
 * SocketHal provides an interface to low-power processors, enabling Bluetooth Offload Socket
 * functionality.
 *
 * Bluetooth Offload Socket allows the transfer of channel information from an established
 * BluetoothSocket to a low-power processor. This enables the offload stack on the low-power
 * processor to handle packet reception, processing, and transmission independently. This offloading
 * process prevents the need to wake the main application processor, improving power efficiency.
 */
class SocketHal : public ::bluetooth::Module {
public:
  static const ModuleFactory Factory;

  virtual ~SocketHal() = default;

  /**
   * Registers a socket hal callback function to receive asynchronous events from socket HAL.
   *
   * @param callback A pointer to the callback function. Must not be nullptr and must have static
   * lifetime.
   * @return True if the callback was successfully registered, false otherwise.
   */
  virtual bool RegisterCallback(hal::SocketHalCallback const* callback) = 0;

  /**
   * Retrieves the supported offloaded socket capabilities.
   *
   * @return Supported socket capabilities
   */
  virtual hal::SocketCapabilities GetSocketCapabilities() const = 0;

  /**
   * Notifies the socket HAL that the socket has been opened.
   *
   * @param context Socket context including socket ID, channel, hub, and endpoint info
   * @return Result of calling this method
   */
  virtual bool Opened(const hal::SocketContext& context) const = 0;

  /**
   * Notifies the socket HAL that the socket has been closed.
   *
   * @param socket_id Identifier assigned to the socket by the host stack
   */
  virtual void Closed(uint64_t socket_id) const = 0;
};

}  // namespace bluetooth::hal
