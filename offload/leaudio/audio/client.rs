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
use binder::{
    BinderFeatures, DeathRecipient, ExceptionCode, IBinder, Interface, Result as BinderResult,
    Strong, Weak as BinderWeak,
};
use std::cmp::min;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

struct HciClient {
    state: Arc<Mutex<State>>,
    status: Arc<Mutex<HashMap<u16, IsoStatus>>>,
}

#[derive(Default)]
struct State {
    iso: HashMap<u16, IsoStream>,
    stream: HashMap<u16, Arc<Streamer<StreamerEvents>>>,
}

struct IsoStream {
    t0: Option<Instant>,
    iso_interval_us: u32,
    sdu_interval_us: u32,
    max_sdu_size: usize,
    clock: Option<IsoClock>,
}

#[derive(Default)]
struct IsoClock {
    state: IsoClockState,
    sn: i64,
    dt: i64,
}

enum IsoClockState {
    Probe(u8),
    Decimate(Instant),
}

impl Default for IsoClockState {
    fn default() -> Self {
        IsoClockState::Probe(0)
    }
}

#[derive(Clone)]
struct Service {
    interface: Strong<dyn IHciProxy>,
    state: Arc<Mutex<State>>,
    status: Arc<Mutex<HashMap<u16, IsoStatus>>>,
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
        panic!("Failed to connect to HCI-Proxy service")
    };

    let state = Arc::new(Mutex::new(Default::default()));
    let status = Arc::new(Mutex::new(Default::default()));
    let binder_client = BnHciProxyCallbacks::new_binder(
        HciClient { state: state.clone(), status: status.clone() },
        BinderFeatures::default(),
    );

    let mut death_recipient = DeathRecipient::new(move || {
        log::info!("HCI Proxy has died");
        *SERVICE.write().unwrap() = None;
    });
    interface.as_binder().link_to_death(&mut death_recipient).expect("Link to death");

    interface.registerCallbacks(&binder_client).expect("Registering Callbacks");

    let service = Service { interface: interface.clone(), state, status };
    *SERVICE.write().unwrap() = Some((service.clone(), death_recipient));
    service
}

impl Interface for HciClient {}

impl IHciProxyCallbacks for HciClient {
    fn startStream(&self, handle: i32, configuration: &StreamConfiguration) -> BinderResult<()> {
        let handle: u16 = handle.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let iso_interval_us: u32 =
            configuration.isoIntervalUs.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let sdu_interval_us: u32 =
            configuration.sduIntervalUs.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let max_sdu_size: usize =
            configuration.maxSduSize.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let mut state = self.state.lock().unwrap();

        if configuration.linkFeedbackSupported {
            state.iso.insert(
                handle,
                IsoStream {
                    t0: None,
                    iso_interval_us,
                    sdu_interval_us,
                    max_sdu_size,
                    clock: Some(IsoClock::default()),
                },
            );
        } else {
            let t0 = Instant::now();
            state.iso.insert(
                handle,
                IsoStream {
                    t0: Some(t0),
                    iso_interval_us,
                    sdu_interval_us,
                    max_sdu_size,
                    clock: None,
                },
            );
            if let Some(streamer) = state.stream.get(&handle) {
                streamer.enable(handle, t0, false, sdu_interval_us, max_sdu_size);
            }
        }

        Ok(())
    }

    fn stopStream(&self, handle: i32) -> BinderResult<()> {
        let handle: u16 = handle.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;

        let mut state = self.state.lock().unwrap();
        if let Some(streamer) = state.stream.get(&handle) {
            streamer.disable(handle);
        }
        state.iso.remove(&handle);

        Ok(())
    }

    fn linkFeedback(
        &self,
        handle: i32,
        sequence_number: i32,
        anchor_point_delay: i32,
        sdu_input_status: i32,
    ) -> BinderResult<()> {
        let handle: u16 = handle.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let sequence_number: u16 =
            sequence_number.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let anchor_point_delay: u16 =
            anchor_point_delay.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;
        let sdu_input_status: u16 =
            sdu_input_status.try_into().map_err(|_| ExceptionCode::ILLEGAL_ARGUMENT)?;

        let t = Instant::now() - Duration::from_micros(anchor_point_delay.into());
        let mut state = self.state.lock().unwrap();
        let streamer = state.stream.get(&handle).cloned();

        let Some(iso) = state.iso.get_mut(&handle) else {
            return Ok(());
        };

        if let Some(status) = self.status.lock().unwrap().get(&handle) {
            status.check(
                sequence_number,
                sdu_input_status,
                iso.iso_interval_us,
                iso.sdu_interval_us,
            );
        }

        let clock = iso.clock.as_mut().unwrap();
        clock.sn += sequence_number.wrapping_sub(clock.sn as u16) as i64;

        let t0 = iso.t0.unwrap_or(
            t - Duration::from_micros(sequence_number as u64 * iso.sdu_interval_us as u64),
        );
        iso.t0 = Some(t0);

        clock.dt =
            min(clock.dt, (t - t0).as_micros() as i64 - clock.sn * iso.sdu_interval_us as i64);

        clock.state = match clock.state {
            IsoClockState::Probe(n) if n < 5 => IsoClockState::Probe(n + 1),
            IsoClockState::Probe(_) => {
                assert!(clock.dt <= 0);
                let t0 = t0 - Duration::from_micros(-clock.dt as u64);
                if let Some(ref streamer) = streamer {
                    streamer.enable(handle, t0, true, iso.sdu_interval_us, iso.max_sdu_size);
                }
                iso.t0 = Some(t0);
                clock.dt = i64::MAX;
                IsoClockState::Decimate(t0)
            }
            IsoClockState::Decimate(t0) if t.duration_since(t0) < Duration::from_secs(1) => {
                IsoClockState::Decimate(t0)
            }
            IsoClockState::Decimate(t0) => {
                if let Some(ref streamer) = streamer {
                    streamer.anchor(handle, clock.sn, clock.dt);
                }
                clock.dt = i64::MAX;
                IsoClockState::Decimate(t0 + Duration::from_secs(1))
            }
        };

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
        latency: Duration,
        ccb: &CCallbacks,
    ) -> Result<Stream, String> {
        let service = get_service();
        let mut state = service.state.lock().unwrap();

        if iso_streams.iter().any(|s| state.stream.contains_key(&s.handle)) {
            return Err("ISO Stream already used".to_string());
        }

        let callbacks = StreamerEvents::new(
            *ccb,
            Strong::downgrade(&service.interface),
            service.status.clone(),
        );
        let streamer = Arc::new(Streamer::new(iso_streams, audio, latency, callbacks)?);
        for iso_stream in iso_streams {
            state.stream.insert(iso_stream.handle, streamer.clone());
        }

        for (&h, iso) in iso_streams.iter().filter_map(|e| state.iso.get_key_value(&e.handle)) {
            if let Some(t0) = iso.t0 {
                streamer.enable(h, t0, iso.clock.is_some(), iso.sdu_interval_us, iso.max_sdu_size);
            }
        }

        Ok(Stream { handles: iso_streams.iter().map(|e| e.handle).collect() })
    }

    pub fn write(&self, chunk: &[u8]) -> Result<usize, String> {
        let streamer = {
            let service = get_service();
            let state = service.state.lock().unwrap();
            state.stream.get(&self.handles[0]).unwrap().clone()
        };
        streamer.write(chunk)
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        let service = get_service();
        let mut state = service.state.lock().unwrap();
        for h in &self.handles {
            state.stream.remove(h);
        }
    }
}

struct StreamerEvents {
    ccb: Mutex<CCallbacks>,
    hci: BinderWeak<dyn IHciProxy>,
    status: Arc<Mutex<HashMap<u16, IsoStatus>>>,
}

impl StreamerEvents {
    fn new(
        ccb: CCallbacks,
        hci: BinderWeak<dyn IHciProxy>,
        status: Arc<Mutex<HashMap<u16, IsoStatus>>>,
    ) -> Self {
        Self { ccb: Mutex::new(ccb), hci, status }
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
        let Ok(hci) = self.hci.upgrade() else {
            return;
        };

        if let Err(e) = hci.sendPacket(handle.into(), sequence_number.into(), data) {
            log::error!("Cannot send packet to HCI: {e:?}");
        }

        if let Some(status) = self.status.lock().unwrap().get_mut(&handle) {
            status.update(sequence_number);
        }
    }
}

#[derive(Default)]
struct IsoStatus {
    last_sn: u16,
    history: u64,
}

impl IsoStatus {
    fn update(&mut self, sn: u16) {
        let sn_diff = sn.wrapping_sub(self.last_sn);
        self.last_sn = sn;
        self.history = 1 | if sn_diff < 64 { self.history << sn_diff } else { 0 };
    }

    fn check(&self, sn: u16, status: u16, iso_interval_us: u32, sdu_interval_us: u32) {
        let sn_diff = sn.wrapping_sub(self.last_sn) as i16;
        let history = match sn_diff {
            -63..0 => self.history >> -sn_diff,
            0..=63 => self.history << sn_diff,
            _ => 0,
        };

        for i in (0..(iso_interval_us / sdu_interval_us) as u16).rev() {
            if ((history >> i) & 1) == 1 && ((status >> i) & 1) == 0 {
                log::warn!("SDU {} late received by the controller", sn.wrapping_sub(i + 1));
            }
        }
    }
}
