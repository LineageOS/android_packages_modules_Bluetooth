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
using bluetooth::mcp::MediaState;
using bluetooth::mcp::PlayingOrder;

namespace android {
static jmethodID method_onConnectionStateChanged;
static jmethodID method_onDiscovered;
static jmethodID method_onMediaPlayerNameChanged;
static jmethodID method_onTrackChanged;
static jmethodID method_onTrackTitleChanged;
static jmethodID method_onTrackDurationChanged;
static jmethodID method_onTrackPositionChanged;
static jmethodID method_onPlaybackSpeedChanged;
static jmethodID method_onPlayingOrderChanged;
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

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
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

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onDiscovered, addr.get());
  }

  void OnMediaPlayerNameChanged(const RawAddress& address, int media_controller_id,
                                const std::string& name) override {
    log::info("addr: {}, id: {}, name: {}", address.ToRedactedStringForLogging(),
              media_controller_id, name);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    jstring j_name = sCallbackEnv->NewStringUTF(name.c_str());
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onMediaPlayerNameChanged, addr.get(),
                                 media_controller_id, j_name);
  }

  void OnTrackChanged(const RawAddress& address, int media_controller_id) override {
    log::info("addr: {}, id: {}", address.ToRedactedStringForLogging(), media_controller_id);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onTrackChanged, addr.get(),
                                 media_controller_id);
  }

  void OnTrackTitleChanged(const RawAddress& address, int media_controller_id,
                           const std::string& title) override {
    log::info("addr: {}, id: {}, title: {}", address.ToRedactedStringForLogging(),
              media_controller_id, title);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    jstring j_title = sCallbackEnv->NewStringUTF(title.c_str());
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onTrackTitleChanged, addr.get(),
                                 media_controller_id, j_title);
  }

  void OnTrackDurationChanged(const RawAddress& address, int media_controller_id,
                              int32_t duration) override {
    log::info("addr: {}, id: {}, duration: {}", address.ToRedactedStringForLogging(),
              media_controller_id, duration);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onTrackDurationChanged, addr.get(),
                                 media_controller_id, (jint)duration);
  }

  void OnTrackPositionChanged(const RawAddress& address, int media_controller_id,
                              int32_t position) override {
    log::info("addr: {}, id: {}, position: {}", address.ToRedactedStringForLogging(),
              media_controller_id, position);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onTrackPositionChanged, addr.get(),
                                 media_controller_id, (jint)position);
  }

  void OnPlaybackSpeedChanged(const RawAddress& address, int media_controller_id,
                              int8_t speed) override {
    log::info("addr: {}, id: {}, speed: {}", address.ToRedactedStringForLogging(),
              media_controller_id, speed);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onPlaybackSpeedChanged, addr.get(),
                                 media_controller_id, (jbyte)speed);
  }

  void OnPlayingOrderChanged(const RawAddress& address, int media_controller_id,
                             PlayingOrder playing_order) override {
    log::info("addr: {}, id: {}, playing_order: {}", address.ToRedactedStringForLogging(),
              media_controller_id, static_cast<int>(playing_order));

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onPlayingOrderChanged, addr.get(),
                                 media_controller_id, (jint)playing_order);
  }

  void OnPlayingOrdersSupportedChanged(const RawAddress& address, int media_controller_id,
                                       uint16_t playing_orders) override {
    log::info("addr: {}, id: {}, playing_orders: {}", address.ToRedactedStringForLogging(),
              media_controller_id, playing_orders);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onPlayingOrdersSupportedChanged, addr.get(),
                                 media_controller_id, (jint)playing_orders);
  }

  void OnSeekingSpeedChanged(const RawAddress& address, int media_controller_id,
                             int8_t speed) override {
    log::info("addr: {}, id: {}, speed: {}", address.ToRedactedStringForLogging(),
              media_controller_id, speed);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onSeekingSpeedChanged, addr.get(),
                                 media_controller_id, (jbyte)speed);
  }

  void OnMediaStateChanged(const RawAddress& address, int media_controller_id,
                           MediaState state) override {
    log::info("addr: {}, id: {}, state: {}", address.ToRedactedStringForLogging(),
              media_controller_id, static_cast<int>(state));

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onMediaStateChanged, addr.get(),
                                 media_controller_id, (jint)state);
  }

  void OnMediaControlResult(const RawAddress& address, int media_controller_id, uint8_t opcode,
                            MediaControlResultCode result) override {
    log::info("addr: {}, id: {}, opcode: {}, result: {}", address.ToRedactedStringForLogging(),
              media_controller_id, opcode, static_cast<int>(result));

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onMediaControlResult, addr.get(),
                                 media_controller_id, (jint)opcode, (jint)result);
  }

  void OnOpcodesSupportedChanged(const RawAddress& address, int media_controller_id,
                                 uint32_t opcodes) override {
    log::info("addr: {}, id: {}, opcodes: {}", address.ToRedactedStringForLogging(),
              media_controller_id, opcodes);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) {
      return;
    }

    ScopedLocalRef<jbyteArray> addr = addressToJByteArray(sCallbackEnv, address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onOpcodesSupportedChanged, addr.get(),
                                 media_controller_id, (jint)opcodes);
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

static void playNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                       jint media_controller_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->Play(bd_addr, media_controller_id);
}

static void pauseNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                        jint media_controller_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->Pause(bd_addr, media_controller_id);
}

static void stopNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                       jint media_controller_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->Stop(bd_addr, media_controller_id);
}

static void nextTrackNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                            jint media_controller_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->NextTrack(bd_addr, media_controller_id);
}

static void previousTrackNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                jint media_controller_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->PreviousTrack(bd_addr, media_controller_id);
}

static void fastRewindNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                             jint media_controller_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->FastRewind(bd_addr, media_controller_id);
}

static void fastForwardNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                              jint media_controller_id) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->FastForward(bd_addr, media_controller_id);
}

static void moveRelativeNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                               jint media_controller_id, jint offset) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->MoveRelative(bd_addr, media_controller_id, offset);
}

static void setTrackPositionNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                   jint media_controller_id, jint position) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->SetTrackPosition(bd_addr, media_controller_id, position);
}

static void setPlaybackSpeedNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                   jint media_controller_id, jbyte speed) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->SetPlaybackSpeed(bd_addr, media_controller_id, speed);
}

static void setPlayingOrderNative(JNIEnv* env, jobject /* object */, jbyteArray address,
                                  jint media_controller_id, jint playing_order) {
  log::info("");
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sMcpClientInterface) {
    log::error("sMcpClientInterface is null");
    return;
  }
  RawAddress bd_addr = addressFromJByteArray(env, address);
  sMcpClientInterface->SetPlayingOrder(bd_addr, media_controller_id,
                                       static_cast<PlayingOrder>(playing_order));
}

int register_com_android_bluetooth_mcp_client(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"initNative", "()V", reinterpret_cast<void*>(initNative)},
          {"cleanupNative", "()V", reinterpret_cast<void*>(cleanupNative)},
          {"connectNative", "([B)V", reinterpret_cast<void*>(connectNative)},
          {"disconnectNative", "([B)V", reinterpret_cast<void*>(disconnectNative)},
          {"playNative", "([BI)V", reinterpret_cast<void*>(playNative)},
          {"pauseNative", "([BI)V", reinterpret_cast<void*>(pauseNative)},
          {"stopNative", "([BI)V", reinterpret_cast<void*>(stopNative)},
          {"nextTrackNative", "([BI)V", reinterpret_cast<void*>(nextTrackNative)},
          {"previousTrackNative", "([BI)V", reinterpret_cast<void*>(previousTrackNative)},
          {"fastRewindNative", "([BI)V", reinterpret_cast<void*>(fastRewindNative)},
          {"fastForwardNative", "([BI)V", reinterpret_cast<void*>(fastForwardNative)},
          {"moveRelativeNative", "([BII)V", reinterpret_cast<void*>(moveRelativeNative)},
          {"setTrackPositionNative", "([BII)V", reinterpret_cast<void*>(setTrackPositionNative)},
          {"setPlaybackSpeedNative", "([BIB)V", reinterpret_cast<void*>(setPlaybackSpeedNative)},
          {"setPlayingOrderNative", "([BII)V", reinterpret_cast<void*>(setPlayingOrderNative)},
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
          {"onMediaPlayerNameChanged", "([BILjava/lang/String;)V",
           &method_onMediaPlayerNameChanged},
          {"onTrackChanged", "([BI)V", &method_onTrackChanged},
          {"onTrackTitleChanged", "([BILjava/lang/String;)V", &method_onTrackTitleChanged},
          {"onTrackDurationChanged", "([BII)V", &method_onTrackDurationChanged},
          {"onTrackPositionChanged", "([BII)V", &method_onTrackPositionChanged},
          {"onPlaybackSpeedChanged", "([BIB)V", &method_onPlaybackSpeedChanged},
          {"onPlayingOrderChanged", "([BII)V", &method_onPlayingOrderChanged},
          {"onPlayingOrdersSupportedChanged", "([BII)V", &method_onPlayingOrdersSupportedChanged},
          {"onSeekingSpeedChanged", "([BIB)V", &method_onSeekingSpeedChanged},
          {"onMediaStateChanged", "([BII)V", &method_onMediaStateChanged},
          {"onMediaControlResult", "([BIII)V", &method_onMediaControlResult},
          {"onOpcodesSupportedChanged", "([BII)V", &method_onOpcodesSupportedChanged},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/mcp/McpClientNativeCallback", javaMethods);

  return 0;
}
}  // namespace android
