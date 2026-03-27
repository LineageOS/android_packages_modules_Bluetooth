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

use crate::ffi::{CAudioConfig, CCallbacks};
use crate::streamer::{Callbacks, Streamer};
use aidl::android::hardware::bluetooth::offload::a2dp::IHciProxy::{BpHciProxy, IHciProxy};
use aidl::android::hardware::bluetooth::offload::a2dp::IHciProxyCallbacks::{
    BnHciProxyCallbacks, IHciProxyCallbacks,
};
use aidl::android::hardware::bluetooth::offload::a2dp::StreamConfiguration::StreamConfiguration;
use binder::{
    BinderFeatures, DeathRecipient, ExceptionCode, IBinder, Interface, Result as BinderResult,
    Strong, Weak as BinderWeak,
};
use std::sync::{Arc, Mutex, RwLock};

struct HciClient {
    state: Arc<Mutex<State>>,
}

#[derive(Default)]
struct State {
    streamer: Option<Arc<Streamer<StreamerEvents>>>,
    active_handle: Option<u16>,
}

#[derive(Clone)]
struct Service {
    interface: Strong<dyn IHciProxy>,
    state: Arc<Mutex<State>>,
}

static SERVICE: RwLock<Option<(Service, DeathRecipient)>> = RwLock::new(None);

fn get_service() -> Service {
    if let Some((s, _)) = &*SERVICE.read().unwrap() {
        return s.clone();
    }

    let Ok(interface) = binder::wait_for_interface::<dyn IHciProxy>(&format!(
        "{}/default",
        BpHciProxy::get_descriptor()
    )) else {
        panic!("Failed to connect to A2DP Offload service")
    };

    let state = Arc::new(Mutex::new(Default::default()));
    let binder_client = BnHciProxyCallbacks::new_binder(
        HciClient { state: state.clone() },
        BinderFeatures::default(),
    );

    let mut death_recipient = DeathRecipient::new(move || {
        log::info!("get_service: A2DP Software Offload service has died");
        *SERVICE.write().unwrap() = None;
    });
    interface.as_binder().link_to_death(&mut death_recipient).expect("Link to death");

    interface.registerCallbacks(&binder_client).expect("Registering Callbacks");

    let service = Service { interface: interface.clone(), state };
    *SERVICE.write().unwrap() = Some((service.clone(), death_recipient));
    service
}

impl Interface for HciClient {}

impl IHciProxyCallbacks for HciClient {
    fn startStream(&self, handle: i32, configuration: &StreamConfiguration) -> BinderResult<()> {
        let handle: u16 = handle.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let l2cap_channel_id: u16 =
            configuration.l2capChannelId.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let peer_mtu: u16 =
            configuration.peerMtu.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;

        let mut state = self.state.lock().unwrap();
        if state.active_handle.is_some() {
            log::warn!("HciClient::startStream: A stream is already active, ignoring startStream for handle {}", handle);
            return Err(ExceptionCode::ILLEGAL_STATE.into());
        }

        if let Some(streamer) = &state.streamer {
            streamer.enable(handle, l2cap_channel_id, peer_mtu);
            state.active_handle = Some(handle);
        } else {
            log::error!("HciClient::startStream: startStream called but no streamer is configured");
            return Err(ExceptionCode::ILLEGAL_STATE.into());
        }

        Ok(())
    }

    fn stopStream(&self, handle: i32) -> BinderResult<()> {
        let handle: u16 = handle.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;

        let mut state = self.state.lock().unwrap();
        if state.active_handle != Some(handle) {
            log::warn!(
                "HciClient::stopStream: stopStream called for handle {} which is not the active handle ({:?})",
                handle,
                state.active_handle
            );
            return Err(ExceptionCode::ILLEGAL_STATE.into());
        }

        if let Some(streamer) = &state.streamer {
            streamer.disable();
        }
        state.active_handle = None;

        Ok(())
    }
}

/// Sets up the single audio streamer instance.
///
/// SAFETY: The caller must ensure that `audio` points to a valid `CAudioConfig` where
/// the union field matches the type tag.
pub unsafe fn setup(audio: &CAudioConfig, ccb: &CCallbacks) -> Result<(), String> {
    log::debug!("setup: {:?}", audio);

    let service = get_service();
    let mut state = service.state.lock().unwrap();

    if state.streamer.is_some() {
        log::warn!("Streamer already configured, overwriting.");
    }

    let callbacks = StreamerEvents::new(*ccb, Strong::downgrade(&service.interface));

    // SAFETY: We are propagating the safety guarantee from the caller of this function.
    let streamer = Arc::new(unsafe { Streamer::new(audio, callbacks) }?);
    state.streamer = Some(streamer);

    Ok(())
}

/// Tears down and cleans up the single audio streamer.
pub fn teardown() {
    log::debug!("teardown");
    let service = get_service();
    let mut state = service.state.lock().unwrap();
    if let Some(streamer) = state.streamer.take() {
        streamer.disable();
    }
    state.active_handle = None;
}

/// Writes PCM data to the active stream.
pub fn write(chunk: &[u8]) -> Result<usize, String> {
    let service = get_service();
    let state = service.state.lock().unwrap();

    if let Some(streamer) = &state.streamer {
        if state.active_handle.is_none() {
            return Err("A2DP stream is not active".to_string());
        }
        streamer.write(chunk)
    } else {
        Err("A2DP streamer is not configured".to_string())
    }
}

struct StreamerEvents {
    ccb: Mutex<CCallbacks>,
    hci: BinderWeak<dyn IHciProxy>,
}

impl StreamerEvents {
    fn new(ccb: CCallbacks, hci: BinderWeak<dyn IHciProxy>) -> Self {
        Self { ccb: Mutex::new(ccb), hci }
    }
}

impl Callbacks for StreamerEvents {
    fn start(&self) {
        self.ccb.lock().unwrap().start();
    }

    fn stop(&self) {
        self.ccb.lock().unwrap().stop();
    }

    fn send(&self, handle: u16, data: &[u8]) {
        let Ok(hci) = self.hci.upgrade() else {
            log::error!("StreamerEvents::send: HCI service is gone, cannot send packet.");
            return;
        };
        if let Err(e) = hci.sendPacket(handle.into(), data) {
            log::error!("StreamerEvents::send: Cannot send packet to HCI: {:?}", e);
        }
    }
}
