/******************************************************************************
 *
 *  Copyright 2024 The Android Open Source Project
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
#ifndef SYSTEM_STACK_INCLUDE_AIS_API_H_
#define SYSTEM_STACK_INCLUDE_AIS_API_H_

#include <bluetooth/types/uuid.h>

#define ANDROID_INFORMATION_SERVICE_UUID_STRING "e73e0001-ef1b-4e74-8291-2e4f3164f3b5"
/* Android Information Service characteristic */
#define GATT_UUID_AIS_API_LEVEL_STRING "e73e0002-ef1b-4e74-8291-2e4f3164f3b5"

extern constinit bluetooth::Uuid ANDROID_INFORMATION_SERVICE_UUID;
extern constinit bluetooth::Uuid GATT_UUID_AIS_API_LEVEL;

/*******************************************************************************
 *
 * Function         AIS_Init
 *
 * Description      Initializes the control blocks used by AIS.
 *                  This routine should not be called except once per
 *                      stack invocation.
 *
 * Returns          Nothing
 *
 ******************************************************************************/
void AIS_Init(void);

#endif  // SYSTEM_STACK_INCLUDE_AIS_API_H_
