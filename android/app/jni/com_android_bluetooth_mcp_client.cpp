/*
 * Copyright (C) 2026 The Android Open Source Project
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

#define LOG_TAG "BluetoothMcpClientServiceJni"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_local_ref.h>

#include <cstdint>
#include <mutex>
#include <shared_mutex>
#include <string>

#include "com_android_bluetooth.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_mcp_client.h"

using bluetooth::mcp::ConnectionState;
using bluetooth::mcp::McpClientCallbacks;
using bluetooth::mcp::McpClientInterface;
using bluetooth::mcp::MediaControlResultCode;

namespace android {
static jmethodID method_onConnectionStateChanged;
static jmethodID method_onDiscovered;
static jmethodID method_onMediaPlayerNameChanged;
static jmethodID method_onTrackChanged;
static jmethodID method_onTrackTitleChanged;
static jmethodID method_onTrackDurationChanged;
static jmethodID method_onTrackPositionChanged;
static jmethodID method_onPlaybackSpeedChanged;
static jmethodID method_onPlayingOrdersSupportedChanged;
static jmethodID method_onSeekingSpeedChanged;
static jmethodID method_onMediaStateChanged;
static jmethodID method_onMediaControlResult;
static jmethodID method_onOpcodesSupportedChanged;

static McpClientInterface* sMcpClientInterface = nullptr;
static std::shared_timed_mutex interface_mutex;

static jobject mCallbacksObj = nullptr;
static std::shared_timed_mutex callbacks_mutex;

static jfieldID sCallbacksField;

class McpClientCallbacksImpl : public McpClientCallbacks {
public:
  ~McpClientCallbacksImpl() = default;

  void OnConnectionState(const RawAddress& address, ConnectionState state) override {
    log::info("state:{}, addr: {}", static_cast<int>(state), address.ToRedactedStringForLogging());

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectionStateChanged, addr.get(),
                                 (jint)state);
  }

  void OnDiscovered(const RawAddress& address) override {
    log::info("addr: {}", address.ToRedactedStringForLogging());

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onDiscovered, addr.get());
  }

  void OnMediaPlayerNameChanged(const RawAddress& address, const std::string& name) override {
    log::info("addr: {}, name: {}", address.ToRedactedStringForLogging(), name);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    jstring j_name = sCallbackEnv->NewStringUTF(name.c_str());
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onMediaPlayerNameChanged, addr.get(),
                                 j_name);
  }

  void OnTrackChanged(const RawAddress& address) override {
    log::info("addr: {}", address.ToRedactedStringForLogging());

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onTrackChanged, addr.get());
  }

  void OnTrackTitleChanged(const RawAddress& address, const std::string& title) override {
    log::info("addr: {}, title: {}", address.ToRedactedStringForLogging(), title);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    jstring j_title = sCallbackEnv->NewStringUTF(title.c_str());
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onTrackTitleChanged, addr.get(), j_title);
  }

  void OnTrackDurationChanged(const RawAddress& address, int32_t duration) override {
    log::info("addr: {}, duration: {}", address.ToRedactedStringForLogging(), duration);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onTrackDurationChanged, addr.get(),
                                 (jint)duration);
  }

  void OnTrackPositionChanged(const RawAddress& address, int32_t position) override {
    log::info("addr: {}, position: {}", address.ToRedactedStringForLogging(), position);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onTrackPositionChanged, addr.get(),
                                 (jint)position);
  }

  void OnPlaybackSpeedChanged(const RawAddress& address, int8_t speed) override {
    log::info("addr: {}, speed: {}", address.ToRedactedStringForLogging(), speed);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onPlaybackSpeedChanged, addr.get(),
                                 (jbyte)speed);
  }

  void OnPlayingOrdersSupportedChanged(const RawAddress& address,
                                       uint16_t playing_orders) override {
    log::info("addr: {}, playing_orders: {}", address.ToRedactedStringForLogging(), playing_orders);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onPlayingOrdersSupportedChanged, addr.get(),
                                 (jint)playing_orders);
  }

  void OnSeekingSpeedChanged(const RawAddress& address, int8_t speed) override {
    log::info("addr: {}, speed: {}", address.ToRedactedStringForLogging(), speed);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onSeekingSpeedChanged, addr.get(),
                                 (jbyte)speed);
  }

  void OnMediaStateChanged(const RawAddress& address, uint8_t state) override {
    log::info("addr: {}, state: {}", address.ToRedactedStringForLogging(), state);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onMediaStateChanged, addr.get(),
                                 (jint)state);
  }

  void OnMediaControlResult(const RawAddress& address, uint8_t opcode,
                            MediaControlResultCode result) override {
    log::info("addr: {}, opcode: {}, result: {}", address.ToRedactedStringForLogging(), opcode,
              static_cast<int>(result));

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onMediaControlResult, addr.get(),
                                 (jint)opcode, (jint)result);
  }

  void OnOpcodesSupportedChanged(const RawAddress& address, uint32_t opcodes) override {
    log::info("addr: {}, opcodes: {}", address.ToRedactedStringForLogging(), opcodes);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv.get(), address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onOpcodesSupportedChanged, addr.get(),
                                 (jint)opcodes);
  }
};

static McpClientCallbacksImpl sMcpClientCallbacks;

static void initNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sMcpClientInterface != nullptr) {
    log::info("Cleaning up McpClient Interface before initializing...");
    sMcpClientInterface->Cleanup();
    sMcpClientInterface = nullptr;
  }

  if (mCallbacksObj != nullptr) {
    log::info("Cleaning up McpClient callback object");
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }

  if ((mCallbacksObj = env->NewGlobalRef(env->GetObjectField(object, sCallbacksField))) ==
      nullptr) {
    log::fatal("Failed to allocate Global Ref for McpClient Callbacks");
  }

  sMcpClientInterface = const_cast<McpClientInterface*>(reinterpret_cast<const McpClientInterface*>(
          btInf->get_profile_interface(BT_PROFILE_MCP_CLIENT_ID)));

  if (sMcpClientInterface == nullptr) {
    log::error("Failed to get Bluetooth McpClient Interface");
    return;
  }

  sMcpClientInterface->Init(&sMcpClientCallbacks);
}

static void cleanupNative(JNIEnv* env, jobject /* object */) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    log::error("Bluetooth module is not loaded");
    return;
  }

  if (sMcpClientInterface != nullptr) {
    sMcpClientInterface->Cleanup();
    sMcpClientInterface = nullptr;
  }

  if (mCallbacksObj != nullptr) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }
}

static void connectNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->Connect(bd_addr);
}

static void disconnectNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->Disconnect(bd_addr);
}

static void playNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->Play(bd_addr);
}

static void pauseNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->Pause(bd_addr);
}

static void stopNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->Stop(bd_addr);
}

static void nextTrackNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->NextTrack(bd_addr);
}

static void previousTrackNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->PreviousTrack(bd_addr);
}

static void fastRewindNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->FastRewind(bd_addr);
}

static void fastForwardNative(JNIEnv* env, jobject /* object */, jbyteArray address) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->FastForward(bd_addr);
}

static void moveRelativeNative(JNIEnv* env, jobject /* object */, jbyteArray address, jint offset) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->MoveRelative(bd_addr, offset);
}

static void setTrackPositionNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                   jint position) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->SetTrackPosition(bd_addr, position);
}

int register_com_android_bluetooth_mcp_client(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initNative", "()V", reinterpret_cast<void*>(initNative)},
          {"cleanupNative", "()V", reinterpret_cast<void*>(cleanupNative)},
          {"connectNative", "([B)V", reinterpret_cast<void*>(connectNative)},
          {"disconnectNative", "([B)V", reinterpret_cast<void*>(disconnectNative)},
          {"playNative", "([B)V", reinterpret_cast<void*>(playNative)},
          {"pauseNative", "([B)V", reinterpret_cast<void*>(pauseNative)},
          {"stopNative", "([B)V", reinterpret_cast<void*>(stopNative)},
          {"nextTrackNative", "([B)V", reinterpret_cast<void*>(nextTrackNative)},
          {"previousTrackNative", "([B)V", reinterpret_cast<void*>(previousTrackNative)},
          {"fastRewindNative", "([B)V", reinterpret_cast<void*>(fastRewindNative)},
          {"fastForwardNative", "([B)V", reinterpret_cast<void*>(fastForwardNative)},
          {"moveRelativeNative", "([BI)V", reinterpret_cast<void*>(moveRelativeNative)},
          {"setTrackPositionNative", "([BI)V", reinterpret_cast<void*>(setTrackPositionNative)},
  };
  const char* jniNativeInterfaceClass = "com/android/bluetooth/mcp/McpClientNativeInterface";
  const int result = REGISTER_NATIVE_METHODS(env, jniNativeInterfaceClass, methods);
  if (result != 0) {
    return result;
  }

  sCallbacksField = getNativeCallbackField(env, jniNativeInterfaceClass);

  const JNIJavaMethod javaMethods[] = {
          {"onConnectionStateChanged", "([BI)V", &method_onConnectionStateChanged},
          {"onDiscovered", "([B)V", &method_onDiscovered},
          {"onMediaPlayerNameChanged", "([BLjava/lang/String;)V", &method_onMediaPlayerNameChanged},
          {"onTrackChanged", "([B)V", &method_onTrackChanged},
          {"onTrackTitleChanged", "([BLjava/lang/String;)V", &method_onTrackTitleChanged},
          {"onTrackDurationChanged", "([BI)V", &method_onTrackDurationChanged},
          {"onTrackPositionChanged", "([BI)V", &method_onTrackPositionChanged},
          {"onPlaybackSpeedChanged", "([BB)V", &method_onPlaybackSpeedChanged},
          {"onPlayingOrdersSupportedChanged", "([BI)V", &method_onPlayingOrdersSupportedChanged},
          {"onSeekingSpeedChanged", "([BB)V", &method_onSeekingSpeedChanged},
          {"onMediaStateChanged", "([BI)V", &method_onMediaStateChanged},
          {"onMediaControlResult", "([BII)V", &method_onMediaControlResult},
          {"onOpcodesSupportedChanged", "([BI)V", &method_onOpcodesSupportedChanged},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/mcp/McpClientNativeCallback", javaMethods);

  return 0;
}
}  // namespace android
