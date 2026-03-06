/*
 * Copyright 2024 The Android Open Source Project
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

#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/btm_api_types.h"
#include "stack/include/btm_status.h"

/*******************************************************************************
 *
 * Function         BTM_SetDiscoverability
 *
 * Description      This function is called to set the device into or out of
 *                  discoverable mode. Discoverable mode means inquiry
 *                  scans are enabled.  If a value of '0' is entered for window
 *                  or interval, the default values are used.
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS if successful
 *                  tBTM_STATUS::BTM_BUSY if a setting of the filter is already in progress
 *                  tBTM_STATUS::BTM_NO_RESOURCES if couldn't get a memory pool buffer
 *                  tBTM_STATUS::BTM_ILLEGAL_VALUE if a bad parameter was detected
 *                  tBTM_STATUS::BTM_WRONG_MODE if the device is not up.
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_SetDiscoverability(uint16_t inq_mode);

/*******************************************************************************
 *
 * Function         BTM_StartInquiry
 *
 * Description      This function is called to start an inquiry.
 *
 * Parameters:      p_inqparms - pointer to the inquiry information
 *                      mode - GENERAL or LIMITED inquiry
 *                      duration - length in 1.28 sec intervals (If '0', the
 *                                 inquiry is CANCELLED)
 *                      filter_cond_type - BTM_CLR_INQUIRY_FILTER,
 *                                         BTM_FILTER_COND_DEVICE_CLASS, or
 *                                         BTM_FILTER_COND_BD_ADDR
 *                      filter_cond - value for the filter (based on
 *                                                          filter_cond_type)
 *
 *                  p_results_cb  - Pointer to the callback routine which gets
 *                                called upon receipt of an inquiry result. If
 *                                this field is NULL, the application is not
 *                                notified.
 *
 *                  p_cmpl_cb   - Pointer to the callback routine which gets
 *                                called upon completion.  If this field is
 *                                NULL, the application is not notified when
 *                                completed.
 * Returns          tBTM_STATUS
 *                  tBTM_STATUS::BTM_CMD_STARTED if successfully initiated
 *                  tBTM_STATUS::BTM_BUSY if already in progress
 *                  tBTM_STATUS::BTM_ILLEGAL_VALUE if parameter(s) are out of range
 *                  tBTM_STATUS::BTM_NO_RESOURCES if could not allocate resources to start
 *                                   the command
 *                  tBTM_STATUS::BTM_WRONG_MODE if the device is not up.
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_StartInquiry(tBTM_INQ_RESULTS_CB* p_results_cb,
                                           tBTM_INQUIRY_CMPL_CB* p_cmpl_cb);

/*******************************************************************************
 *
 * Function         BTM_IsInquiryActive
 *
 * Description      Return a bit mask of the current inquiry state
 *
 * Returns          Bitmask of current inquiry state
 *
 ******************************************************************************/
[[nodiscard]] uint16_t BTM_IsInquiryActive(void);

/*******************************************************************************
 *
 * Function         BTM_CancelInquiry
 *
 * Description      This function cancels an inquiry if active
 *
 ******************************************************************************/
void BTM_CancelInquiry(void);

/*******************************************************************************
 *
 * Function         BTM_SetConnectability
 *
 * Description      This function is called to set the device into or out of
 *                  connectable mode. Discoverable mode means page scans are
 *                  enabled.
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS if successful
 *                  tBTM_STATUS::BTM_ILLEGAL_VALUE if a bad parameter is detected
 *                  tBTM_STATUS::BTM_NO_RESOURCES if could not allocate a message buffer
 *                  tBTM_STATUS::BTM_WRONG_MODE if the device is not up.
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_SetConnectability(uint16_t page_mode);

/*******************************************************************************
 *
 * Function         BTM_SetInquiryMode
 *
 * Description      This function is called to set standard, with RSSI
 *                  mode or extended of the inquiry for local device.
 *
 * Input Params:    BTM_INQ_RESULT_STANDARD, BTM_INQ_RESULT_WITH_RSSI or
 *                  BTM_INQ_RESULT_EXTENDED
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS if successful
 *                  tBTM_STATUS::BTM_NO_RESOURCES if couldn't get a memory pool buffer
 *                  tBTM_STATUS::BTM_ILLEGAL_VALUE if a bad parameter was detected
 *                  tBTM_STATUS::BTM_WRONG_MODE if the device is not up.
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_SetInquiryMode(uint8_t mode);

/*******************************************************************************
 *
 * Function         BTM_EnableInterlacedInquiryScan
 *
 * Description      Reads system property PROPERTY_INQ_SCAN_TYPE and
 *                  enables interlaced inquiry scan with controller support.
 *
 * Input Params:    None
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_EnableInterlacedInquiryScan();

/*******************************************************************************
 *
 * Function         BTM_EnableInterlacedPageScan
 *
 * Description      Reads system property PROPERTY_PAGE_SCAN_TYPE and
 *                  enables interlaced page scan with controller support.
 *
 * Input Params:    None
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_EnableInterlacedPageScan();

void btm_inq_db_reset(void);
void btm_clr_inq_result_flt(void);
