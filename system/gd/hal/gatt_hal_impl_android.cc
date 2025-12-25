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

#include "hal/gatt_hal_impl_android.h"

#include <aidl/android/hardware/bluetooth/gatt/BnBluetoothGattCallback.h>
#include <aidl/android/hardware/bluetooth/gatt/IBluetoothGatt.h>
#include <aidl/android/hardware/bluetooth/gatt/IBluetoothGattCallback.h>
#include <android/binder_manager.h>
#include <bluetooth/log.h>

#include <thread>

using ::aidl::android::hardware::bluetooth::gatt::BnBluetoothGattCallback;
using ::aidl::android::hardware::bluetooth::gatt::IBluetoothGatt;

namespace bluetooth::hal {

constexpr int32_t kGattPropertyNotify = 0x10;

class GattAidlCallback : public BnBluetoothGattCallback {
  class : public hal::GattHalCallback {
  public:
    void registerServiceComplete(uint16_t /* session_id */,
                                 GattStatus /* status */) const override {
      log::warn("Dropping registerServiceComplete event, since callback is not set");
    }

    void unregisterServiceComplete(uint16_t /* session_id */) const override {
      log::warn("Dropping unregisterServiceComplete event, since callback is not set");
    }

    void clearServicesComplete(uint16_t /* acl_connection_handle */) const override {
      log::warn("Dropping clearServicesComplete event, since callback is not set");
    }

    void errorReport(uint16_t /* acl_connection_handle */, uint16_t /* local_cid */,
                     GattError /* error */) const override {
      log::warn("Dropping errorReport event, since callback is not set");
    }
  } kNullCallbacks;

public:
  GattAidlCallback() = default;

  void SetCallback(hal::GattHalCallback const* callback) {
    log::assert_that(callback != nullptr, "callback != nullptr");
    gatt_hal_cb_ = callback;
  }

  ::ndk::ScopedAStatus registerServiceComplete(
          int32_t in_session_id, ::aidl::android::hardware::bluetooth::gatt::Status in_status,
          const std::string& in_reason) override {
    log::info("session_id: {}, status: {}, reason: {}", static_cast<uint16_t>(in_session_id),
              static_cast<int>(in_status), in_reason);
    gatt_hal_cb_->registerServiceComplete(static_cast<uint16_t>(in_session_id),
                                          static_cast<hal::GattStatus>(in_status));
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus unregisterServiceComplete(int32_t in_session_id,
                                                 const std::string& in_reason) override {
    log::info("session_id: {}, reason: {}", static_cast<uint16_t>(in_session_id), in_reason);
    gatt_hal_cb_->unregisterServiceComplete(static_cast<uint16_t>(in_session_id));
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus clearServicesComplete(int32_t in_acl_connection_handle,
                                             const std::string& in_reason) override {
    log::info("acl_connection_handle: 0x{:x}, reason: {}",
              static_cast<uint16_t>(in_acl_connection_handle), in_reason);
    gatt_hal_cb_->clearServicesComplete(static_cast<uint16_t>(in_acl_connection_handle));
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus errorReport(
          const ::aidl::android::hardware::bluetooth::gatt::ErrorReport& in_report) override {
    log::info("acl_connection_handle: 0x{:x}, local_cid: 0x{:x}, error: {}, reason: {}",
              in_report.aclConnectionHandle, in_report.localCid, static_cast<int>(in_report.error),
              in_report.reason);
    gatt_hal_cb_->errorReport(static_cast<uint16_t>(in_report.aclConnectionHandle),
                              static_cast<uint16_t>(in_report.localCid),
                              static_cast<hal::GattError>(in_report.error));
    return ::ndk::ScopedAStatus::ok();
  }

private:
  hal::GattHalCallback const* gatt_hal_cb_ = &kNullCallbacks;
};

GattHalImpl::GattHalImpl() {
  std::string instance = std::string() + IBluetoothGatt::descriptor + "/default";
  if (!AServiceManager_isDeclared(instance.c_str())) {
    log::error("The service {} is not declared", instance);
    return;
  }

  ::ndk::SpAIBinder binder(AServiceManager_waitForService(instance.c_str()));
  gatt_hal_instance_ = IBluetoothGatt::fromBinder(binder);

  if (gatt_hal_instance_ == nullptr) {
    log::error("Failed to bind to the service {}", instance);
    return;
  }

  gatt_aidl_cb_ = ndk::SharedRefBase::make<GattAidlCallback>();
  ::ndk::ScopedAStatus status = gatt_hal_instance_->init(gatt_aidl_cb_);
  if (!status.isOk()) {
    log::error("init failure: {}", status.getDescription());
    gatt_hal_instance_ = nullptr;
    return;
  }

  death_recipient_ =
          ::ndk::ScopedAIBinder_DeathRecipient(AIBinder_DeathRecipient_new([](void* /* cookie*/) {
            log::error("The Gatt HAL service died.");
            // At shutdown, sometimes the HAL service gets killed before Bluetooth.
            std::this_thread::sleep_for(std::chrono::seconds(1));
            log::fatal("Restarting Bluetooth after the Gatt HAL has died.");
          }));

  auto death_link =
          AIBinder_linkToDeath(gatt_hal_instance_->asBinder().get(), death_recipient_.get(), this);
  log::assert_that(death_link == STATUS_OK, "Unable to set the death recipient for the Gatt HAL");
}

GattHalImpl::~GattHalImpl() {
  if (IsBound()) {
    auto death_unlink = AIBinder_unlinkToDeath(gatt_hal_instance_->asBinder().get(),
                                               death_recipient_.get(), this);
    if (death_unlink != STATUS_OK) {
      log::error("Error unlinking death recipient from the Gatt HAL");
    }
    gatt_hal_instance_ = nullptr;
  }
}

bool GattHalImpl::Initialize(hal::GattHalCallback const* callback) {
  if (!IsBound()) {
    return false;
  }
  gatt_aidl_cb_->SetCallback(callback);
  return true;
}

hal::GattCapabilities GattHalImpl::GetGattCapabilities() const {
  if (!IsBound()) {
    return {};
  }
  ::aidl::android::hardware::bluetooth::gatt::GattCapabilities gatt_capabilities;
  ::ndk::ScopedAStatus status = gatt_hal_instance_->getGattCapabilities(&gatt_capabilities);
  if (!status.isOk()) {
    log::info("Failed to get gatt capabilities");
    return {};
  }
  log::info(
          "gatt capabilities supportedGattClientProperties: 0x{:x}, "
          "supportedGattServerProperties: 0x{:x}",
          gatt_capabilities.supportedGattClientProperties,
          gatt_capabilities.supportedGattServerProperties);

  if (gatt_capabilities.supportedGattClientProperties) {
    if (!(gatt_capabilities.supportedGattClientProperties & kGattPropertyNotify)) {
      log::error("Mandatory client properties: 0x{:x} are not supported", kGattPropertyNotify);
      return {};
    }
  }
  if (gatt_capabilities.supportedGattServerProperties) {
    if (!(gatt_capabilities.supportedGattServerProperties & kGattPropertyNotify)) {
      log::error("Mandatory server properties: 0x{:x} are not supported", kGattPropertyNotify);
      return {};
    }
  }

  return hal::GattCapabilities{
          .supported_gatt_client_properties = gatt_capabilities.supportedGattClientProperties,
          .supported_gatt_server_properties = gatt_capabilities.supportedGattServerProperties};
}

bool GattHalImpl::RegisterService(const hal::GattSession& session) const {
  if (!IsBound()) {
    return false;
  }
  log::info(
          "session_id: {}, acl_connection_handle: 0x{:x}, att_mtu: {}, role: {}, service_uuid: {}, "
          "hub_id: {}, endpoint_id: {}",
          session.id, session.acl_connection_handle, session.att_mtu,
          static_cast<int>(session.role), session.service_uuid, session.endpoint_info.hub_id,
          session.endpoint_info.endpoint_id);

  ::aidl::android::hardware::bluetooth::gatt::GattSession::Role gatt_role =
          (session.role == hal::GattRole::GATT_SERVER)
                  ? ::aidl::android::hardware::bluetooth::gatt::GattSession::Role::SERVER
                  : ::aidl::android::hardware::bluetooth::gatt::GattSession::Role::CLIENT;

  ::aidl::android::hardware::bluetooth::gatt::Uuid service_uuid;
  std::copy(session.service_uuid.To128BitLE().begin(), session.service_uuid.To128BitLE().end(),
            service_uuid.uuid.data());

  std::vector<::aidl::android::hardware::bluetooth::gatt::GattCharacteristic> characteristics;
  std::transform(session.characteristics.begin(), session.characteristics.end(),
                 std::back_inserter(characteristics), [](hal::GattCharacteristic hal_char) {
                   ::aidl::android::hardware::bluetooth::gatt::GattCharacteristic aidl_char{
                           .properties = hal_char.properties,
                           .valueHandle = hal_char.value_handle,
                   };
                   auto hal_uuid = hal_char.uuid.To128BitLE();
                   std::copy(hal_uuid.begin(), hal_uuid.end(), aidl_char.uuid.uuid.begin());
                   return aidl_char;
                 });

  ::aidl::android::hardware::contexthub::EndpointId endpoint_id = {
          .id = static_cast<int64_t>(session.endpoint_info.endpoint_id),
          .hubId = static_cast<int64_t>(session.endpoint_info.hub_id),
  };

  ::aidl::android::hardware::bluetooth::gatt::GattSession aidl_session = {
          .sessionId = session.id,
          .aclConnectionHandle = session.acl_connection_handle,
          .attMtu = session.att_mtu,
          .role = gatt_role,
          .serviceUuid = service_uuid,
          .characteristics = characteristics,
          .endpointId = endpoint_id,
  };

  ::ndk::ScopedAStatus status = gatt_hal_instance_->registerService(aidl_session);
  if (!status.isOk()) {
    log::error("registerService failure: {}", status.getDescription());
    return false;
  }
  return true;
}

void GattHalImpl::UnregisterService(int session_id) const {
  if (!IsBound()) {
    return;
  }
  log::info("session_id: {}", session_id);
  ::ndk::ScopedAStatus status = gatt_hal_instance_->unregisterService(session_id);
  if (!status.isOk()) {
    log::info("unregisterService failure: {}", status.getDescription());
  }
}

void GattHalImpl::ClearServices(int acl_connection_handle) const {
  if (!IsBound()) {
    return;
  }
  log::info("acl_connection_handle: 0x{:x}", acl_connection_handle);
  ::ndk::ScopedAStatus status = gatt_hal_instance_->clearServices(acl_connection_handle);
  if (!status.isOk()) {
    log::info("clearServices failure: {}", status.getDescription());
  }
}

}  // namespace bluetooth::hal
