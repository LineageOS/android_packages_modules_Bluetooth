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

#define LOG_TAG "bluetooth-jni"

#include "com_android_bluetooth.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_local_ref.h>

namespace android {

RawAddress addressFromJByteArray(JNIEnv* env, jbyteArray object) {
  jbyte* address_bytes = env->GetByteArrayElements(object, nullptr);
  log::assert_that(address_bytes != nullptr, "null byte address");
  RawAddress address = RawAddress::FromOctets(reinterpret_cast<const uint8_t*>(address_bytes));
  env->ReleaseByteArrayElements(object, address_bytes, 0);
  return address;
}

std::optional<RawAddress> addressFromNullableJByteArray(JNIEnv* env, jbyteArray object) {
  jbyte* address_bytes = env->GetByteArrayElements(object, nullptr);
  std::optional<RawAddress> address =
          address_bytes ? std::optional<RawAddress>(RawAddress::FromOctets(
                                  reinterpret_cast<const uint8_t*>(address_bytes)))
                        : std::nullopt;
  env->ReleaseByteArrayElements(object, address_bytes, 0);
  return address;
}

}  // namespace android
