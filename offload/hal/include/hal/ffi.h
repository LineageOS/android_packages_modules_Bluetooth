/**
 * Copyright 2024, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <cstddef>
#include <cstdint>
#include <vector>

namespace aidl::android::hardware::bluetooth::hal {

extern "C" {

/**
 * Callabcks from C to Rust
 * The given `handle` must be passed as the first parameter of all functions.
 * The functions can be called from `hal_interface.initialize()` call to
 * `hal_interface.close()` call.
 */

enum Status {
  SUCCESS,
  ALREADY_INITIALIZED,
  UNABLE_TO_OPEN_INTERFACE,
  HARDWARE_INITIALIZATION_ERROR,
  UNKNOWN,
};

struct CCallbacks {
  void *handle;
  void (*initialization_complete)(const void *handle, Status);
  void (*event_received)(const void *handle, const uint8_t *data, size_t len);
  void (*acl_received)(const void *handle, const uint8_t *data, size_t len);
  void (*sco_received)(const void *handle, const uint8_t *data, size_t len);
  void (*iso_received)(const void *handle, const uint8_t *data, size_t len);
};

/**
 * Interface from Rust to C
 * The `handle` value is passed as the first parameter of all functions.
 * Theses functions can be called from different threads, but NOT concurrently.
 * Locking over `handle` is not necessary.
 */

struct CInterface {
  void *handle;
  void (*initialize)(void *handle, const CCallbacks *);
  void (*close)(void *handle);
  void (*send_command)(void *handle, const uint8_t *data, size_t len);
  void (*send_acl)(void *handle, const uint8_t *data, size_t len);
  void (*send_sco)(void *handle, const uint8_t *data, size_t len);
  void (*send_iso)(void *handle, const uint8_t *data, size_t len);
  void (*client_died)(void *handle);
};

/**
 * Add binder service
 */

void __add_bluetooth_hci_service(CInterface intf);

}  // extern "C"

class IBluetoothHciCallbacks {
public:
  IBluetoothHciCallbacks(const CCallbacks *callbacks) : callbacks_(*callbacks) {}

  void initializationComplete(Status status) {
    callbacks_.initialization_complete(callbacks_.handle, status);
  }

  void hciEventReceived(std::vector<uint8_t> data) {
    callbacks_.event_received(callbacks_.handle, data.data(), data.size());
  }

  void aclDataReceived(std::vector<uint8_t> data) {
    callbacks_.acl_received(callbacks_.handle, data.data(), data.size());
  }

  void scoDataReceived(std::vector<uint8_t> data) {
    callbacks_.sco_received(callbacks_.handle, data.data(), data.size());
  }

  void isoDataReceived(std::vector<uint8_t> data) {
    callbacks_.iso_received(callbacks_.handle, data.data(), data.size());
  }

private:
  CCallbacks callbacks_;
};

class IBluetoothHci {
public:
  virtual ~IBluetoothHci() = default;
  virtual void initialize(const std::shared_ptr<IBluetoothHciCallbacks> &callbacks);
  virtual void close();
  virtual void sendHciCommand(const std::vector<uint8_t> &data);
  virtual void sendAclData(const std::vector<uint8_t> &data);
  virtual void sendScoData(const std::vector<uint8_t> &data);
  virtual void sendIsoData(const std::vector<uint8_t> &data);
  virtual void clientDied();
};

static inline void IBluetoothHci_addService(IBluetoothHci *hci) {
  __add_bluetooth_hci_service((CInterface){
          .handle = hci,
          .initialize =
                  [](void *instance, const CCallbacks *callbacks) {
                    static_cast<IBluetoothHci *>(instance)->initialize(
                            std::make_shared<IBluetoothHciCallbacks>(callbacks));
                  },
          .close = [](void *instance) { static_cast<IBluetoothHci *>(instance)->close(); },
          .send_command =
                  [](void *instance, const uint8_t *data, size_t len) {
                    static_cast<IBluetoothHci *>(instance)->sendHciCommand(
                            std::vector<uint8_t>(data, data + len));
                  },
          .send_acl =
                  [](void *instance, const uint8_t *data, size_t len) {
                    static_cast<IBluetoothHci *>(instance)->sendAclData(
                            std::vector<uint8_t>(data, data + len));
                  },
          .send_sco =
                  [](void *instance, const uint8_t *data, size_t len) {
                    static_cast<IBluetoothHci *>(instance)->sendScoData(
                            std::vector<uint8_t>(data, data + len));
                  },
          .send_iso =
                  [](void *instance, const uint8_t *data, size_t len) {
                    static_cast<IBluetoothHci *>(instance)->sendIsoData(
                            std::vector<uint8_t>(data, data + len));
                  },
          .client_died =
                  [](void *instance) { static_cast<IBluetoothHci *>(instance)->clientDied(); }});
}

}  // namespace aidl::android::hardware::bluetooth::hal
