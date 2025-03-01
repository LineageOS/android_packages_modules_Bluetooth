/*
 * Copyright 2020 The Android Open Source Project
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

#include "main/shim/acl_api.h"

#include <android_bluetooth_sysprop.h>
#include <base/location.h>
#include <com_android_bluetooth_flags.h>

#include <cstdint>
#include <future>
#include <optional>

#include "hci/acl_manager.h"
#include "hci/remote_name_request.h"
#include "main/shim/acl.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "main/shim/stack.h"
#include "osi/include/allocator.h"
#include "osi/include/properties.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/security_device_record.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/main_thread.h"
#include "stack/include/rnr_interface.h"
#include "stack/rnr/remote_name_request.h"
#include "types/ble_address_with_type.h"
#include "types/raw_address.h"
#ifndef PROPERTY_BLE_PRIVACY_OWN_ADDRESS_ENABLED
#define PROPERTY_BLE_PRIVACY_OWN_ADDRESS_ENABLED \
  "bluetooth.core.gap.le.privacy.own_address_type.enabled"
#endif

namespace {
constexpr char kBtmLogTag[] = "ACL";
}

void bluetooth::shim::ACL_CreateClassicConnection(const RawAddress& raw_address) {
  auto address = ToGdAddress(raw_address);
  Stack::GetInstance()->GetAcl()->CreateClassicConnection(address);
}

void bluetooth::shim::ACL_CancelClassicConnection(const RawAddress& raw_address) {
  auto address = ToGdAddress(raw_address);
  Stack::GetInstance()->GetAcl()->CancelClassicConnection(address);
}

void bluetooth::shim::ACL_WriteData(uint16_t handle, BT_HDR* p_buf) {
  std::unique_ptr<bluetooth::packet::RawBuilder> packet =
          MakeUniquePacket(p_buf->data + p_buf->offset + HCI_DATA_PREAMBLE_SIZE,
                           p_buf->len - HCI_DATA_PREAMBLE_SIZE, IsPacketFlushable(p_buf));
  Stack::GetInstance()->GetAcl()->WriteData(handle, std::move(packet));
  osi_free(p_buf);
}

void bluetooth::shim::ACL_Flush(uint16_t handle) { Stack::GetInstance()->GetAcl()->Flush(handle); }

void bluetooth::shim::ACL_SendConnectionParameterUpdateRequest(
        uint16_t handle, uint16_t conn_int_min, uint16_t conn_int_max, uint16_t conn_latency,
        uint16_t conn_timeout, uint16_t min_ce_len, uint16_t max_ce_len) {
  Stack::GetInstance()->GetAcl()->UpdateConnectionParameters(
          handle, conn_int_min, conn_int_max, conn_latency, conn_timeout, min_ce_len, max_ce_len);
}

void bluetooth::shim::ACL_ConfigureLePrivacy(bool is_le_privacy_enabled) {
  hci::LeAddressManager::AddressPolicy address_policy =
          is_le_privacy_enabled ? hci::LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS
                                : hci::LeAddressManager::AddressPolicy::USE_PUBLIC_ADDRESS;
  /* This is a Floss only flag. Android determines address policy according to
   * privacy mode, hence it is not necessary to enable resolvable address with
   * another sysprop */
  if (com::android::bluetooth::flags::floss_separate_host_privacy_and_llprivacy()) {
    address_policy = hci::LeAddressManager::AddressPolicy::USE_PUBLIC_ADDRESS;
    if (osi_property_get_bool(PROPERTY_BLE_PRIVACY_OWN_ADDRESS_ENABLED, is_le_privacy_enabled)) {
      address_policy = hci::LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS;
    }
  }

  hci::AddressWithType empty_address_with_type(hci::Address{},
                                               hci::AddressType::RANDOM_DEVICE_ADDRESS);

  /* Default to 7 minutes minimum, 15 minutes maximum for random address refreshing;
   * device can override. */
  auto minimum_rotation_time = std::chrono::minutes(
          android::sysprop::bluetooth::Ble::random_address_rotation_interval_min().value_or(7));
  auto maximum_rotation_time = std::chrono::minutes(
          android::sysprop::bluetooth::Ble::random_address_rotation_interval_max().value_or(15));

  Stack::GetInstance()
          ->GetInstance<bluetooth::hci::AclManager>()
          ->SetPrivacyPolicyForInitiatorAddress(address_policy, empty_address_with_type,
                                                minimum_rotation_time, maximum_rotation_time);
}

void bluetooth::shim::ACL_Disconnect(uint16_t handle, bool is_classic, tHCI_STATUS reason,
                                     std::string comment) {
  (is_classic) ? Stack::GetInstance()->GetAcl()->DisconnectClassic(handle, reason, comment)
               : Stack::GetInstance()->GetAcl()->DisconnectLe(handle, reason, comment);
}

void bluetooth::shim::ACL_Shutdown() { Stack::GetInstance()->GetAcl()->Shutdown(); }

void bluetooth::shim::ACL_ReadConnectionAddress(uint16_t handle, RawAddress& conn_addr,
                                                tBLE_ADDR_TYPE* p_addr_type, bool ota_address) {
  std::promise<bluetooth::hci::AddressWithType> promise;
  auto future = promise.get_future();
  Stack::GetInstance()->GetAcl()->GetConnectionLocalAddress(handle, ota_address,
                                                            std::move(promise));
  auto local_address = future.get();

  conn_addr = ToRawAddress(local_address.GetAddress());
  *p_addr_type = static_cast<tBLE_ADDR_TYPE>(local_address.GetAddressType());
}

void bluetooth::shim::ACL_ReadPeerConnectionAddress(uint16_t handle, RawAddress& conn_addr,
                                                    tBLE_ADDR_TYPE* p_addr_type, bool ota_address) {
  std::promise<bluetooth::hci::AddressWithType> promise;
  auto future = promise.get_future();
  Stack::GetInstance()->GetAcl()->GetConnectionPeerAddress(handle, ota_address, std::move(promise));
  auto remote_ota_address = future.get();

  conn_addr = ToRawAddress(remote_ota_address.GetAddress());
  *p_addr_type = static_cast<tBLE_ADDR_TYPE>(remote_ota_address.GetAddressType());
}

std::optional<uint8_t> bluetooth::shim::ACL_GetAdvertisingSetConnectedTo(const RawAddress& addr) {
  std::promise<std::optional<uint8_t>> promise;
  auto future = promise.get_future();
  Stack::GetInstance()->GetAcl()->GetAdvertisingSetConnectedTo(addr, std::move(promise));
  return future.get();
}

void bluetooth::shim::ACL_AddToAddressResolution(const tBLE_BD_ADDR& legacy_address_with_type,
                                                 const Octet16& peer_irk,
                                                 const Octet16& local_irk) {
  Stack::GetInstance()->GetAcl()->AddToAddressResolution(
          ToAddressWithType(legacy_address_with_type.bda, legacy_address_with_type.type), peer_irk,
          local_irk);
}

void bluetooth::shim::ACL_RemoveFromAddressResolution(
        const tBLE_BD_ADDR& legacy_address_with_type) {
  Stack::GetInstance()->GetAcl()->RemoveFromAddressResolution(
          ToAddressWithType(legacy_address_with_type.bda, legacy_address_with_type.type));
}

void bluetooth::shim::ACL_ClearAddressResolution() {
  Stack::GetInstance()->GetAcl()->ClearAddressResolution();
}

void bluetooth::shim::ACL_ClearFilterAcceptList() {
  Stack::GetInstance()->GetAcl()->ClearFilterAcceptList();
}
void bluetooth::shim::ACL_LeSetDefaultSubrate(uint16_t subrate_min, uint16_t subrate_max,
                                              uint16_t max_latency, uint16_t cont_num,
                                              uint16_t sup_tout) {
  Stack::GetInstance()->GetAcl()->LeSetDefaultSubrate(subrate_min, subrate_max, max_latency,
                                                      cont_num, sup_tout);
}

void bluetooth::shim::ACL_LeSubrateRequest(uint16_t hci_handle, uint16_t subrate_min,
                                           uint16_t subrate_max, uint16_t max_latency,
                                           uint16_t cont_num, uint16_t sup_tout) {
  Stack::GetInstance()->GetAcl()->LeSubrateRequest(hci_handle, subrate_min, subrate_max,
                                                   max_latency, cont_num, sup_tout);
}

void bluetooth::shim::ACL_RemoteNameRequest(const RawAddress& addr, uint8_t page_scan_rep_mode,
                                            uint8_t /* page_scan_mode */, uint16_t clock_offset) {
  bluetooth::shim::GetRemoteNameRequest()->StartRemoteNameRequest(
          ToGdAddress(addr),
          hci::RemoteNameRequestBuilder::Create(
                  ToGdAddress(addr), hci::PageScanRepetitionMode(page_scan_rep_mode),
                  clock_offset & (~BTM_CLOCK_OFFSET_VALID),
                  (clock_offset & BTM_CLOCK_OFFSET_VALID) ? hci::ClockOffsetValid::VALID
                                                          : hci::ClockOffsetValid::INVALID),
          GetGdShimHandler()->BindOnce([](hci::ErrorCode status) {
            if (status != hci::ErrorCode::SUCCESS) {
              do_in_main_thread(base::BindOnce(
                      [](hci::ErrorCode status) {
                        // NOTE: we intentionally don't supply the
                        // address, to match the legacy behavior.
                        // Callsites that want the address should use
                        // StartRemoteNameRequest() directly, rather
                        // than going through this shim.
                        get_stack_rnr_interface().btm_process_remote_name(
                                nullptr, nullptr, 0, static_cast<tHCI_STATUS>(status));
                        btm_sec_rmt_name_request_complete(nullptr, nullptr,
                                                          static_cast<tHCI_STATUS>(status));
                      },
                      status));
            }
          }),
          GetGdShimHandler()->BindOnce(
                  [](RawAddress addr, uint64_t features) {
                    static_assert(sizeof(features) == 8);
                    do_in_main_thread(base::BindOnce(btm_sec_rmt_host_support_feat_evt, addr,
                                                     static_cast<uint8_t>(features & 0xff)));
                  },
                  addr),
          GetGdShimHandler()->BindOnce(
                  [](RawAddress addr, hci::ErrorCode status, std::array<uint8_t, 248> name) {
                    do_in_main_thread(base::BindOnce(
                            [](RawAddress addr, hci::ErrorCode status,
                               std::array<uint8_t, 248> name) {
                              get_stack_rnr_interface().btm_process_remote_name(
                                      &addr, name.data(), name.size(),
                                      static_cast<tHCI_STATUS>(status));
                              btm_sec_rmt_name_request_complete(&addr, name.data(),
                                                                static_cast<tHCI_STATUS>(status));
                            },
                            addr, status, name));
                  },
                  addr));
}

void bluetooth::shim::ACL_CancelRemoteNameRequest(const RawAddress& addr) {
  bluetooth::shim::GetRemoteNameRequest()->CancelRemoteNameRequest(ToGdAddress(addr));
}
