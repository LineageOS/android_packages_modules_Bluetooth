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

#pragma once

#include <bluetooth/types/acl_link_spec.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/hci_role.h>
#include <bluetooth/types/remote_version.h>

#include <cstdint>
#include <string>
#include <vector>

#include "internal_include/bt_target.h"
#include "internal_include/bt_trace.h"
#include "stack/acl/peer_packet_types.h"
#include "stack/btm/power_mode.h"
#include "stack/include/btm_status.h"
#include "stack/include/hcimsgs.h"

enum class BtmAclEncryptState : uint8_t {
  kIdle = 0,
  kEncryptOff,
  kTemporaryOff,
  kEncryptOn,
};

enum class BtmAclSwitchKeyState : uint8_t {
  kIdle = 0,
  kModeChange,
  kEncryptionOff,
  kSwitching,
  kEncryptionOn,
  kInProgress,
};

struct LinkPolicy {
  // Hold mode is not supported in Android
  // Park mode is deprecated in the Bluetooth spec
  bool role_switch;
  bool sniff_mode;

public:
  constexpr uint16_t toUint16() const { return (role_switch << 0) | (sniff_mode << 2); }
  constexpr operator uint16_t() const { return toUint16(); }
};

inline std::string link_policy_text(const LinkPolicy& policy) {
  std::ostringstream os;
  os << "role_switch: " << (policy.role_switch == 0 ? "disabled" : "enabled") << ", ";
  os << "sniff_mode: " << (policy.sniff_mode == 0 ? "disabled" : "enabled");
  return os.str();
}

constexpr LinkPolicy kLinkPolicyDefault = {
        .role_switch = true,
        .sniff_mode = true,
};

// Power mode states.
// Used as both value and bitmask
enum : uint8_t {
  BTM_PM_ST_ACTIVE = HCI_MODE_ACTIVE,      // 0x00
  BTM_PM_ST_SNIFF = HCI_MODE_SNIFF,        // 0x02
  BTM_PM_ST_UNUSED,                        // 0x04
  BTM_PM_ST_PENDING = BTM_PM_STS_PENDING,  // 0x05
  BTM_PM_ST_INVALID = 0x7F,
  BTM_PM_STORED_MASK = 0x80, /* set this mask if the command is stored */
};
typedef uint8_t tBTM_PM_STATE;

inline std::string power_mode_state_text(tBTM_PM_STATE state) {
  std::string s = std::string((state & BTM_PM_STORED_MASK) ? "stored:" : "immediate:");
  switch (state & ~BTM_PM_STORED_MASK) {
    case BTM_PM_ST_ACTIVE:
      return s + std::string("active");
    case BTM_PM_ST_SNIFF:
      return s + std::string("sniff");
    case BTM_PM_ST_UNUSED:
      return s + std::string("WARN:UNUSED");
    case BTM_PM_ST_PENDING:
      return s + std::string("pending");
    case BTM_PM_ST_INVALID:
      return s + std::string("invalid");
    default:
      return s + std::string("UNKNOWN");
  }
}

namespace bluetooth {
namespace shim {
tBTM_STATUS BTM_SetPowerMode(uint16_t handle, const tBTM_PM_PWR_MD& new_mode);
tBTM_STATUS BTM_SetSsrParams(uint16_t handle, uint16_t max_lat, uint16_t min_rmt_to,
                             uint16_t min_loc_to);
void btm_pm_on_mode_change(tHCI_STATUS status, uint16_t handle, tHCI_MODE hci_mode,
                           uint16_t interval);
void btm_pm_on_sniff_subrating(tHCI_STATUS status, uint16_t handle,
                               uint16_t maximum_transmit_latency, uint16_t maximum_receive_latency,
                               uint16_t minimum_remote_timeout, uint16_t minimum_local_timeout);
}  // namespace shim
}  // namespace bluetooth

typedef struct {
  uint16_t max_xmit_latency;
  uint16_t max_recv_latency;
  uint16_t min_remote_timeout;
  uint16_t min_local_timeout;
} tSSR_PARAMS;

#define BTM_PM_REC_NOT_USED 0
typedef struct tBTM_PM_RCB {
  tBTM_PM_STATUS_CBACK* cback = nullptr; /* to notify the registered party of mode change event */
  uint8_t mask = 0;                      /* registered request mask. 0, if this entry is not used */
} tBTM_PM_RCB;

/* Structure returned with Role Switch information (in tBTM_CMPL_CB callback
 * function) in response to BTM_SwitchRoleToCentral call.
 */
typedef struct {
  RawAddress remote_bd_addr; /* Remote BD addr involved with the switch */
  tHCI_STATUS hci_status;    /* HCI status returned with the event */
  tHCI_ROLE role;            /* HCI_ROLE_CENTRAL or HCI_ROLE_PERIPHERAL */
} tBTM_ROLE_SWITCH_CMPL;

struct tBTM_PM_MCB {
  bool chg_ind = false;
  tBTM_PM_PWR_MD req_mode;
  tBTM_PM_PWR_MD set_mode;
  tBTM_PM_STATE state = BTM_PM_ST_ACTIVE;  // 0
  uint16_t interval = 0;
  uint16_t max_lat = 0;
  uint16_t min_loc_to = 0;
  uint16_t min_rmt_to = 0;
  void Init(RawAddress bda, uint16_t handle) {
    bda_ = bda;
    handle_ = handle;
  }
  RawAddress bda_;
  uint16_t handle_;
};

struct tACL_CONN {
  AclLinkSpec link_spec;
  tBLE_BD_ADDR active_addrt;

  bool in_use{false};

  BD_FEATURES peer_le_features;
  bool peer_le_features_valid;
  BD_FEATURES peer_lmp_feature_pages[HCI_EXT_FEATURES_PAGE_MAX + 1];
  bool peer_lmp_feature_valid[HCI_EXT_FEATURES_PAGE_MAX + 1];

  /* Whether "Read Remote Version Information Complete" was received */
  bool remote_version_received{false};

public:
  bool InUse() const { return in_use; }
  const RawAddress RemoteAddress() const { return link_spec.addrt.bda; }

  bool link_up_issued;
  bool is_transport_br_edr() const { return link_spec.transport == BT_TRANSPORT_BR_EDR; }
  bool is_transport_ble() const { return link_spec.transport == BT_TRANSPORT_LE; }
  bool is_transport_valid() const { return is_transport_ble() || is_transport_br_edr(); }

  uint16_t flush_timeout_in_ticks;
  uint16_t hci_handle;
  LinkPolicy link_policy;

public:
  uint16_t Handle() const { return hci_handle; }
  uint16_t link_super_tout;
  uint16_t pkt_types_mask;
  uint8_t disconnect_reason;

public:
  BtmAclEncryptState encrypt_state_ = BtmAclEncryptState::kIdle;

public:
  bool is_encrypted = false;
  tHCI_ROLE link_role;
  uint8_t switch_role_failed_attempts;

  tREMOTE_VERSION_INFO remote_version_info;

#define BTM_SEC_RS_NOT_PENDING 0 /* Role Switch not in progress */
#define BTM_SEC_RS_PENDING 1     /* Role Switch in progress */
#define BTM_SEC_DISC_PENDING 2   /* Disconnect is pending */

private:
  uint8_t rs_disc_pending = BTM_SEC_RS_NOT_PENDING;
  friend struct StackAclBtmAcl;
  friend tBTM_STATUS btm_remove_acl(const RawAddress& bd_addr, tBT_TRANSPORT transport);
  friend void acl_disconnect_after_role_switch(uint16_t conn_handle, tHCI_STATUS reason,
                                               std::string);
  friend void bluetooth::shim::btm_pm_on_mode_change(tHCI_STATUS status, uint16_t handle,
                                                     tHCI_MODE hci_mode, uint16_t interval);
  friend void btm_acl_encrypt_change(uint16_t handle, uint8_t status, uint8_t encr_enable);

public:
  bool is_disconnect_pending() const { return rs_disc_pending == BTM_SEC_DISC_PENDING; }
  bool is_role_switch_pending() const { return rs_disc_pending == BTM_SEC_RS_PENDING; }

public:
  BtmAclSwitchKeyState switch_role_state_ = BtmAclSwitchKeyState::kIdle;

public:
  uint8_t sca; /* Sleep clock accuracy */

  void Reset();
};

/****************************************************
 **      ACL Management API
 ****************************************************/
constexpr uint16_t kDefaultPacketTypeMask = HCI_PKT_TYPES_MASK_DH1 | HCI_PKT_TYPES_MASK_DM1 |
                                            HCI_PKT_TYPES_MASK_DH3 | HCI_PKT_TYPES_MASK_DM3 |
                                            HCI_PKT_TYPES_MASK_DH5 | HCI_PKT_TYPES_MASK_DM5;

struct tACL_CB {
private:
  friend uint8_t btm_handle_to_acl_index(uint16_t hci_handle);
  friend void btm_acl_device_down(void);
  friend void btm_acl_encrypt_change(uint16_t handle, uint8_t status, uint8_t encr_enable);

  friend void DumpsysAcl(int fd);
  friend struct StackAclBtmAcl;

  tACL_CONN acl_db[MAX_L2CAP_LINKS];
  tBTM_ROLE_SWITCH_CMPL switch_role_ref_data;
  uint16_t btm_acl_pkt_types_supported = kDefaultPacketTypeMask;
  tHCI_STATUS acl_disc_reason = HCI_ERR_UNDEFINED;

public:
  void SetDefaultPacketTypeMask(uint16_t packet_type_mask) {
    btm_acl_pkt_types_supported = packet_type_mask;
  }

  tHCI_STATUS get_disconnect_reason() const { return acl_disc_reason; }
  void set_disconnect_reason(tHCI_STATUS reason) { acl_disc_reason = reason; }
  uint16_t DefaultPacketTypes() const { return btm_acl_pkt_types_supported; }

  struct {
    std::vector<tBTM_PM_STATUS_CBACK*> clients;
  } link_policy;

  unsigned NumberOfActiveLinks() const {
    unsigned cnt = 0;
    for (int i = 0; i < MAX_L2CAP_LINKS; i++) {
      if (acl_db[i].InUse()) {
        ++cnt;
      }
    }
    return cnt;
  }
};

tACL_CONN* btm_acl_for_bda(const RawAddress& bd_addr, tBT_TRANSPORT transport);

void btm_acl_encrypt_change(uint16_t handle, uint8_t status, uint8_t encr_enable);

namespace std {
template <>
struct formatter<LinkPolicy> : string_formatter<LinkPolicy, link_policy_text> {};
}  // namespace std
