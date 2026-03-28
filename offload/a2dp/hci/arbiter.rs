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

use bluetooth_offload_hci::{AclData, Module};
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::{self, JoinHandle};

const ACL_HEADER_SIZE: usize = 4;

pub struct Arbiter {
    state_cvar: Arc<(Mutex<State>, Condvar)>,
    thread: Option<JoinHandle<()>>,
    acl_data_packet_length: usize,
}

#[derive(Default)]
struct State {
    /// Halt indication of the sender thread
    halt: bool,

    /// Software transmission queue for data packets.
    queue: VecDeque<QueuedPacket>,

    /// Queue of origins for packets sent to the controller and not yet acknowledged,
    /// by connection handle.
    in_transit: HashMap<u16, VecDeque<Origin>>,
}

/// Source of the packet.
#[derive(Clone, Copy, PartialEq)]
enum Origin {
    /// Source of A2DP frames.
    Audio,
    /// Source of other stack frames.
    Incoming,
}

#[derive(Clone, PartialEq)]
/// Packet to be sent to the controller.
struct QueuedPacket {
    /// Source of the packet.
    origin: Origin,
    /// Connection handle.
    handle: u16,
    /// Data to be sent.
    data: Vec<u8>,
}

impl Arbiter {
    pub fn new(
        sink: Arc<dyn Module>,
        acl_data_packet_length: usize,
        total_num_acl_data_packets: usize,
    ) -> Self {
        log::debug!(
            "Arbiter::new: acl_data_packet_length: {:?}, total_num_acl_data_packets: {:?}",
            acl_data_packet_length,
            total_num_acl_data_packets
        );
        let state_cvar = Arc::new((Mutex::<State>::new(Default::default()), Condvar::new()));
        let thread = {
            let state_cvar = state_cvar.clone();
            thread::spawn(move || {
                Self::thread_loop(state_cvar.clone(), sink, total_num_acl_data_packets)
            })
        };

        Self { state_cvar, thread: Some(thread), acl_data_packet_length }
    }

    pub fn acl_data_packet_length(&self) -> usize {
        self.acl_data_packet_length
    }

    pub fn add_connection(&self, handle: u16) {
        log::debug!("Arbiter::add_connection: handle: {:?}", handle);
        let (state, _) = &*self.state_cvar;
        state.lock().unwrap().in_transit.entry(handle).or_default();
    }

    pub fn remove_connection(&self, handle: u16) -> u16 {
        log::debug!("Arbiter::remove_connection: handle: {:?}", handle);
        let (state, cvar) = &*self.state_cvar;
        let mut state = state.lock().unwrap();
        let mut removed: u16 = 0;

        state.queue.retain(|packet| {
            removed += (packet.origin == Origin::Incoming && packet.handle == handle) as u16;
            packet.handle != handle
        });
        if state.in_transit.remove(&handle).is_some() {
            cvar.notify_one();
        }
        removed
    }

    pub fn push_incoming(&self, data: &AclData) {
        self.push(Origin::Incoming, data);
    }

    pub fn push_audio(&self, data: &AclData) {
        self.push(Origin::Audio, data);
    }

    fn push(&self, origin: Origin, data: &AclData) {
        let handle = data.connection_handle;
        let data = data.to_bytes();
        assert!(data.len() <= self.acl_data_packet_length + ACL_HEADER_SIZE);

        let (state, cvar) = &*self.state_cvar;
        let mut state = state.lock().unwrap();

        // Get or insert a queue for the handle.
        state.in_transit.entry(handle).or_default();

        log::debug!(
            "Arbiter::push: Adding to queue handle: {:?}, data len: {:?}",
            handle,
            data.len()
        );
        state.queue.push_back(QueuedPacket { origin, handle, data });
        cvar.notify_one();
    }

    pub fn set_completed(&self, handle: u16, num: usize) -> usize {
        let (state, cvar) = &*self.state_cvar;
        let mut state = state.lock().unwrap();

        let Some(queue) = state.in_transit.get_mut(&handle) else {
            log::error!(
                "Arbiter::set_completed: Received credits for unknown connection handle {}",
                handle
            );
            return num;
        };

        let mut excess_packets = 0;
        let count = if num > queue.len() {
            log::error!(
                "Arbiter::set_completed: More completed packets than sent reported {} / {}",
                num,
                queue.len()
            );
            excess_packets = num - queue.len();
            queue.len()
        } else {
            num
        };

        let stack_completed = queue.drain(0..count).filter(|o| *o == Origin::Incoming).count();

        log::debug!("Arbiter::set_completed: Setting packet completed, stack: {}", stack_completed);
        cvar.notify_one();

        stack_completed + excess_packets
    }

    fn thread_loop(
        state_cvar: Arc<(Mutex<State>, Condvar)>,
        sink: Arc<dyn Module>,
        total_num_acl_data_packets: usize,
    ) {
        let (state, cvar) = &*state_cvar;
        'main: loop {
            let packet = {
                let mut state = state.lock().unwrap();
                let mut packet = None;
                while !state.halt && {
                    log::debug!("Arbiter::thread_loop: Pulling packet");
                    packet = Self::pull(&mut state, total_num_acl_data_packets);
                    packet.is_none()
                } {
                    log::debug!("Arbiter::thread_loop: Waiting on cvar lock");
                    state = cvar.wait(state).unwrap();
                }
                if state.halt {
                    log::debug!("Arbiter::thread_loop: State halt");
                    break 'main;
                }
                packet.unwrap()
            };
            log::trace!("Arbiter::thread_loop: Sending packet: {:02X?}", packet);
            sink.out_acl(&packet);
        }
    }

    fn pull(
        state: &mut MutexGuard<'_, State>,
        total_num_acl_data_packets: usize,
    ) -> Option<Vec<u8>> {
        let in_transit_count: usize = state.in_transit.values().map(VecDeque::len).sum();
        if in_transit_count >= total_num_acl_data_packets || state.queue.is_empty() {
            return None;
        }
        let packet = state.queue.pop_front().unwrap();
        state.in_transit.get_mut(&packet.handle).unwrap().push_back(packet.origin);
        Some(packet.data)
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
