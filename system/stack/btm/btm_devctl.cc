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
 *  This file contains functions that handle BTM interface functions for the
 *  Bluetooth device including Rest, HCI buffer size and others
 *
 ******************************************************************************/

#define LOG_TAG "devctl"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include "acl_api_types.h"
#include "btm_sec_int_types.h"
#include "btm_security.h"
#include "hci/controller.h"
#include "main/shim/entry.h"
#include "main/shim/shim.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/connection_manager/connection_manager.h"
#include "stack/include/acl_api.h"
#include "stack/include/acl_api_types.h"
#include "stack/include/acl_hci_link_interface.h"
#include "stack/include/btm_ble_privacy.h"
#include "stack/include/btm_inq.h"
#include "stack/include/btm_sec_api.h"
#include "stack/include/btm_status.h"
#include "stack/include/hcidefs.h"
#include "stack/include/l2cap_controller_interface.h"

using namespace ::bluetooth;

/******************************************************************************/
/*               L O C A L    D A T A    D E F I N I T I O N S                */
/******************************************************************************/

#ifndef BTM_DEV_RESET_TIMEOUT
#define BTM_DEV_RESET_TIMEOUT 4
#endif

// TODO: Reevaluate this value in the context of timers with ms granularity
#define BTM_DEV_NAME_REPLY_TIMEOUT_MS    \
  (2 * 1000) /* 2 seconds for name reply \
              */

#define BTM_INFO_TIMEOUT 5 /* 5 seconds for info response */

/******************************************************************************/
/*            L O C A L    F U N C T I O N     P R O T O T Y P E S            */
/******************************************************************************/

static void decode_controller_support();

/*******************************************************************************
 *
 * Function         btm_db_reset
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_db_reset(void) {
  btm_inq_db_reset();

  if (btm_cb.devcb.p_rln_cmpl_cb) {
    std::vector<uint8_t> packet = {
            static_cast<uint8_t>(bluetooth::hci::EventCode::COMMAND_COMPLETE),
            252,  // Param len
            1,    // Num HCI Cmd Packets
            static_cast<uint8_t>(bluetooth::hci::OpCode::READ_LOCAL_NAME) & 0xFF,
            (static_cast<uint8_t>(bluetooth::hci::OpCode::READ_LOCAL_NAME) >> 8) & 0xFF,
            static_cast<uint8_t>(bluetooth::hci::ErrorCode::HARDWARE_FAILURE),  // Status
    };
    packet.insert(packet.end(), 248, 0);  // Local Name
    auto packet_ptr = std::make_shared<std::vector<uint8_t>>(std::move(packet));
    auto packet_view =
            bluetooth::hci::PacketView<bluetooth::hci::kLittleEndian>(std::move(packet_ptr));
    auto event_view = bluetooth::hci::EventView::Create(std::move(packet_view));
    auto view = bluetooth::hci::CommandCompleteView::Create(std::move(event_view));
    (*btm_cb.devcb.p_rln_cmpl_cb)(std::move(view));
    btm_cb.devcb.p_rln_cmpl_cb = NULL;
  }

  if (btm_cb.devcb.p_rssi_cmpl_cb) {
    tBTM_READ_RSSI_CB* p_cb = btm_cb.devcb.p_rssi_cmpl_cb;
    (*p_cb)(tBTM_STATUS::BTM_DEV_RESET, 0, RawAddress::kEmpty);
    btm_cb.devcb.p_rssi_cmpl_cb = NULL;
  }

  if (btm_cb.devcb.p_automatic_flush_timeout_cmpl_cb) {
    tBTM_READ_AUTOMATIC_FLUSH_TIMEOUT_CB* p_cb = btm_cb.devcb.p_automatic_flush_timeout_cmpl_cb;
    (*p_cb)(RawAddress::kEmpty);
    btm_cb.devcb.p_automatic_flush_timeout_cmpl_cb = NULL;
  }
}

static bool set_sec_state_idle(void* data, void* /* context */) {
  BtmDevice* p_device = static_cast<BtmDevice*>(data);
  p_device->sec_rec.le_link = tSECURITY_STATE::IDLE;
  p_device->sec_rec.classic_link = tSECURITY_STATE::IDLE;
  return true;
}

void BTM_reset_complete() {
  /* Tell L2CAP that all connections are gone */
  l2cu_device_reset();

  /* Clear current security state */
  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    list_foreach(BtmSecurity::Get().sec_dev_rec_, set_sec_state_idle, NULL);
  } else {
    BtmSecurity::Get().for_each_dev_rec(set_sec_state_idle, NULL);
  }

  /* After the reset controller should restore all parameters to defaults. */
  btm_cb.btm_inq_vars.inq_counter = 1;
  btm_cb.btm_inq_vars.inq_scan_window = HCI_DEF_INQUIRYSCAN_WINDOW;
  btm_cb.btm_inq_vars.inq_scan_period = HCI_DEF_INQUIRYSCAN_INTERVAL;
  btm_cb.btm_inq_vars.inq_scan_type = HCI_DEF_SCAN_TYPE;

  btm_cb.btm_inq_vars.page_scan_window = HCI_DEF_PAGESCAN_WINDOW;
  btm_cb.btm_inq_vars.page_scan_period = HCI_DEF_PAGESCAN_INTERVAL;
  btm_cb.btm_inq_vars.page_scan_type = HCI_DEF_SCAN_TYPE;

  connection_manager::reset(true);

  btm_pm_reset();

  l2c_link_init(bluetooth::shim::GetController()->GetNumAclPacketBuffers());

  // setup the random number generator
  std::srand(std::time(nullptr));

  /* Set up the BLE privacy settings */
  if (bluetooth::shim::GetController()->SupportsBle() &&
      bluetooth::shim::GetController()->SupportsBlePrivacy() &&
      bluetooth::shim::GetController()->GetLeResolvingListSize() > 0) {
    btm_ble_resolving_list_init(bluetooth::shim::GetController()->GetLeResolvingListSize());

    // If HCI_LE_Set_Resolvable_Private_Address_Timeout [v2] is supported, RPA generation will be
    // completely offloaded to the controller by LE Address Manager. In that we don't need to use
    // the HCI_LE_Set_Resolvable_Private_Address_Timeout [v1] here.
    if (!bluetooth::shim::GetController()->IsRpaGenerationSupported()) {
      /* Set the default random private address timeout */
      btsnd_hcic_ble_set_rand_priv_addr_timeout(btm_get_next_private_address_interval_ms() / 1000);
    }
  } else {
    log::info("Le Address Resolving list disabled due to lack of controller support");
  }

  if (bluetooth::shim::GetController()->SupportsBle()) {
    l2c_link_process_ble_num_bufs(
            bluetooth::shim::GetController()->GetLeBufferSize().total_num_le_packets_);
  }

  if (!com_android_bluetooth_flags_local_pin_key_type()) {
    get_security_client_interface().BTM_SetPinType(BtmSecurity::Get().cfg_.pin_type,
                                                   BtmSecurity::Get().cfg_.pin_code,
                                                   BtmSecurity::Get().cfg_.pin_code_len);
  }

  decode_controller_support();
}

/*******************************************************************************
 *
 * Function         BTM_IsDeviceUp
 *
 * Description      This function is called to check if the device is up.
 *
 * Returns          true if device is up, else false
 *
 ******************************************************************************/
bool BTM_IsDeviceUp(void) { return bluetooth::shim::is_gd_stack_started_up(); }

static void decode_controller_support() {
  /* Create (e)SCO supported packet types mask */
  btm_cb.btm_sco_pkt_types_supported = 0;
  btm_cb.sco_cb.esco_supported = false;
  if (bluetooth::shim::GetController()->SupportsSco()) {
    btm_cb.btm_sco_pkt_types_supported = ESCO_PKT_TYPES_MASK_HV1;

    if (bluetooth::shim::GetController()->SupportsHv2Packets()) {
      btm_cb.btm_sco_pkt_types_supported |= ESCO_PKT_TYPES_MASK_HV2;
    }

    if (bluetooth::shim::GetController()->SupportsHv3Packets()) {
      btm_cb.btm_sco_pkt_types_supported |= ESCO_PKT_TYPES_MASK_HV3;
    }
  }

  if (bluetooth::shim::GetController()->SupportsEv3Packets()) {
    btm_cb.btm_sco_pkt_types_supported |= ESCO_PKT_TYPES_MASK_EV3;
  }

  if (bluetooth::shim::GetController()->SupportsEv4Packets()) {
    btm_cb.btm_sco_pkt_types_supported |= ESCO_PKT_TYPES_MASK_EV4;
  }

  if (bluetooth::shim::GetController()->SupportsEv5Packets()) {
    btm_cb.btm_sco_pkt_types_supported |= ESCO_PKT_TYPES_MASK_EV5;
  }

  if (btm_cb.btm_sco_pkt_types_supported & BTM_ESCO_LINK_ONLY_MASK) {
    btm_cb.sco_cb.esco_supported = true;

    /* Add in EDR related eSCO types */
    if (bluetooth::shim::GetController()->SupportsEsco2mPhy()) {
      if (!bluetooth::shim::GetController()->Supports3SlotEdrPackets()) {
        btm_cb.btm_sco_pkt_types_supported |= ESCO_PKT_TYPES_MASK_NO_2_EV5;
      }
    } else {
      btm_cb.btm_sco_pkt_types_supported |=
              (ESCO_PKT_TYPES_MASK_NO_2_EV3 + ESCO_PKT_TYPES_MASK_NO_2_EV5);
    }

    if (bluetooth::shim::GetController()->SupportsEsco3mPhy()) {
      if (!bluetooth::shim::GetController()->Supports3SlotEdrPackets()) {
        btm_cb.btm_sco_pkt_types_supported |= ESCO_PKT_TYPES_MASK_NO_3_EV5;
      }
    } else {
      btm_cb.btm_sco_pkt_types_supported |=
              (ESCO_PKT_TYPES_MASK_NO_3_EV3 + ESCO_PKT_TYPES_MASK_NO_3_EV5);
    }
  }

  log::verbose("Local supported SCO packet types: 0x{:04x}", btm_cb.btm_sco_pkt_types_supported);

  BTM_acl_after_controller_started();
  btm_sec_dev_reset();

  if (bluetooth::shim::GetController()->SupportsRssiWithInquiryResults()) {
    if (bluetooth::shim::GetController()->SupportsExtendedInquiryResponse()) {
      if (BTM_SetInquiryMode(BTM_INQ_RESULT_EXTENDED) != tBTM_STATUS::BTM_SUCCESS) {
        log::warn("Unable to set inquiry mode BTM_INQ_RESULT_EXTENDED");
      }
    } else {
      if (BTM_SetInquiryMode(BTM_INQ_RESULT_WITH_RSSI) != tBTM_STATUS::BTM_SUCCESS) {
        log::warn("Unable to set inquiry mode BTM_INQ_RESULT_WITH_RSSI");
      }
    }
  }

  l2cu_set_non_flushable_pbf(bluetooth::shim::GetController()->SupportsNonFlushablePb());
  BTM_EnableInterlacedPageScan();
  BTM_EnableInterlacedInquiryScan();
}

/*******************************************************************************
 *
 * Function         BTM_SetLocalDeviceName
 *
 * Description      This function is called to set the local device name.
 *
 * Returns          status of the operation
 *
 ******************************************************************************/
tBTM_STATUS BTM_SetLocalDeviceName(const char* p_name) {
  if (!p_name || !p_name[0] || (strlen(p_name) > BD_NAME_LEN)) {
    return tBTM_STATUS::BTM_ILLEGAL_VALUE;
  }

  if (bluetooth::shim::GetController() == nullptr) {
    return tBTM_STATUS::BTM_DEV_RESET;
  }
  /* Save the device name if local storage is enabled */

  bd_name_from_char_pointer(BtmSecurity::Get().cfg_.bd_name, p_name);

  bluetooth::shim::GetController()->WriteLocalName(p_name);
  return tBTM_STATUS::BTM_CMD_STARTED;
}

/*******************************************************************************
 *
 * Function         BTM_ReadLocalDeviceName
 *
 * Description      This function is called to read the local device name.
 *
 * Returns          status of the operation
 *                  If success, tBTM_STATUS::BTM_SUCCESS is returned and p_name points stored
 *                              local device name
 *                  If BTM doesn't store local device name, tBTM_STATUS::BTM_NO_RESOURCES is
 *                              is returned and p_name is set to NULL
 *
 ******************************************************************************/
tBTM_STATUS BTM_ReadLocalDeviceName(const char** p_name) {
  *p_name = (const char*)BtmSecurity::Get().cfg_.bd_name;
  return tBTM_STATUS::BTM_SUCCESS;
}

/*******************************************************************************
 *
 * Function         BTM_SetDeviceClass
 *
 * Description      This function is called to set the local device class
 *
 * Returns          status of the operation
 *
 ******************************************************************************/
tBTM_STATUS BTM_SetDeviceClass(DEV_CLASS dev_class) {
  if (btm_cb.devcb.dev_class == dev_class) {
    return tBTM_STATUS::BTM_SUCCESS;
  }

  btm_cb.devcb.dev_class = dev_class;

  if (bluetooth::shim::GetController() == nullptr) {
    return tBTM_STATUS::BTM_DEV_RESET;
  }

  btsnd_hcic_write_dev_class(dev_class);

  return tBTM_STATUS::BTM_SUCCESS;
}

/*******************************************************************************
 *
 * Function         BTM_ReadDeviceClass
 *
 * Description      This function is called to read the local device class
 *
 * Returns          the device class
 *
 ******************************************************************************/
DEV_CLASS BTM_ReadDeviceClass(void) { return btm_cb.devcb.dev_class; }

/*******************************************************************************
 *
 * Function         BTM_VendorSpecificCommand
 *
 * Description      Send a vendor specific HCI command to the controller.
 *
 * Notes
 *      Opcode will be OR'd with HCI_GRP_VENDOR_SPECIFIC.
 *
 ******************************************************************************/
void BTM_VendorSpecificCommand(uint16_t opcode, uint8_t param_len, uint8_t* p_param_buf,
                               tBTM_VSC_CMPL_CB* p_cb) {
  log::verbose("BTM: Opcode: 0x{:04X}, ParamLen: {}.", opcode, param_len);

  /* Send the HCI command (opcode will be OR'd with HCI_GRP_VENDOR_SPECIFIC) */
  btsnd_hcic_vendor_spec_cmd(opcode, param_len, p_param_buf, p_cb);
}

/*******************************************************************************
 *
 * Function         BTM_WritePageTimeout
 *
 * Description      Send HCI Write Page Timeout.
 *
 ******************************************************************************/
void BTM_WritePageTimeout(uint16_t timeout) {
  log::verbose("BTM: BTM_WritePageTimeout: Timeout: {}.", timeout);

  /* Send the HCI command */
  btsnd_hcic_write_page_tout(timeout);
}

/*******************************************************************************
 *
 * Function         BTM_WriteVoiceSettings
 *
 * Description      Send HCI Write Voice Settings command.
 *                  See hcidefs.h for settings bitmask values.
 *
 ******************************************************************************/
void BTM_WriteVoiceSettings(uint16_t settings) {
  log::verbose("BTM: BTM_WriteVoiceSettings: Settings: 0x{:04x}.", settings);

  /* Send the HCI command */
  btsnd_hcic_write_voice_settings((uint16_t)(settings & 0x03ff));
}
