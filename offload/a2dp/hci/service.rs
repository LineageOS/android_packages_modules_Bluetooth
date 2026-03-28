// Copyright (C) 2026, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use android_hardware_bluetooth_offload_a2dp::{aidl, binder};

use crate::arbiter::Arbiter;
use aidl::android::hardware::bluetooth::offload::a2dp::IHciProxy::IHciProxy;
#[cfg(not(test))]
use aidl::android::hardware::bluetooth::offload::a2dp::IHciProxy::{BnHciProxy, BpHciProxy};
use aidl::android::hardware::bluetooth::offload::a2dp::IHciProxyCallbacks::IHciProxyCallbacks;
#[cfg(not(test))]
use binder::BinderFeatures;
use binder::{ExceptionCode, Interface, Result as BinderResult, Strong};
use bluetooth_offload_hci::{AclData, AclType};
use std::sync::{Arc, LazyLock, Mutex, Weak};

pub(crate) use aidl::android::hardware::bluetooth::offload::a2dp::StreamConfiguration::StreamConfiguration;

pub(crate) struct Service {
    state: Arc<Mutex<State>>,
}

static SERVICE: LazyLock<Service> = LazyLock::new(|| {
    let state = Arc::new(Mutex::new(State::default()));
    HciProxy::register(state.clone());
    Service { state }
});

#[derive(Default)]
struct State {
    arbiter: Weak<Arbiter>,
    stream: Option<(u16, StreamConfiguration)>,
    callbacks: Option<Strong<dyn IHciProxyCallbacks>>,
}

impl Service {
    pub(crate) fn register() {
        log::debug!("Service::register");
        LazyLock::force(&SERVICE);
    }

    pub(crate) fn reset() {
        log::debug!("Service::reset");
        let mut state = SERVICE.state.lock().unwrap();
        if let Some(callbacks) = &state.callbacks {
            if let Some((handle, _)) = state.stream {
                let _ = callbacks.stopStream(handle.into());
            }
        }
        state.stream = None;
    }

    pub(crate) fn set_arbiter(arbiter: Weak<Arbiter>) {
        log::debug!("Service::set_arbiter");
        let mut state = SERVICE.state.lock().unwrap();
        state.arbiter = arbiter;
    }

    pub(crate) fn start_stream(handle: u16, config: StreamConfiguration) -> Result<(), String> {
        log::debug!("Service::start_stream: handle: {:?}, config: {:?}", handle, config);
        let mut state = SERVICE.state.lock().unwrap();

        if let Some(callbacks) = &state.callbacks {
            callbacks.startStream(handle.into(), &config).map_err(|e| format!("{:?}", e))?;
            state.stream = Some((handle, config));
            Ok(())
        } else {
            #[cfg(test)]
            {
                log::info!("Service::start_stream: No callbacks registered (test mode)");
                state.stream = Some((handle, config));
                Ok(())
            }
            #[cfg(not(test))]
            Err("Stream started without registered client".to_string())
        }
    }

    pub(crate) fn stop_stream(handle: u16) -> Result<(), String> {
        log::debug!("Service::stop_stream: handle: {:?}", handle);
        let mut state = SERVICE.state.lock().unwrap();

        if let Some((current_handle, _)) = state.stream {
            if current_handle == handle {
                state.stream = None;
            }
        }

        if let Some(callbacks) = &state.callbacks {
            callbacks.stopStream(handle.into()).map_err(|e| format!("{:?}", e))
        } else {
            #[cfg(test)]
            return Ok(());
            #[cfg(not(test))]
            Err("Stream stopped without registered client".to_string())
        }
    }
}

struct HciProxy {
    state: Arc<Mutex<State>>,
}

impl Interface for HciProxy {}

impl HciProxy {
    fn register(_state: Arc<Mutex<State>>) {
        #[cfg(not(test))]
        binder::add_service(
            &format!("{}/default", BpHciProxy::get_descriptor()),
            BnHciProxy::new_binder(Self { state: _state }, BinderFeatures::default()).as_binder(),
        )
        .expect("Failed to register service");
    }
}

impl IHciProxy for HciProxy {
    fn registerCallbacks(&self, callbacks: &Strong<dyn IHciProxyCallbacks>) -> BinderResult<()> {
        let mut state = self.state.lock().unwrap();
        if state.callbacks.is_some() {
            log::error!("HciProxy::registerCallbacks: callbacks already registered");
            return Err(ExceptionCode::ILLEGAL_STATE.into());
        }
        state.callbacks = Some(callbacks.clone());
        Ok(())
    }

    fn sendPacket(&self, handle: i32, data: &[u8]) -> BinderResult<()> {
        let handle: u16 = handle.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        log::debug!("HciProxy::sendPacket: handle: {:?}", handle);
        let state = self.state.lock().unwrap();
        let is_valid_stream = state.stream.as_ref().is_some_and(|(h, _)| *h == handle);
        if let (Some(arbiter), true) = (state.arbiter.upgrade(), is_valid_stream) {
            assert!(
                data.len() <= arbiter.acl_data_packet_length(),
                "HciProxy::sendPacket: SDU Fragmentation over HCI is not supported"
            );
            arbiter.push_audio(&AclData::new(
                handle,
                AclType::First { is_flushable: true, is_broadcast: false },
                data,
            ));
        } else {
            log::warn!("HciProxy::sendPacket: Trashing packet received in bad state");
        }

        Ok(())
    }
}
