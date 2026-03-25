/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/******************************************************************************
 *
 *  This file contains functions for BLE controller based privacy.
 *
 ******************************************************************************/
#define LOG_TAG "ble_priv"

#include "stack/include/btm_ble_privacy.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_octets.h>
#include <com_android_bluetooth_flags.h>

#include <algorithm>

#include "btm_dev.h"
#include "btm_security.h"
#include "hci/acl_manager/acl_manager_le.h"
#include "hci/controller.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "osi/include/allocator.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/include/ble_hci_link_interface.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_client_interface.h"

using namespace bluetooth;

/* RPA offload VSC specifics */
#define HCI_VENDOR_BLE_RPA_VSC (0x0155 | HCI_GRP_VENDOR_SPECIFIC)

#define BTM_BLE_META_IRK_ENABLE 0x01
#define BTM_BLE_META_ADD_IRK_ENTRY 0x02
#define BTM_BLE_META_REMOVE_IRK_ENTRY 0x03
#define BTM_BLE_META_CLEAR_IRK_LIST 0x04
#define BTM_BLE_META_READ_IRK_ENTRY 0x05
#define BTM_BLE_META_CS_RESOLVE_ADDR 0x00000001
#define BTM_BLE_IRK_ENABLE_LEN 2

#define BTM_BLE_META_ADD_IRK_LEN 24
#define BTM_BLE_META_REMOVE_IRK_LEN 8
#define BTM_BLE_META_CLEAR_IRK_LEN 1
#define BTM_BLE_META_READ_IRK_LEN 2
#define BTM_BLE_META_ADD_WL_ATTR_LEN 9

/*******************************************************************************
 *         Functions implemented controller based privacy using Resolving List
 ******************************************************************************/
/*******************************************************************************
 *
 * Function         btm_ble_enq_resolving_list_pending
 *
 * Description      add target address into resolving pending operation queue
 *
 * Parameters       target_bda: target device address
 *                  add_entry: true for add entry, false for remove entry
 *
 * Returns          void
 *
 ******************************************************************************/
static void btm_ble_enq_resolving_list_pending(const RawAddress& pseudo_bda, uint8_t op_code) {
  tBTM_BLE_RESOLVE_Q* p_q = &btm_cb.ble_ctr_cb.resolving_list_pend_q;

  p_q->resolve_q_random_pseudo[p_q->q_next] = pseudo_bda;
  p_q->resolve_q_action[p_q->q_next] = op_code;
  p_q->q_next++;
  p_q->q_next %= bluetooth::shim::GetController()->GetLeResolvingListSize();
}

/*******************************************************************************
 *
 * Function         btm_ble_brcm_find_resolving_pending_entry
 *
 * Description      check to see if the action is in pending list
 *
 * Parameters       true: action pending;
 *                  false: new action
 *
 * Returns          void
 *
 ******************************************************************************/
static bool btm_ble_brcm_find_resolving_pending_entry(const RawAddress& pseudo_addr,
                                                      uint8_t action) {
  tBTM_BLE_RESOLVE_Q* p_q = &btm_cb.ble_ctr_cb.resolving_list_pend_q;

  for (uint8_t i = p_q->q_pending; i != p_q->q_next;) {
    if (p_q->resolve_q_random_pseudo[i] == pseudo_addr && action == p_q->resolve_q_action[i]) {
      return true;
    }

    i++;
    i %= bluetooth::shim::GetController()->GetLeResolvingListSize();
  }
  return false;
}

/*******************************************************************************
 *
 * Function         btm_ble_deq_resolving_pending
 *
 * Description      dequeue target address from resolving pending operation
 *                  queue
 *
 * Parameters       pseudo_addr: pseudo_addr device address
 *
 * Returns          void
 *
 ******************************************************************************/
static bool btm_ble_deq_resolving_pending(RawAddress& pseudo_addr) {
  tBTM_BLE_RESOLVE_Q* p_q = &btm_cb.ble_ctr_cb.resolving_list_pend_q;

  if (p_q->q_next != p_q->q_pending) {
    pseudo_addr = p_q->resolve_q_random_pseudo[p_q->q_pending];
    p_q->resolve_q_random_pseudo[p_q->q_pending] = RawAddress::kEmpty;
    p_q->q_pending++;
    p_q->q_pending %= bluetooth::shim::GetController()->GetLeResolvingListSize();
    return true;
  }

  return false;
}

/*******************************************************************************
 *
 * Function         btm_ble_clear_irk_index
 *
 * Description      clear IRK list index mask for availability
 *
 * Returns          none
 *
 ******************************************************************************/
static void btm_ble_clear_irk_index(uint8_t index) {
  uint8_t byte;
  uint8_t bit;

  if (index < bluetooth::shim::GetController()->GetLeResolvingListSize()) {
    byte = index / 8;
    bit = index % 8;
    btm_cb.ble_ctr_cb.irk_list_mask[byte] &= (~(1 << bit));
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_find_irk_index
 *
 * Description      find the first available IRK list index
 *
 * Returns          index from 0 ~ max (127 default)
 *
 ******************************************************************************/
static uint8_t btm_ble_find_irk_index(void) {
  uint8_t i = 0;
  uint8_t byte;
  uint8_t bit;

  while (i < bluetooth::shim::GetController()->GetLeResolvingListSize()) {
    byte = i / 8;
    bit = i % 8;

    if ((btm_cb.ble_ctr_cb.irk_list_mask[byte] & (1 << bit)) == 0) {
      btm_cb.ble_ctr_cb.irk_list_mask[byte] |= (1 << bit);
      return i;
    }
    i++;
  }

  log::error("no index found");
  return i;
}

/*******************************************************************************
 *
 * Function         btm_ble_update_resolving_list
 *
 * Description      update resolving list entry in host maintained record
 *
 * Returns          void
 *
 ******************************************************************************/
static void btm_ble_update_resolving_list(const RawAddress& pseudo_bda, bool add) {
  BtmDevice* p_device = btm_get_dev(pseudo_bda);
  if (p_device == NULL) {
    return;
  }

  if (add) {
    p_device->ble.in_controller_list |= BTM_RESOLVING_LIST_BIT;
    if (!bluetooth::shim::GetController()->SupportsBlePrivacy()) {
      p_device->ble.resolving_list_index = btm_ble_find_irk_index();
    }
  } else {
    p_device->ble.in_controller_list &= ~BTM_RESOLVING_LIST_BIT;
    if (!bluetooth::shim::GetController()->SupportsBlePrivacy()) {
      /* clear IRK list index mask */
      btm_ble_clear_irk_index(p_device->ble.resolving_list_index);
      p_device->ble.resolving_list_index = 0;
    }
  }
}

static bool clear_resolving_list_bit(void* data, void* /* context */) {
  BtmDevice* p_device = static_cast<BtmDevice*>(data);
  p_device->ble.in_controller_list &= ~BTM_RESOLVING_LIST_BIT;
  return true;
}

/*******************************************************************************
 *
 * Function         btm_ble_clear_resolving_list_complete
 *
 * Description      This function is called when command complete for
 *                  clear resolving list
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_clear_resolving_list_complete(bluetooth::hci::CommandCompleteView view) {
  if (!view.IsValid()) {
    log::error("Invalid command complete view");
    return;
  }
  uint8_t status = static_cast<uint8_t>(bluetooth::hci::ErrorCode::SUCCESS);
  auto payload = view.GetPayload();
  auto clear_device_from_resolving_list_complete_view =
          bluetooth::hci::LeClearResolvingListCompleteView::Create(view);
  if (clear_device_from_resolving_list_complete_view.IsValid()) {
    status = static_cast<uint8_t>(clear_device_from_resolving_list_complete_view.GetStatus());
  } else {
    // If it's not a standard Clear Device From Resolving List complete, it might be a VSC.
    // Parse status manually from the payload (first byte).
    if (payload.size() > 0) {
      status = *payload.begin();
    } else {
      log::error("Invalid command complete view: payload empty");
      return;
    }
  }

  log::verbose("status={}", status);

  if (status == HCI_SUCCESS) {
    if (payload.size() >= 3) {
      /* VSC complete has one extra byte for op code and list size, skip it here
       */
      auto it = payload.begin();
      std::advance(it, 2);  // one extra for status

      /* updated the available list size, and current list size */
      uint8_t irk_list_sz_max = *it;

      if (bluetooth::shim::GetController()->GetLeResolvingListSize() == 0) {
        btm_ble_resolving_list_init(irk_list_sz_max);
      }

      uint8_t irk_mask_size =
              (irk_list_sz_max % 8) ? (irk_list_sz_max / 8 + 1) : (irk_list_sz_max / 8);
      memset(btm_cb.ble_ctr_cb.irk_list_mask, 0, irk_mask_size);
    }

    btm_cb.ble_ctr_cb.resolving_list_avail_size =
            bluetooth::shim::GetController()->GetLeResolvingListSize();

    log::verbose("resolving_list_avail_size={}", btm_cb.ble_ctr_cb.resolving_list_avail_size);

    if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
      list_foreach(BtmSecurity::Get().sec_dev_rec_, clear_resolving_list_bit, NULL);
    } else {
      BtmSecurity::Get().for_each_dev_rec(clear_resolving_list_bit, NULL);
    }
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_add_resolving_list_entry_complete
 *
 * Description      This function is called when command complete for
 *                  add resolving list entry
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_add_resolving_list_entry_complete(bluetooth::hci::CommandCompleteView view) {
  if (!view.IsValid()) {
    log::error("Invalid command complete view");
    return;
  }
  uint8_t status = static_cast<uint8_t>(bluetooth::hci::ErrorCode::SUCCESS);
  auto payload = view.GetPayload();
  auto add_device_to_resolving_list_complete_view =
          bluetooth::hci::LeAddDeviceToResolvingListCompleteView::Create(view);
  if (add_device_to_resolving_list_complete_view.IsValid()) {
    status = static_cast<uint8_t>(add_device_to_resolving_list_complete_view.GetStatus());
  } else {
    // If it's not a standard LE Add Device To Resolving List complete, it might be a VSC.
    // Parse status manually from the payload (first byte).
    if (payload.size() > 0) {
      status = *payload.begin();
    } else {
      log::error("Invalid command complete view: payload empty");
      return;
    }
  }
  log::verbose("status={}", status);

  RawAddress pseudo_bda;
  if (!btm_ble_deq_resolving_pending(pseudo_bda)) {
    log::verbose("no pending resolving list operation");
    return;
  }

  if (status == HCI_SUCCESS) {
    btm_ble_update_resolving_list(pseudo_bda, true);
    /* privacy 1.2 command complete does not have these extra byte */
    if (payload.size() > 2) {
      /* VSC complete has one extra byte for op code, skip it here */
      auto it = payload.begin();
      std::advance(it, 2);  // one extra for status
      btm_cb.ble_ctr_cb.resolving_list_avail_size = *it;
    } else {
      btm_cb.ble_ctr_cb.resolving_list_avail_size--;
    }
  } else if (status == HCI_ERR_MEMORY_FULL) /* BT_ERROR_CODE_MEMORY_CAPACITY_EXCEEDED  */
  {
    btm_cb.ble_ctr_cb.resolving_list_avail_size = 0;
    log::verbose("Resolving list Full");
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_remove_resolving_list_entry_complete
 *
 * Description      This function is called when command complete for
 *                  remove resolving list entry
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_remove_resolving_list_entry_complete(bluetooth::hci::CommandCompleteView view) {
  if (!view.IsValid()) {
    log::error("Invalid command complete view");
    return;
  }
  RawAddress pseudo_bda;
  uint8_t status = static_cast<uint8_t>(bluetooth::hci::ErrorCode::SUCCESS);
  auto payload = view.GetPayload();
  auto remove_device_from_resolving_list_complete_view =
          bluetooth::hci::LeRemoveDeviceFromResolvingListCompleteView::Create(view);
  if (remove_device_from_resolving_list_complete_view.IsValid()) {
    status = static_cast<uint8_t>(remove_device_from_resolving_list_complete_view.GetStatus());
  } else {
    // If it's not a standard Remove Device From Resolving List complete, it might be a VSC.
    // Parse status manually from the payload (first byte).
    if (payload.size() > 0) {
      status = *payload.begin();
    } else {
      log::error("Invalid command complete view: payload empty");
      return;
    }
  }

  log::verbose("status={}", status);

  if (!btm_ble_deq_resolving_pending(pseudo_bda)) {
    log::error("no pending resolving list operation");
    return;
  }

  if (status == HCI_SUCCESS) {
    /* proprietary: spec does not have these extra bytes */
    if (payload.size() > 2) {
      auto it = payload.begin();
      std::advance(it, 2);  // one extra for status
      btm_cb.ble_ctr_cb.resolving_list_avail_size = *it;
    } else {
      btm_cb.ble_ctr_cb.resolving_list_avail_size++;
    }
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_read_resolving_list_entry_complete
 *
 * Description      This function is called when command complete for
 *                  read resolving list entry
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_read_resolving_list_entry_complete(bluetooth::hci::CommandCompleteView view) {
  if (!view.IsValid()) {
    log::error("Invalid command complete view");
    return;
  }
  uint8_t status;
  RawAddress rra, pseudo_bda;
  auto payload = view.GetPayload();

  auto read_resolvable_address_complete_view =
          bluetooth::hci::LeReadPeerResolvableAddressCompleteView::Create(view);
  if (read_resolvable_address_complete_view.IsValid()) {
    status = static_cast<uint8_t>(read_resolvable_address_complete_view.GetStatus());
    if (status == HCI_SUCCESS) {
      auto addr = read_resolvable_address_complete_view.GetPeerResolvableAddress();
      rra = RawAddress(addr.address);
    }
  } else {
    // If it's not a standard Read Resolvable Address complete, it might be a
    // VSC. Parse status manually from the payload (first byte).
    if (payload.size() > 0) {
      status = *payload.begin();
    } else {
      log::error("Invalid command complete view: payload empty");
      return;
    }
  }

  log::verbose("status={}", status);

  if (!btm_ble_deq_resolving_pending(pseudo_bda)) {
    log::error("no pending resolving list operation");
    return;
  }

  if (status == HCI_SUCCESS) {
    // If it was a standard command, rra is already populated.
    // If it was VSC, we need to populate it now.
    if (!read_resolvable_address_complete_view.IsValid()) {
      auto it = payload.begin();
      if (payload.size() > 8) {
        // status(1) + subcode(1) + index(1) + IRK(16) + addr type(1) + identity addr type(6)
        // We need to skip 1 + 2 + 16 + 1 + 6 = 26 bytes to get to Peer Resolvable Address
        std::advance(it, 26);
      } else {
        // Skip status (1 byte)
        std::advance(it, 1);
      }

      uint8_t addr[6];
      for (int i = 0; i < 6; i++) {
        if (it != payload.end()) {
          addr[5 - i] = *it;
          ++it;
        } else {
          log::error("Invalid command complete view: payload too short");
          return;
        }
      }
      rra = RawAddress::FromOctets(addr);
      if (payload.size() > 8) {
        log::info("peer_addr:{}", rra);
      }
    }
    btm_ble_refresh_peer_resolvable_private_addr(pseudo_bda, rra,
                                                 tBLE_RAND_ADDR_TYPE::BTM_BLE_ADDR_PSEUDO);
  }
}
/*******************************************************************************
                VSC that implement controller based privacy
 ******************************************************************************/
/*******************************************************************************
 *
 * Function         btm_ble_resolving_list_vsc_op_cmpl
 *
 * Description      IRK operation VSC complete handler
 *
 * Parameters
 *
 * Returns          void
 *
 ******************************************************************************/
static void btm_ble_resolving_list_vsc_op_cmpl(tBTM_VSC_CMPL* p_params) {
  uint8_t *p = p_params->p_param_buf, op_subcode;
  uint16_t evt_len = p_params->param_len;

  op_subcode = *(p + 1);

  log::verbose("op_subcode={}", op_subcode);

  if (op_subcode == BTM_BLE_META_CLEAR_IRK_LIST) {
    std::vector<uint8_t> packet = {
            (uint8_t)bluetooth::hci::EventCode::COMMAND_COMPLETE,
            (uint8_t)(evt_len + 3),  // +3 for NumPackets(1) + OpCode(2)
            1,                       // Num Packets
            static_cast<uint8_t>(bluetooth::hci::OpCode::LE_CLEAR_RESOLVING_LIST),
            static_cast<uint8_t>(bluetooth::hci::OpCode::LE_CLEAR_RESOLVING_LIST) >> 8,
    };
    packet.insert(packet.end(), p, p + evt_len);
    auto packet_ptr = std::make_shared<std::vector<uint8_t>>(std::move(packet));
    auto packet_view = bluetooth::hci::PacketView<bluetooth::hci::kLittleEndian>(packet_ptr);
    auto event_view = bluetooth::hci::EventView::Create(packet_view);
    auto command_complete_view = bluetooth::hci::CommandCompleteView::Create(event_view);
    if (command_complete_view.IsValid()) {
      btm_ble_clear_resolving_list_complete(std::move(command_complete_view));
    }
  } else if (op_subcode == BTM_BLE_META_ADD_IRK_ENTRY) {
    std::vector<uint8_t> packet = {
            (uint8_t)bluetooth::hci::EventCode::COMMAND_COMPLETE,
            (uint8_t)(evt_len + 3),  // +3 for NumPackets(1) + OpCode(2)
            1,                       // Num Packets
            static_cast<uint8_t>(bluetooth::hci::OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST),
            static_cast<uint8_t>(bluetooth::hci::OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST) >> 8,
    };
    packet.insert(packet.end(), p, p + evt_len);
    auto packet_ptr = std::make_shared<std::vector<uint8_t>>(std::move(packet));
    auto packet_view = bluetooth::hci::PacketView<bluetooth::hci::kLittleEndian>(packet_ptr);
    auto event_view = bluetooth::hci::EventView::Create(packet_view);
    auto command_complete_view = bluetooth::hci::CommandCompleteView::Create(event_view);
    if (command_complete_view.IsValid()) {
      btm_ble_add_resolving_list_entry_complete(std::move(command_complete_view));
    }
  } else if (op_subcode == BTM_BLE_META_REMOVE_IRK_ENTRY) {
    std::vector<uint8_t> packet = {
            (uint8_t)bluetooth::hci::EventCode::COMMAND_COMPLETE,
            (uint8_t)(evt_len + 3),  // +3 for NumPackets(1) + OpCode(2)
            1,                       // Num Packets
            static_cast<uint8_t>(bluetooth::hci::OpCode::LE_REMOVE_DEVICE_FROM_RESOLVING_LIST),
            static_cast<uint8_t>(bluetooth::hci::OpCode::LE_REMOVE_DEVICE_FROM_RESOLVING_LIST) >> 8,
    };
    packet.insert(packet.end(), p, p + evt_len);
    auto packet_ptr = std::make_shared<std::vector<uint8_t>>(std::move(packet));
    auto packet_view = bluetooth::hci::PacketView<bluetooth::hci::kLittleEndian>(packet_ptr);
    auto event_view = bluetooth::hci::EventView::Create(packet_view);
    auto command_complete_view = bluetooth::hci::CommandCompleteView::Create(event_view);
    if (command_complete_view.IsValid()) {
      btm_ble_remove_resolving_list_entry_complete(std::move(command_complete_view));
    }
  } else if (op_subcode == BTM_BLE_META_READ_IRK_ENTRY) {
    std::vector<uint8_t> packet = {
            (uint8_t)bluetooth::hci::EventCode::COMMAND_COMPLETE,
            (uint8_t)(evt_len + 3),  // +3 for NumPackets(1) + OpCode(2)
            1,                       // Num Packets
            static_cast<uint8_t>(bluetooth::hci::OpCode::LE_READ_PEER_RESOLVABLE_ADDRESS),
            static_cast<uint8_t>(bluetooth::hci::OpCode::LE_READ_PEER_RESOLVABLE_ADDRESS) >> 8,
    };
    packet.insert(packet.end(), p, p + evt_len);
    auto packet_ptr = std::make_shared<std::vector<uint8_t>>(std::move(packet));
    auto packet_view = bluetooth::hci::PacketView<bluetooth::hci::kLittleEndian>(packet_ptr);
    auto event_view = bluetooth::hci::EventView::Create(packet_view);
    auto command_complete_view = bluetooth::hci::CommandCompleteView::Create(event_view);
    if (command_complete_view.IsValid()) {
      btm_ble_read_resolving_list_entry_complete(std::move(command_complete_view));
    }
  } else if (op_subcode == BTM_BLE_META_IRK_ENABLE) {
    /* RPA offloading enable/disabled */
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_remove_resolving_list_entry
 *
 * Description      This function to remove an IRK entry from the list
 *
 * Parameters       ble_addr_type: address type
 *                  ble_addr: LE adddress
 *
 * Returns          status
 *
 ******************************************************************************/
static tBTM_STATUS btm_ble_remove_resolving_list_entry(BtmDevice* p_device) {
  /* if controller does not support RPA offloading or privacy 1.2, skip */
  if (bluetooth::shim::GetController()->GetLeResolvingListSize() == 0) {
    return tBTM_STATUS::BTM_WRONG_MODE;
  }

  if (bluetooth::shim::GetController()->SupportsBlePrivacy()) {
    auto& addr = p_device->ble.identity_address_with_type;
    bluetooth::shim::GetAclManagerLe()->RemoveDeviceFromResolvingList(
            ToAddressWithType(addr.bda, addr.type));
  } else {
    uint8_t param[20] = {0};
    uint8_t* p = param;

    UINT8_TO_STREAM(p, BTM_BLE_META_REMOVE_IRK_ENTRY);
    UINT8_TO_STREAM(p, p_device->ble.identity_address_with_type.type);
    BDADDR_TO_STREAM(p, p_device->ble.identity_address_with_type.bda);

    get_btm_client_interface().vendor.BTM_VendorSpecificCommand(HCI_VENDOR_BLE_RPA_VSC,
                                                                BTM_BLE_META_REMOVE_IRK_LEN, param,
                                                                btm_ble_resolving_list_vsc_op_cmpl);
    btm_ble_enq_resolving_list_pending(p_device->bd_addr, BTM_BLE_META_REMOVE_IRK_ENTRY);
  }
  return tBTM_STATUS::BTM_CMD_STARTED;
}

/*******************************************************************************
 *
 * Function         btm_ble_clear_resolving_list
 *
 * Description      This function clears the resolving  list
 *
 * Parameters       None.
 *
 ******************************************************************************/
static void btm_ble_clear_resolving_list(void) {
  if (bluetooth::shim::GetController()->SupportsBlePrivacy()) {
    bluetooth::shim::GetAclManagerLe()->ClearResolvingList();
  } else {
    uint8_t param[20] = {0};
    uint8_t* p = param;

    UINT8_TO_STREAM(p, BTM_BLE_META_CLEAR_IRK_LIST);
    get_btm_client_interface().vendor.BTM_VendorSpecificCommand(HCI_VENDOR_BLE_RPA_VSC,
                                                                BTM_BLE_META_CLEAR_IRK_LEN, param,
                                                                btm_ble_resolving_list_vsc_op_cmpl);
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_read_resolving_list_entry
 *
 * Description      This function read an IRK entry by index
 *
 * Parameters       entry index.
 *
 * Returns          true if command successfully sent, false otherwise
 *
 ******************************************************************************/
bool btm_ble_read_resolving_list_entry(BtmDevice* p_device) {
  if (btm_cb.ble_ctr_cb.privacy_mode < BTM_PRIVACY_1_2) {
    log::debug("Privacy 1.2 is not enabled");
    return false;
  }
  if (!(p_device->ble.in_controller_list & BTM_RESOLVING_LIST_BIT)) {
    log::info("Unable to read resolving list entry as resolving bit not set");
    return false;
  }

  if (bluetooth::shim::GetController()->SupportsBlePrivacy()) {
    btsnd_hcic_ble_read_resolvable_addr_peer(p_device->ble.identity_address_with_type.type,
                                             p_device->ble.identity_address_with_type.bda);
  } else {
    uint8_t param[20] = {0};
    uint8_t* p = param;

    UINT8_TO_STREAM(p, BTM_BLE_META_READ_IRK_ENTRY);
    UINT8_TO_STREAM(p, p_device->ble.resolving_list_index);

    get_btm_client_interface().vendor.BTM_VendorSpecificCommand(HCI_VENDOR_BLE_RPA_VSC,
                                                                BTM_BLE_META_READ_IRK_LEN, param,
                                                                btm_ble_resolving_list_vsc_op_cmpl);

    btm_ble_enq_resolving_list_pending(p_device->bd_addr, BTM_BLE_META_READ_IRK_ENTRY);
  }
  return true;
}

static void btm_ble_ble_unsupported_resolving_list_load_dev(BtmDevice* p_device) {
  log::info("Controller does not support BLE privacy");
  uint8_t param[40] = {0};
  uint8_t* p = param;

  UINT8_TO_STREAM(p, BTM_BLE_META_ADD_IRK_ENTRY);
  ARRAY_TO_STREAM(p, p_device->sec_rec.ble_keys.irk, kOctet16Length);
  UINT8_TO_STREAM(p, p_device->ble.identity_address_with_type.type);
  BDADDR_TO_STREAM(p, p_device->ble.identity_address_with_type.bda);

  get_btm_client_interface().vendor.BTM_VendorSpecificCommand(HCI_VENDOR_BLE_RPA_VSC,
                                                              BTM_BLE_META_ADD_IRK_LEN, param,
                                                              btm_ble_resolving_list_vsc_op_cmpl);

  btm_ble_enq_resolving_list_pending(p_device->bd_addr, BTM_BLE_META_ADD_IRK_ENTRY);
  return;
}

static bool is_peer_identity_key_valid(const BtmDevice& device) {
  return device.sec_rec.ble_keys.key_type & BTM_LE_KEY_PID;
}

static Octet16 get_local_irk() { return BtmSecurity::Get().devcb_.id_keys.irk; }

static bool count_resolving_list_entries(void* data, void* context) {
  uint16_t* count = (uint16_t*)context;

  BtmDevice* p_device = static_cast<BtmDevice*>(data);
  if (p_device->ble.in_controller_list & BTM_RESOLVING_LIST_BIT) {
    *count = *count + 1;
  }
  return true;
}

void btm_ble_resolving_list_load_dev(BtmDevice& device) {
  if (btm_cb.ble_ctr_cb.privacy_mode < BTM_PRIVACY_1_2) {
    log::debug("Privacy 1.2 is not enabled");
    return;
  }

  uint8_t resolving_list_size = bluetooth::shim::GetController()->GetLeResolvingListSize();
  if (resolving_list_size == 0) {
    log::info("Controller does not support RPA offloading or privacy 1.2");
    return;
  }

  if (!bluetooth::shim::GetController()->SupportsBlePrivacy()) {
    return btm_ble_ble_unsupported_resolving_list_load_dev(&device);
  }

  // No need to check for local identity key validity. It remains unchanged.
  if (!is_peer_identity_key_valid(device)) {
    log::info("Peer is not an RPA enabled device:{}", device.ble.identity_address_with_type);
    return;
  }

  if (device.ble.in_controller_list & BTM_RESOLVING_LIST_BIT) {
    log::warn("Already in Address Resolving list device:{}", device.ble.identity_address_with_type);
    return;
  }

  const Octet16& peer_irk = device.sec_rec.ble_keys.irk;
  const Octet16& local_irk = get_local_irk();

  if (device.ble.identity_address_with_type.bda.IsEmpty()) {
    device.ble.identity_address_with_type = {
            .type = device.ble.AddressType(),
            .bda = device.bd_addr,
    };
  }

  if (!is_ble_addr_type_known(device.ble.identity_address_with_type.type)) {
    log::error("Adding unknown address type({}) to Address Resolving list.",
               device.ble.identity_address_with_type.type);
    return;
  }

  uint16_t count = 1; /* we use 1 entry for local controller */
  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    list_foreach(BtmSecurity::Get().sec_dev_rec_, count_resolving_list_entries, &count);
  } else {
    count += std::count_if(BtmSecurity::Get().device_records_.begin(),
                           BtmSecurity::Get().device_records_.end(), [](const BtmDevice& dev) {
                             return dev.IsInitialized() &&
                                    dev.ble.in_controller_list & BTM_RESOLVING_LIST_BIT;
                           });
  }

  if (count + 1 > resolving_list_size) {
    log::warn("Le Address Resolution list is full! size:{}", count);
    return;
  }

  bluetooth::shim::GetAclManagerLe()->AddDeviceToResolvingList(
          ToAddressWithType(device.ble.identity_address_with_type.bda,
                            device.ble.identity_address_with_type.type),
          peer_irk, local_irk);

  log::debug("Added to Address Resolving list device:{}", device.ble.identity_address_with_type);

  device.ble.in_controller_list |= BTM_RESOLVING_LIST_BIT;
}

/*******************************************************************************
 *
 * Function         btm_ble_resolving_list_remove_dev
 *
 * Description      This function removes the device from resolving list
 *
 * Parameters
 *
 * Returns          status
 *
 ******************************************************************************/
void btm_ble_resolving_list_remove_dev(BtmDevice* p_device) {
  if (btm_cb.ble_ctr_cb.privacy_mode < BTM_PRIVACY_1_2) {
    log::debug("Privacy 1.2 is not enabled");
    return;
  }

  if ((p_device->ble.in_controller_list & BTM_RESOLVING_LIST_BIT) &&
      !btm_ble_brcm_find_resolving_pending_entry(p_device->bd_addr,
                                                 BTM_BLE_META_REMOVE_IRK_ENTRY)) {
    btm_ble_update_resolving_list(p_device->bd_addr, false);
    btm_ble_remove_resolving_list_entry(p_device);
  } else {
    log::verbose("Device not in resolving list");
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_resolving_list_init
 *
 * Description      Initialize resolving list in host stack
 *
 * Parameters       Max resolving list size
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_resolving_list_init(uint8_t max_irk_list_sz) {
  tBTM_BLE_RESOLVE_Q* p_q = &btm_cb.ble_ctr_cb.resolving_list_pend_q;
  uint8_t irk_mask_size = (max_irk_list_sz % 8) ? (max_irk_list_sz / 8 + 1) : (max_irk_list_sz / 8);

  if (max_irk_list_sz > 0 && p_q->resolve_q_random_pseudo == nullptr) {
    // NOTE: This memory is never freed
    p_q->resolve_q_random_pseudo = (RawAddress*)osi_malloc(sizeof(RawAddress) * max_irk_list_sz);
    // NOTE: This memory is never freed
    p_q->resolve_q_action = (uint8_t*)osi_malloc(max_irk_list_sz);

    /* RPA offloading feature */
    if (btm_cb.ble_ctr_cb.irk_list_mask == NULL) {
      // NOTE: This memory is never freed
      btm_cb.ble_ctr_cb.irk_list_mask = (uint8_t*)osi_malloc(irk_mask_size);
    }

    log::verbose("max_irk_list_sz={}", max_irk_list_sz);
  }

  btm_ble_clear_resolving_list();
  btm_cb.ble_ctr_cb.resolving_list_avail_size = max_irk_list_sz;
}
