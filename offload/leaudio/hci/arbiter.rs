// Copyright 2024, The Android Open Source Project
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

use bluetooth_offload_hci::{IsoData, Module};
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::{self, JoinHandle};

pub struct Arbiter {
    state_cvar: Arc<(Mutex<State>, Condvar)>,
    thread: Option<JoinHandle<()>>,
    max_buf_len: usize,
}

#[derive(Default)]
struct State {
    /// Halt indication of the sender thread
    halt: bool,

    /// Software transmission queues for each `Origin`.
    /// A queue is pair of connection handle, and packet raw ISO data.
    queues: [VecDeque<(u16, Vec<u8>)>; 2],

    /// Count of packets sent to the controller and not yet acknowledged,
    /// by connection handle stored on `u16`.
    in_transit: HashMap<u16, usize>,
}

enum Origin {
    Audio,
    Incoming,
}

impl Arbiter {
    pub fn new(sink: Arc<dyn Module>, max_buf_len: usize, max_buf_count: usize) -> Self {
        let state_cvar = Arc::new((Mutex::<State>::new(Default::default()), Condvar::new()));
        let thread = {
            let state_cvar = state_cvar.clone();
            thread::spawn(move || Self::thread_loop(state_cvar.clone(), sink, max_buf_count))
        };

        Self { state_cvar, thread: Some(thread), max_buf_len }
    }

    pub fn max_buf_len(&self) -> usize {
        self.max_buf_len
    }

    pub fn add_connection(&self, handle: u16) {
        let (state, _) = &*self.state_cvar;
        if state.lock().unwrap().in_transit.insert(handle, 0).is_some() {
            panic!("Connection with handle 0x{:03x} already exists", handle);
        }
    }

    pub fn remove_connection(&self, handle: u16) {
        let (state, cvar) = &*self.state_cvar;
        let mut state = state.lock().unwrap();
        for q in state.queues.iter_mut() {
            while let Some(idx) = q.iter().position(|&(h, _)| h == handle) {
                q.remove(idx);
            }
        }
        if state.in_transit.remove(&handle).is_some() {
            cvar.notify_one();
        }
    }

    pub fn push_incoming(&self, iso_data: &IsoData) {
        self.push(Origin::Incoming, iso_data);
    }

    pub fn push_audio(&self, iso_data: &IsoData) {
        self.push(Origin::Audio, iso_data);
    }

    pub fn set_completed(&self, handle: u16, num: usize) {
        let (state, cvar) = &*self.state_cvar;
        if let Some(buf_usage) = state.lock().unwrap().in_transit.get_mut(&handle) {
            *buf_usage -= num;
            cvar.notify_one();
        }
    }

    fn push(&self, origin: Origin, iso_data: &IsoData) {
        let handle = iso_data.connection_handle;
        let data = iso_data.to_bytes();
        assert!(data.len() <= self.max_buf_len + 4);

        let (state, cvar) = &*self.state_cvar;
        let mut state = state.lock().unwrap();
        if state.in_transit.contains_key(&handle) {
            state.queues[origin as usize].push_back((handle, data));
            cvar.notify_one();
        }
    }

    fn thread_loop(
        state_cvar: Arc<(Mutex<State>, Condvar)>,
        sink: Arc<dyn Module>,
        max_buf_count: usize,
    ) {
        let (state, cvar) = &*state_cvar;
        'main: loop {
            let packet = {
                let mut state = state.lock().unwrap();
                let mut packet = None;
                while !state.halt && {
                    packet = Self::pull(&mut state, max_buf_count);
                    packet.is_none()
                } {
                    state = cvar.wait(state).unwrap();
                }
                if state.halt {
                    break 'main;
                }
                packet.unwrap()
            };
            sink.out_iso(&packet);
        }
    }

    fn pull(state: &mut MutexGuard<'_, State>, max_buf_count: usize) -> Option<Vec<u8>> {
        for idx in 0..state.queues.len() {
            if state.queues[idx].is_empty() || max_buf_count <= state.in_transit.values().sum() {
                continue;
            }
            let (handle, vec) = state.queues[idx].pop_front().unwrap();
            *state.in_transit.get_mut(&handle).unwrap() += 1;
            return Some(vec);
        }
        None
    }
}

impl Drop for Arbiter {
    fn drop(&mut self) {
        let (state, cvar) = &*self.state_cvar;
        {
            let mut state = state.lock().unwrap();
            state.halt = true;
            cvar.notify_one();
        }
        let thread = self.thread.take().unwrap();
        thread.join().expect("End of thread loop");
    }
}
