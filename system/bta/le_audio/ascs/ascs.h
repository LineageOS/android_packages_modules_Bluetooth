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

#include <format>
#include <memory>
#include <variant>

#include "ascs_types.h"
#include "stack/include/gatt_api.h"

namespace bluetooth::le_audio {

/**
 * @brief Audio Stream Control Service (ASCS).
 *
 * This class provides an API to manage the ASCS GATT service, which is part of
 * the Basic Audio Profile (BAP). It handles the registration of the GATT
 * service, manages connections from clients, and processes client requests to
 * configure, enable, and control audio streams (ASEs).
 *
 * This service exposes:
 * - Sink/Source ASE characteristics
 * - ASE Control Point characteristic
 */
class Ascs {
public:
  /**
   * @brief Represents the state of a single Audio Stream Endpoint (ASE).
   */
  struct AseState {
    /** The current state of the ASE (e.g., IDLE, ENABLING, STREAMING). */
    ascs::AseState state = ascs::AseState::IDLE;
    /**
     * @brief State-specific parameters.
     *
     * The content of this variant depends on the `state` field. For example,
     * it holds codec configuration when the state is `CODEC_CONFIGURED`.
     */
    std::variant<std::monostate, ascs::AseStateCodecConfiguration, ascs::AseStateQosConfiguration,
                 ascs::AseStateTransientParams>
            state_params = std::monostate{};

    bool operator==(const AseState&) const = default;
  };

  /**
   * @brief Represents a request from a client via the ASE Control Point.
   */
  struct AseCtpRequest {
    /** The operation code of the request (e.g., CONFIG_CODEC, ENABLE). */
    ascs::AseCtpOpcode opcode;
    /**
     * @brief Request-specific parameters.
     *
     * The content of this variant depends on the `opcode` field.
     */
    std::variant<std::monostate, std::vector<ascs::AseCodecConfigurationReq>,
                 std::vector<ascs::AseQosConfigurationReq>, std::vector<ascs::AseEnableReq>,
                 std::vector<ascs::AseUpdateMetadataReq>, std::vector<uint8_t>>
            request_params = std::monostate{};
  };

  /**
   * @brief Represents a response to a client's ASE Control Point request.
   */
  struct AseCtpResponse {
    /** The operation code corresponding to the original request. */
    ascs::AseCtpOpcode opcode;
    /** A list of responses for each ASE involved in the operation. */
    std::vector<ascs::AseCtpResponseParams> entries;
  };

  /**
   * @brief Defines the GATT database structure for the ASC service.
   */
  struct ServiceDescriptor {
    /** Number of Sink ASE characteristics to create. */
    uint8_t num_sink_ases;
    /** Number of Source ASE characteristics to create. */
    uint8_t num_source_ases;
  };

  /**
   * @brief Callbacks for the ASC service to notify of events.
   *
   * An implementation of this interface must be provided to the
   * `RegisterGattService` method.
   */
  struct Callbacks {
    Callbacks() = default;
    virtual ~Callbacks() = default;
    /**
     * @brief Called when the ASC GATT service has been registered.
     * @param sink_ases A set of ASE IDs for Sink ASEs.
     * @param source_ases A set of ASE IDs for Source ASEs.
     */
    virtual void OnAscsRegistered(std::set<uint8_t> sink_ases, std::set<uint8_t> source_ases) = 0;
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
     * @brief Called when a client writes to the ASE Control Point.
     *
     * The implementation should process the request and eventually respond
     * using `AseCtpRequestResponse`.
     * @param pseudo_addr The address of the device making the request.
     * @param request The parsed control point request.
     */
    virtual void OnAseControlPointRequest(const RawAddress& pseudo_addr,
                                          const AseCtpRequest& request) = 0;
    /**
     * @brief Called when a client reads an ASE characteristic.
     * @param pseudo_addr The address of the device reading the characteristic.
     * @param ase_id The ID of the ASE being read.
     * @return The current state of the requested ASE.
     */
    virtual AseState OnGetAseState(const RawAddress& pseudo_addr, uint8_t ase_id) = 0;
  };

  /**
   * @brief Destroys the Ascs instance and cleans up resources.
   */
  virtual ~Ascs();
  /**
   * @brief Dumps the state of the service to the given stream.
   *
   * @param stream The string stream to write the dump to.
   */
  virtual void Dump(std::stringstream& stream) const;
  /**
   * @brief Registers the ASCS GATT service with the given descriptor and
   * callbacks.
   *
   * This method should be called once to initialize the service.
   *
   * @param service_descriptor The service parameters, like number of ASEs.
   * @param callbacks The callback handler for service events.
   */
  virtual void RegisterGattService(const ServiceDescriptor& service_descriptor,
                                   Callbacks* callbacks);
  /**
   * @brief Updates the state of an ASE and notifies the client if subscribed.
   * @param pseudo_addr The address of the client.
   * @param ase_id The ID of the ASE to update.
   * @param ase_state The new state of the ASE.
   */
  virtual void UpdateAseState(const RawAddress& pseudo_addr, uint8_t ase_id,
                              const AseState& ase_state);
  /**
   * @brief Sends a response to a pending ASE Control Point request.
   *
   * This should be called after receiving an `OnAseControlPointRequest`
   * callback.
   *
   * @param pseudo_addr The address of the client that made the request.
   * @param response The response to send.
   */
  virtual void AseCtpRequestResponse(const RawAddress& pseudo_addr, const AseCtpResponse& response);
  /**
   * @brief Gets the connection ID for a connected device.
   *
   * @param pseudo_addr The address of the device.
   * @return The connection ID if the device is connected, otherwise
   * `GATT_INVALID_CONN_ID`.
   */
  virtual uint16_t GetConnectionId(const RawAddress& pseudo_addr) const;

protected:
  /**
   * @brief Constructs an Ascs instance.
   */
  Ascs();

  /**
   * @brief Disallow cloning due to static GATT callbacks
   */
  Ascs(const Ascs&) = delete;
  Ascs& operator=(const Ascs&) = delete;

private:
  // Separates the implementation details from the interface
  struct service_impl;
  std::unique_ptr<service_impl> service_impl_;
};

/**
 * @brief Factory method to get a shared instance of the Ascs.
 *
 * This is required to manage a single static (but shared) instance, needed by
 * the static GATT callback API.
 *
 * @return A shared pointer to the Ascs instance.
 */
std::shared_ptr<Ascs> InstantiateAscs();
/**
 * @brief Releases the shared instance of the Ascs.
 *
 * @param shared_instance The shared pointer obtained from
 * `InstantiateAscs`.
 */
void ReleaseAscs(const std::shared_ptr<Ascs> shared_instance);

}  // namespace bluetooth::le_audio
