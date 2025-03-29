// Copyright (C) 2025, The Android Open Source Project
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

use android_hardware_bluetooth_offload_leaudio::{aidl, binder};

use crate::ffi::{CAudioConfig, CCallbacks, CIsoStream};
use crate::streamer::{Callbacks, Streamer};
use aidl::android::hardware::bluetooth::offload::leaudio::IHciProxy::{BpHciProxy, IHciProxy};
use aidl::android::hardware::bluetooth::offload::leaudio::IHciProxyCallbacks::{
    BnHciProxyCallbacks, IHciProxyCallbacks,
};
use aidl::android::hardware::bluetooth::offload::leaudio::StreamConfiguration::StreamConfiguration;
use binder::{BinderFeatures, ExceptionCode, Interface, Result as BinderResult, Strong};
use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};

struct FfiClient {
    service: Strong<dyn IHciProxy>,
    state: Arc<Mutex<State>>,
}

struct HciClient {
    state: Arc<Mutex<State>>,
}

#[derive(Default)]
struct State {
    iso: HashMap<u16, IsoStream>,
    stream: HashMap<u16, Arc<Streamer<StreamerEvents>>>,
}

struct IsoStream {
    max_sdu_size: usize,
    sdu_interval_us: u32,
}

static CLIENT: LazyLock<FfiClient> = LazyLock::new(|| {
    let Ok(service) = binder::wait_for_interface::<dyn IHciProxy>(&format!(
        "{}/default",
        BpHciProxy::get_descriptor()
    )) else {
        panic!("Failed to connect to HCI-Proxy service")
    };

    let state = Arc::new(Mutex::new(State { ..Default::default() }));
    let binder_client = BnHciProxyCallbacks::new_binder(
        HciClient { state: state.clone() },
        BinderFeatures::default(),
    );

    service.registerCallbacks(&binder_client).expect("Registering Callbacks");
    FfiClient { service, state }
});

impl Interface for HciClient {}

impl IHciProxyCallbacks for HciClient {
    fn startStream(&self, handle: i32, configuration: &StreamConfiguration) -> BinderResult<()> {
        let handle: u16 = handle.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let max_sdu_size: usize =
            configuration.maxSduSize.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let sdu_interval_us: u32 =
            configuration.sduIntervalUs.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let mut state = self.state.lock().unwrap();

        let iso_stream = IsoStream { max_sdu_size, sdu_interval_us };
        state.iso.insert(handle, iso_stream);

        if let Some(streamer) = state.stream.get(&handle) {
            streamer.enable(handle, max_sdu_size, sdu_interval_us);
        }

        Ok(())
    }

    fn stopStream(&self, handle: i32) -> BinderResult<()> {
        let handle: u16 = handle.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let mut state = self.state.lock().unwrap();

        state.iso.remove(&handle);

        if let Some(streamer) = state.stream.get(&handle) {
            streamer.disable(handle);
        }

        Ok(())
    }
}

pub struct Stream {
    handles: Vec<u16>,
}

impl Stream {
    pub fn new(
        iso_streams: &[CIsoStream],
        audio: &CAudioConfig,
        ccb: &CCallbacks,
    ) -> Result<Stream, String> {
        let mut state = CLIENT.state.lock().unwrap();

        let callbacks = StreamerEvents::new(*ccb, CLIENT.service.clone());
        let streamer = Arc::new(Streamer::new(iso_streams, audio, callbacks)?);
        for iso_stream in iso_streams {
            state.stream.insert(iso_stream.handle, streamer.clone());
        }

        for (&h, iso) in iso_streams.iter().filter_map(|e| state.iso.get_key_value(&e.handle)) {
            streamer.enable(h, iso.max_sdu_size, iso.sdu_interval_us);
        }

        Ok(Stream { handles: iso_streams.iter().map(|e| e.handle).collect() })
    }

    pub fn write(&self, chunk: &[u8]) -> Result<usize, String> {
        let streamer = {
            let state = CLIENT.state.lock().unwrap();
            state.stream.get(&self.handles[0]).unwrap().clone()
        };
        streamer.write(chunk)
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        let mut state = CLIENT.state.lock().unwrap();
        for h in &self.handles {
            state.stream.remove(h);
        }
    }
}

struct StreamerEvents {
    ccb: Mutex<CCallbacks>,
    hci: Strong<dyn IHciProxy>,
}
impl StreamerEvents {
    fn new(ccb: CCallbacks, hci: Strong<dyn IHciProxy>) -> Self {
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

    fn send(&self, handle: u16, sequence_number: u16, data: &[u8]) {
        if let Err(e) = self.hci.sendPacket(handle.into(), sequence_number.into(), data) {
            log::error!("Cannot send packet to HCI: {:?}", e);
        }
    }
}
