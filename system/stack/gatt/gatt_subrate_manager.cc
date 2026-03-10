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

/******************************************************************************
 *
 *  this file contains the GATT Subrate Manager functions
 *
 ******************************************************************************/

#define LOG_TAG "gatt_subrate_mgr"

#include <android_bluetooth_sysprop.h>
#include <bluetooth/log.h>
#include <cstdint>
#include "gatt_int.h"

#include "hci/controller.h"
#include "main/shim/entry.h"
#include "stack/include/acl_api.h"
#include "stack/btm/btm_dev.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/l2cap_interface.h"
#include "stack/l2cap/l2c_int.h"
#include "stack/include/main_thread.h"

using namespace bluetooth;

/*******************************************************************************
 *                      G L O B A L      G A T T       D A T A                 *
 ******************************************************************************/
static std::list<tGATT_SUBRATE_MODE> subrate_mode_priority_list = {
    GATT_SUBRATE_MODE_LEA,
    GATT_SUBRATE_MODE_HIGH,
    GATT_SUBRATE_MODE_BALANCED,
    GATT_SUBRATE_MODE_LOW,
    GATT_SUBRATE_MODE_OFF,
};

static uint16_t get_cont_num_by_mode(tGATT_SUBRATE_MODE mode, uint16_t subrate_min,
                                     uint16_t subrate_max) {
    uint16_t cont_num = 0;

    if (gatt_cb.subrate_mode_config[mode].fixed_config) {
        cont_num = gatt_cb.subrate_mode_config[mode].cont_num;
    } else {
        uint16_t ratio = gatt_cb.subrate_mode_config[mode].cont_num_ratio;
        cont_num = subrate_min * ratio / 100;
        cont_num = ((subrate_min * ratio) % 100 < 50) ? cont_num : cont_num + 1;
        if (gatt_cb.subrate_mode_config[mode].cont_num_max > 0) {
            uint16_t max = gatt_cb.subrate_mode_config[mode].cont_num_max;
            cont_num = (cont_num > max) ? max : cont_num;
        }
    }
    if (subrate_max == 1 && subrate_min == 1) cont_num = 0;
    return cont_num;
}

static std::pair<uint16_t, uint16_t> get_subrate_factor_by_mode(tGATT_SUBRATE_MODE mode,
                                                                const RawAddress& bd_addr) {

    uint16_t conn_interval = 1.25 * stack::l2cap::get_interface().L2CA_GetBleConnInterval(bd_addr);
    uint16_t periph_latency = stack::l2cap::get_interface().L2CA_GetBlePeriphLatency(bd_addr);
    uint16_t target_latency = conn_interval * (periph_latency + 1);

    uint16_t subrate_min = 1;
    uint16_t subrate_max = 1;

    subrate_max = target_latency / conn_interval;
    if (target_latency % conn_interval > 0) subrate_max++;
    subrate_min = (subrate_max > 2 ) ? subrate_max - 2 : 1;

    if (gatt_cb.subrate_mode_config[mode].fixed_config) {
        subrate_min = gatt_cb.subrate_mode_config[mode].subrate_min;
        subrate_max = gatt_cb.subrate_mode_config[mode].subrate_max;
    }

    std::pair<uint16_t, uint16_t> subrate_pair(subrate_min, subrate_max);

    return subrate_pair;
}

static tGATT_SUBRATE_CONFIG create_config_by_mode(tGATT_SUBRATE_MODE target_mode,
                                                  const RawAddress& bd_addr) {
    log::info("mode:{} address{}", target_mode, bd_addr);

    tGATT_SUBRATE_CONFIG config;

    config.mode = target_mode;
    config.conn_interval = 1.25 * stack::l2cap::get_interface().L2CA_GetBleConnInterval(bd_addr);
    config.periph_latency = stack::l2cap::get_interface().L2CA_GetBlePeriphLatency(bd_addr);

    // calculate the subrate factor
    std::pair<uint16_t, uint16_t> subrate_pair = get_subrate_factor_by_mode(target_mode, bd_addr);
    config.subrate_min = subrate_pair.first;
    config.subrate_max = subrate_pair.second;

    config.cont_num = get_cont_num_by_mode(target_mode, config.subrate_min, config.subrate_max);

    config.max_latency = (target_mode == GATT_SUBRATE_MODE_OFF) ? config.periph_latency : 0;
    config.timeout = stack::l2cap::get_interface().L2CA_GetBleSupervisionTimeout(bd_addr);

    log::info("conn_interval: {} subrate_min:{} subrate_max:{} cont_num:{} ",
              config.conn_interval, config.subrate_min, config.subrate_max, config.cont_num);
    return config;
}

static void update_pending_queue_to_config_map(const RawAddress& bd_addr) {
    if (!gatt_cb.subrate_info.contains(bd_addr)) {
        log::error("{} does not exist in gatt_cb.subrate_info", bd_addr);
        return;
    }

    bool contains_new_request = false;
    while (!gatt_cb.subrate_info[bd_addr].pending_queue.empty()) {
        tGATT_SUBRATE_REQ req = gatt_cb.subrate_info[bd_addr].pending_queue.front();
        if (req.request_type == GATT_SUBRATE_REQ_TYPE_NEW_REQ) {
            for (auto i = gatt_cb.subrate_info[bd_addr].config_map.begin();
                      i != gatt_cb.subrate_info[bd_addr].config_map.end(); i++) {
                i->second.remove(req.client_if);
            }
            if (req.mode != GATT_SUBRATE_MODE_OFF) {
               gatt_cb.subrate_info[bd_addr].config_map[req.mode].push_back(req.client_if);
            }
            contains_new_request = true;
        }
        gatt_cb.subrate_info[bd_addr].pending_queue.pop_front();
    }
    gatt_cb.subrate_info[bd_addr].has_new_request = contains_new_request;
}

static tGATT_SUBRATE_MODE get_target_mode_by_address(const RawAddress& bd_addr) {
    tGATT_SUBRATE_MODE target_mode = GATT_SUBRATE_MODE_OFF;
    for (tGATT_SUBRATE_MODE mode : subrate_mode_priority_list) {
        if (mode == GATT_SUBRATE_MODE_OFF) continue;
        if (!gatt_cb.subrate_info[bd_addr].config_map[mode].empty()) {
            target_mode = mode;
            break;
        }
    }
    log::verbose("target_mode: {}", target_mode);
    return target_mode;
}

static tGATT_SUBRATE_MODE get_current_mode_by_address(const RawAddress& bd_addr) {
    tGATT_SUBRATE_CONFIG config = gatt_cb.subrate_info[bd_addr].current_config;
    if (config.subrate_factor < config.subrate_min ||
        config.subrate_factor > config.subrate_max) {
        if (config.subrate_factor == 1) return GATT_SUBRATE_MODE_OFF;
        else return GATT_SUBRATE_MODE_SYSTEM_UPDATE;
    } else {
        tGATT_SUBRATE_MODE target_mode = get_target_mode_by_address(bd_addr);
        if (gatt_cb.subrate_mode_config[target_mode].fixed_config) {
            if (config.cont_num > gatt_cb.subrate_mode_config[target_mode].cont_num) {
                return GATT_SUBRATE_MODE_SYSTEM_UPDATE;
            }
        } else {
            uint16_t ratio = gatt_cb.subrate_mode_config[target_mode].cont_num_ratio;
            uint16_t cont_num = config.subrate_min * ratio / 100;
            cont_num = ((config.subrate_min * ratio) % 100 < 50) ? cont_num : cont_num + 1;
            if (config.cont_num > cont_num) return GATT_SUBRATE_MODE_SYSTEM_UPDATE;
        }
        return target_mode;
    }
}

static void process_subrate_request(const RawAddress& bd_addr) {
    log::debug("addr:{}", bd_addr);

    if (!gatt_cb.subrate_info.contains(bd_addr)) {
        log::error("{} does not exist in gatt_cb.subrate_info", bd_addr);
        return;
    }

    if (gatt_cb.subrate_info[bd_addr].state != GATT_SUBRATE_SM_IDLE) {
        log::info("skip process: state is not idle, addr:{}", bd_addr);
        return;
    }

    tL2C_LCB* p_lcb = l2cu_find_lcb_by_bd_addr(bd_addr, BT_TRANSPORT_LE);
    if (!p_lcb) {
        log::warn("skip process: there is no lcb, addr:{}", bd_addr);
        return;
    }

    if (!(p_lcb->conn_update_mask & L2C_BLE_AUDIO_PARAM_SUBRATE)) {
        if ((p_lcb->conn_update_mask & L2C_BLE_UPDATE_PENDING) ||
            (p_lcb->conn_update_mask & L2C_BLE_NEW_CONN_PARAM)) {
            log::warn("skip process: connection updating, addr:{}", bd_addr);
            return;
        }
    }

    log::info("start process, addr:{}", bd_addr);
    if (gatt_cb.subrate_info[bd_addr].pending_queue.empty()) {
        log::warn("skip process: there is no subrate info in pending queue");
        return;
    }

    //  update requests in pending queue and check the aggressive mode
    update_pending_queue_to_config_map(bd_addr);
    tGATT_SUBRATE_MODE target_mode = get_target_mode_by_address(bd_addr);

    // create pending config
    tGATT_SUBRATE_CONFIG pending_config = create_config_by_mode(target_mode, bd_addr);

    // check the difference between current and pending config
    bool updated = false;
    tGATT_SUBRATE_CONFIG current_config = gatt_cb.subrate_info[bd_addr].current_config;
    if (pending_config.mode != current_config.mode) {
        updated = true;
    } else {
        // Current & Pending = OFF, no subrate request and require to update
        if (pending_config.mode == GATT_SUBRATE_MODE_OFF) {
            updated = false;
        }
        else if (current_config.subrate_factor >= pending_config.subrate_min &&
                 current_config.subrate_factor <= pending_config.subrate_max &&
                 current_config.cont_num == pending_config.cont_num) {
            updated = false;
        }
        else {
            updated = true;
        }
    }

    if (!updated) {
        // Notify new client_if but no require to update subrate
        if (gatt_cb.subrate_info[bd_addr].has_new_request) {
            gatt_cb.subrate_info[bd_addr].has_new_request = false;
            const BtmDevice* p_device = btm_find_dev(bd_addr);

            if (p_device == nullptr) {
                log::warn("No matching known device in record");
                return;
            }

            gatt_notify_subrate_change(p_device->ble_hci_handle,
                                       current_config.subrate_factor,
                                       current_config.max_latency,
                                       current_config.cont_num,
                                       current_config.timeout, HCI_SUCCESS);
        }
        return;
    }

    // summit new subrate request and change state to pending subrate
    gatt_cb.subrate_info[bd_addr].pending_config = pending_config;
    gatt_cb.subrate_info[bd_addr].has_new_request = false;

    stack::l2cap::get_interface().L2CA_SubrateRequest(
        bd_addr,
        gatt_cb.subrate_info[bd_addr].pending_config.subrate_min,
        gatt_cb.subrate_info[bd_addr].pending_config.subrate_max,
        gatt_cb.subrate_info[bd_addr].pending_config.max_latency,
        gatt_cb.subrate_info[bd_addr].pending_config.cont_num,
        gatt_cb.subrate_info[bd_addr].pending_config.timeout);

    gatt_cb.subrate_info[bd_addr].state = GATT_SUBRATE_SM_CONFIG_PENDING;
}

void gatt_init_subrate_cb(const RawAddress& bd_addr) {
    log::info("addr:{}", bd_addr);

    if (bluetooth::shim::GetController() == nullptr) {
        log::error("controller module is not ready");
        return;
    }

    log::verbose(
        "Subrate flag confirm. local conrtoller - {}, {}: remote controller - {}, remote host - "
        "{}",
        bluetooth::shim::GetController()->SupportsBleConnectionSubrating(), bd_addr,
        acl_peer_supports_ble_connection_subrating(bd_addr),
        acl_peer_supports_ble_connection_subrating_host(bd_addr));

    if (!bluetooth::shim::GetController()->SupportsBleConnectionSubrating() ||
        !acl_peer_supports_ble_connection_subrating(bd_addr) ||
        !acl_peer_supports_ble_connection_subrating_host(bd_addr)) {
        return;
    }

    // init control block when bd_addr is connected and subrate is supported
    tGATT_SUBRATE_MGR_CB cb = {
            .bda = bd_addr,
            .state = GATT_SUBRATE_SM_IDLE,
        };
    gatt_cb.subrate_info[bd_addr] = cb;
}

void gatt_release_subrate_cb(const RawAddress& bd_addr) {
    log::info("addr:{}", bd_addr);
    //release record when bd_addr is disconnected
    gatt_cb.subrate_info.erase(bd_addr);
    return;
}

bool gatt_register_subrate_config(tGATT_IF client_if, const RawAddress& bd_addr,
                                  tGATT_SUBRATE_MODE subrate_mode) {
    log::info("client_if:{} addr:{}, subrate_mode:{}", client_if, bd_addr, subrate_mode);

    // add to pending queue and trigger handle subrate
    tGATT_SUBRATE_REQ req = {
        .request_type = GATT_SUBRATE_REQ_TYPE_NEW_REQ,
        .client_if = client_if,
        .bda = bd_addr,
        .mode = subrate_mode,
    };

    if (!gatt_cb.subrate_info.contains(bd_addr)) {
        log::error("{} is disconnected which does not exist in gatt_cb.subrate_info", bd_addr);
        return false;
    }

    if (acl_link_is_disconnecting(bd_addr, BT_TRANSPORT_LE)) {
        log::error("{} is disconnecting", bd_addr);
        return false;
    }

    auto it = std::find(subrate_mode_priority_list.begin(),
                   subrate_mode_priority_list.end(), subrate_mode);
    if (it == subrate_mode_priority_list.end()) {
        log::error("{} is unknown subrate mode", subrate_mode);
        return false;
    }

    gatt_cb.subrate_info[bd_addr].pending_queue.push_back(req);
    process_subrate_request(bd_addr);
    return true;
}

bool gatt_handle_subrate_cback_status(const RawAddress& bd_addr, uint16_t subrate_factor,
                                 uint16_t latency, uint16_t cont_num,
                                 uint16_t timeout, uint8_t status) {
    // if status == fail && retry < 3, do retury and return false
    // if status == success, update data and return true
    // check pending, and call process_subrate_request(bda)

    log::debug("Received subrate changed, bd_addr: {}, subrate_factor:{}, latency: {},"
               " cont_num: {}, timeout: {}, status: {}",
               bd_addr, subrate_factor, latency, cont_num, timeout, status);

    if (!gatt_cb.subrate_info.contains(bd_addr)) {
        log::error("{} does not exist in gatt_cb.subrate_info", bd_addr);
        return true;
    }

    if (gatt_cb.subrate_info[bd_addr].state == GATT_SUBRATE_SM_CONFIG_PENDING) {
        if (status == HCI_SUCCESS) {
            gatt_cb.subrate_info[bd_addr].current_config = {
                .mode = gatt_cb.subrate_info[bd_addr].pending_config.mode,
                .conn_interval = gatt_cb.subrate_info[bd_addr].pending_config.conn_interval,
                .subrate_min = gatt_cb.subrate_info[bd_addr].pending_config.subrate_min,
                .subrate_max = gatt_cb.subrate_info[bd_addr].pending_config.subrate_max,
                .max_latency = latency,
                .cont_num = cont_num,
                .timeout = timeout,
                .subrate_factor = subrate_factor,
            };

            gatt_cb.subrate_info[bd_addr].pending_config = {
                .mode = GATT_SUBRATE_MODE_OFF,
            };
            gatt_cb.subrate_info[bd_addr].retry_count = 0;
            gatt_cb.subrate_info[bd_addr].state = GATT_SUBRATE_SM_IDLE;
        } else {
            if (gatt_cb.subrate_info[bd_addr].retry_count < GATT_SUBRATE_MAX_RETRY) {
                gatt_cb.subrate_info[bd_addr].retry_count++;

                do_in_main_thread_delayed(
                    base::BindOnce(
                            [](RawAddress bd_addr, uint16_t subrate_min, uint16_t subrate_max,
                                uint16_t max_latency, uint16_t cont_num, uint16_t timeout) {
                                stack::l2cap::get_interface().L2CA_SubrateRequest(
                                    bd_addr, subrate_min, subrate_max,
                                    max_latency, cont_num, timeout);
                            },
                            bd_addr,
                            gatt_cb.subrate_info[bd_addr].pending_config.subrate_min,
                            gatt_cb.subrate_info[bd_addr].pending_config.subrate_max,
                            gatt_cb.subrate_info[bd_addr].pending_config.max_latency,
                            gatt_cb.subrate_info[bd_addr].pending_config.cont_num,
                            gatt_cb.subrate_info[bd_addr].pending_config.timeout),
                    std::chrono::milliseconds(
                        gatt_cb.subrate_info[bd_addr].pending_config.conn_interval));

                return false;
            } else {
                // if retry > GATT_SUBRATE_MAX_RETRY fail, reset and callback
                gatt_cb.subrate_info[bd_addr].retry_count = 0;
                gatt_cb.subrate_info[bd_addr].state = GATT_SUBRATE_SM_IDLE;
                gatt_cb.subrate_info[bd_addr].pending_config = {
                    .mode = GATT_SUBRATE_MODE_OFF,
                };
            }
        }
    } else {
        log::debug("Receive subrate changed but sm mode is idle.");
        if (status == HCI_SUCCESS) {
            if (cont_num != gatt_cb.subrate_info[bd_addr].current_config.cont_num ||
                subrate_factor != gatt_cb.subrate_info[bd_addr].current_config.subrate_factor) {
                gatt_cb.subrate_info[bd_addr].current_config.max_latency = latency;
                gatt_cb.subrate_info[bd_addr].current_config.cont_num = cont_num;
                gatt_cb.subrate_info[bd_addr].current_config.timeout = timeout;
                gatt_cb.subrate_info[bd_addr].current_config.subrate_factor = subrate_factor;
                gatt_cb.subrate_info[bd_addr].current_config.mode =
                    get_current_mode_by_address(bd_addr);
                log::debug("Subrate updated by remote / system");
            }
        }
    }
    process_subrate_request(bd_addr);
    return true;
}

void gatt_handle_conn_parameter_cback_status(const RawAddress& bd_addr, uint16_t interval) {
    // recalculate subrate factor with new interval and set subrate
    log::debug("addr:{} {}", bd_addr, interval);
    tGATT_SUBRATE_REQ req = {
        .request_type = GATT_SUBRATE_REQ_TYPE_CONN_UPDATE,
    };

    if (!gatt_cb.subrate_info.contains(bd_addr)) {
        log::error("{} does not exist in gatt_cb.subrate_info", bd_addr);
        return;
    }

    // According to spec, subrate reset after connection update
    gatt_cb.subrate_info[bd_addr].current_config = {
        .mode = GATT_SUBRATE_MODE_OFF,
    };

    gatt_cb.subrate_info[bd_addr].pending_queue.push_back(req);
    process_subrate_request(bd_addr);
}

void gatt_init_subrate_mode_config() {
    for (tGATT_SUBRATE_MODE mode : subrate_mode_priority_list) {
        tGATT_SUBRATE_MODE_CONFIG config;
        config.mode = mode;

        switch (mode) {
            case GATT_SUBRATE_MODE_LEA:
                config.fixed_config
                    = android::sysprop::bluetooth::Ble::subrate_manager_lea_mode_fixed_flag()
                      .value_or(kDefaultSubrateMgrLeaModeFixedConfig);
                config.cont_num_ratio
                    = android::sysprop::bluetooth::Ble::subrate_manager_lea_mode_cont_num_ratio()
                      .value_or(kDefaultSubrateMgrLeaModeRatio);
                config.cont_num_max
                    = android::sysprop::bluetooth::Ble::subrate_manager_lea_mode_cont_num_max()
                      .value_or(0);
                config.subrate_max
                    = android::sysprop::bluetooth::Ble::subrate_le_audio_mode_max_subrate()
                      .value_or(kDefaultSubrateLeAudioModeMaxSubrate);
                config.subrate_min
                    = android::sysprop::bluetooth::Ble::subrate_le_audio_mode_min_subrate()
                      .value_or(kDefaultSubrateLeAudioModeMinSubrate);
                config.cont_num
                    = android::sysprop::bluetooth::Ble::subrate_le_audio_mode_cont_number()
                      .value_or(kDefaultSubrateLeAudioModeContNum);
                break;
            case GATT_SUBRATE_MODE_HIGH:
                config.fixed_config
                    = android::sysprop::bluetooth::Ble::subrate_manager_high_mode_fixed_flag()
                      .value_or(kDefaultSubrateMgrHighModeFixedConfig);
                config.cont_num_ratio
                    = android::sysprop::bluetooth::Ble::subrate_manager_high_mode_cont_num_ratio()
                      .value_or(kDefaultSubrateMgrHighModeRatio);
                config.cont_num_max
                    = android::sysprop::bluetooth::Ble::subrate_manager_high_mode_cont_num_max()
                      .value_or(0);
                config.subrate_max
                    = android::sysprop::bluetooth::Ble::subrate_mode_high_max_subrate()
                      .value_or(kDefaultSubrateHighModeMaxSubrate);
                config.subrate_min
                    = android::sysprop::bluetooth::Ble::subrate_mode_high_min_subrate()
                      .value_or(kDefaultSubrateHighModeMinSbrate);
                config.cont_num
                    = android::sysprop::bluetooth::Ble::subrate_mode_high_cont_number()
                      .value_or(kDefaultSubrateHighModeContNum);
                break;
            case GATT_SUBRATE_MODE_BALANCED:
                config.fixed_config
                    = android::sysprop::bluetooth::Ble::subrate_manager_balanced_mode_fixed_flag()
                      .value_or(kDefaultSubrateMgrBalancedModeFixedConfig);
                config.cont_num_ratio = android::sysprop::bluetooth::Ble::
                    subrate_manager_balanced_mode_cont_num_ratio()
                                            .value_or(kDefaultSubrateMgrBalancedModeRatio);
                config.cont_num_max
                    = android::sysprop::bluetooth::Ble::subrate_manager_balanced_mode_cont_num_max()
                      .value_or(0);
                config.subrate_max
                    = android::sysprop::bluetooth::Ble::subrate_mode_balanced_max_subrate()
                      .value_or(kDefaultSubrateBalancedModeMaxSubrate);
                config.subrate_min
                    = android::sysprop::bluetooth::Ble::subrate_mode_balanced_min_subrate()
                      .value_or(kDefaultSubrateBalancedModeMinSubrate);
                config.cont_num
                    = android::sysprop::bluetooth::Ble::subrate_mode_balanced_cont_number()
                      .value_or(kDefaultSubrateBalancedModeContNum);
                break;
            case GATT_SUBRATE_MODE_LOW:
                config.fixed_config
                    = android::sysprop::bluetooth::Ble::subrate_manager_low_mode_fixed_flag()
                      .value_or(kDefaultSubrateMgrLowModeFixedConfig);
                config.cont_num_ratio
                    = android::sysprop::bluetooth::Ble::subrate_manager_low_mode_cont_num_ratio()
                      .value_or(kDefaultSubrateMgrLowModeRatio);
                config.cont_num_max
                    = android::sysprop::bluetooth::Ble::subrate_manager_low_mode_cont_num_max()
                      .value_or(0);
                config.subrate_max
                    = android::sysprop::bluetooth::Ble::subrate_mode_low_max_subrate()
                      .value_or(kDefaultSubrateLowModeMaxSubrate);
                config.subrate_min
                    = android::sysprop::bluetooth::Ble::subrate_mode_low_min_subrate()
                      .value_or(kDefaultSubrateLowModeMinSubrate);
                config.cont_num
                    = android::sysprop::bluetooth::Ble::subrate_mode_low_cont_number()
                      .value_or(kDefaultSubrateLowModeContNum);
                break;
            default: // GATT_SUBRATE_MODE_OFF
                config.fixed_config = true;
                config.cont_num_ratio = 0;
                config.subrate_max = 1;
                config.subrate_min = 1;
                config.cont_num = 0;
                break;
        }
        gatt_cb.subrate_mode_config[mode] = config;
    }
}