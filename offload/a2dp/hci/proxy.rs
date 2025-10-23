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

use bluetooth_offload_hci::{
    self as hci, A2dpHardwareOffloadComplete, LeGetVendorCapabilitiesComplete,
};

use crate::arbiter::Arbiter;
use crate::service::{Service, StreamConfiguration};
use hci::{AclData, Command, Event, EventToBytes, Module, ModuleBuilder, ReturnParameters, Status};
use std::sync::{Arc, Mutex};

const RTP_HEADER_SIZE: u16 = 12;
const LINK_TYPE_ACL: u8 = 0x01;

/// A2DP HCI-Proxy module builder
pub struct A2dpModuleBuilder {}

pub(crate) struct A2dpModule {
    next_module: Arc<dyn Module>,
    state: Mutex<State>,
}

#[derive(Default)]
pub(crate) struct State {
    stream_conn_handle: Option<u16>,
    connection_handles: Vec<u16>,
    pub(crate) arbiter: Option<Arc<Arbiter>>,
}

impl ModuleBuilder for A2dpModuleBuilder {
    /// Build the HCI-Proxy module from the next module in the chain
    fn build(&self, next_module: Arc<dyn Module>) -> Arc<dyn Module> {
        Service::register();
        Arc::new(A2dpModule::new(next_module))
    }
}

impl A2dpModule {
    pub(crate) fn new(next_module: Arc<dyn Module>) -> Self {
        Self { next_module, state: Mutex::new(Default::default()) }
    }

    #[cfg(test)]
    pub fn get_state(&self) -> &Mutex<State> {
        &self.state
    }

    fn start_a2dp_offload(&self, state: &mut State, start: &hci::StartA2dpOffload) -> Status {
        log::debug!(
            "A2dpModule::start_a2dp_offload: Adding stream handle: {:?}",
            start.connection_handle
        );

        if state.stream_conn_handle.is_some() {
            log::error!("A2dpModule::start_a2dp_offload: Stream handle already added");
            return Status::CommandDisallowed;
        }

        state.stream_conn_handle = Some(start.connection_handle);

        // Android Bluetooth stack subtracts RTP header size from the L2CAP peer MTU
        // Let's account for that and provide encoder with real value.
        let l2cap_mtu = start.peer_mtu + RTP_HEADER_SIZE;

        if let Err(e) = Service::start_stream(
            start.connection_handle,
            StreamConfiguration {
                connectionHandle: start.connection_handle as i32,
                l2capChannelId: start.l2cap_channel_id as i32,
                dataPathDirection: start.data_path_direction as i8,
                peerMtu: l2cap_mtu as i32,
                cpEnableScmsT: start.cp_enable_scms_t as i8,
                cpHeaderScmsT: start.cp_header_scms_t as i8,
                VendorSpecificParameters: start.vendor_specific_parameters.clone(),
            },
        ) {
            log::error!("A2dpModule::start_a2dp_offload: Failed to start stream: {}", e);
            state.stream_conn_handle = None;
            return Status::UnspecifiedError;
        }

        Status::Success
    }

    fn stop_a2dp_offload(&self, state: &mut State, stop: &hci::StopA2dpOffload) -> Status {
        log::debug!(
            "A2dpModule::stop_a2dp_offload: Removing stream handle: {:?}",
            stop.connection_handle
        );

        if state.stream_conn_handle.is_none() {
            log::error!("A2dpModule::stop_a2dp_offload: Stream handle not added");
            return Status::CommandDisallowed;
        }

        if state.stream_conn_handle != Some(stop.connection_handle) {
            log::error!(
                "A2dpModule::stop_a2dp_offload: Invalid connection handle: {:?}",
                stop.connection_handle
            );
            return Status::CommandDisallowed;
        }

        if let Err(e) = Service::stop_stream(stop.connection_handle) {
            log::error!("A2dpModule::stop_a2dp_offload: Failed to stop stream: {}", e);
            return Status::UnspecifiedError;
        }

        state.stream_conn_handle = None;

        Status::Success
    }
}

impl Module for A2dpModule {
    fn next(&self) -> &dyn Module {
        &*self.next_module
    }

    fn out_cmd(&self, data: &[u8]) {
        match Command::from_bytes(data) {
            Ok(Command::A2dpHardwareOffload(ref cmd)) => {
                log::debug!("A2dpModule::out_cmd: {:?}", cmd);
                let mut state = self.state.lock().unwrap();
                match cmd {
                    hci::A2dpHardwareOffload::StartA2dpOffload(ref start) => {
                        let status = self.start_a2dp_offload(&mut state, start);

                        let event = hci::CommandComplete {
                            num_hci_command_packets: 1,
                            return_parameters: ReturnParameters::A2dpHardwareOffload(
                                A2dpHardwareOffloadComplete::new(status, start.opcode()),
                            ),
                        };

                        self.next().in_evt(&event.to_bytes());
                        return;
                    }
                    hci::A2dpHardwareOffload::StopA2dpOffload(ref stop) => {
                        let status = self.stop_a2dp_offload(&mut state, stop);

                        let event = hci::CommandComplete {
                            num_hci_command_packets: 1,
                            return_parameters: ReturnParameters::A2dpHardwareOffload(
                                A2dpHardwareOffloadComplete::new(status, stop.opcode()),
                            ),
                        };

                        self.next().in_evt(&event.to_bytes());
                        return;
                    }
                    _ => (),
                }
            }
            Ok(_) => (),
            Err(code) => {
                log::error!("A2dpModule::out_cmd: Malformed command with code: {code:?}");
            }
        }
        self.next().out_cmd(data);
    }

    fn in_evt(&self, data: &[u8]) {
        match Event::from_bytes(data) {
            Ok(Event::CommandComplete(ref e)) => match e.return_parameters {
                ReturnParameters::Reset(ref ret) if ret.status == Status::Success => {
                    log::debug!("A2dpModule::in_evt: {:?}", ret);
                    let mut state = self.state.lock().unwrap();
                    *state = Default::default();
                    Service::reset();
                }

                ReturnParameters::LeGetVendorCapabilities(ref ret)
                    if ret.status == Status::Success =>
                {
                    log::debug!("A2dpModule::in_evt: {:?}", ret);
                    let mut vendor_capabilities: LeGetVendorCapabilitiesComplete = ret.clone();
                    if vendor_capabilities.version_supported >= Some(0x0104) {
                        log::info!("A2dpModule::in_evt: A2DP Offload V2 supported");
                        vendor_capabilities.a2dp_offload_v2_support = Some(1);
                    } else {
                        log::info!("A2dpModule::in_evt: LE Vendor Capabilities version 1.04 not supported - overriding event");
                        vendor_capabilities.version_supported = Some(0x0104);
                        vendor_capabilities.total_num_of_advt_tracked = Some(0);
                        vendor_capabilities.extended_scan_support = Some(0);
                        vendor_capabilities.debug_logging_supported = Some(0);
                        vendor_capabilities.le_address_generation_offloading_support = Some(0);
                        vendor_capabilities.a2dp_source_offload_capability_mask = Some(0);
                        vendor_capabilities.bluetooth_quality_report_support = Some(0);
                        vendor_capabilities.dynamic_audio_buffer_support = Some(0);
                        vendor_capabilities.a2dp_offload_v2_support = Some(1);
                        vendor_capabilities.iso_link_feedback_support = Some(0);
                    }
                    let event = hci::CommandComplete {
                        num_hci_command_packets: e.num_hci_command_packets,
                        return_parameters: ReturnParameters::LeGetVendorCapabilities(
                            vendor_capabilities,
                        ),
                    };
                    self.next().in_evt(&event.to_bytes());
                    return;
                }
                ReturnParameters::ReadBufferSize(ref ret) if ret.status == Status::Success => {
                    log::debug!("A2dpModule::in_evt: {:?}", ret);
                    let mut state = self.state.lock().unwrap();
                    state.arbiter = Some(Arc::new(Arbiter::new(
                        self.next_module.clone(),
                        ret.acl_data_packet_length.into(),
                        ret.total_num_acl_data_packets.into(),
                    )));
                    Service::set_arbiter(Arc::downgrade(state.arbiter.as_ref().unwrap()));
                }
                _ => (),
            },

            Ok(Event::ConnectionComplete(ref e)) => 'event: {
                log::debug!("A2dpModule::in_evt: {:?}, handle: {:?}", e, e.connection_handle);
                if e.status != Status::Success {
                    log::error!("A2dpModule::in_evt: {:?}", e);
                    break 'event;
                }

                if e.link_type != LINK_TYPE_ACL {
                    break 'event;
                }

                let mut state = self.state.lock().unwrap();
                let Some(arbiter) = state.arbiter.clone() else {
                    log::error!("ConnectionComplete: Arbiter not initialized");
                    break 'event;
                };

                if state.connection_handles.contains(&e.connection_handle) {
                    panic!(
                        "ConnectionComplete: Connection with handle 0x{0:03x} already exists",
                        e.connection_handle
                    );
                }
                log::info!("ConnectionComplete: Save connection handle: {:?}", e.connection_handle);
                state.connection_handles.push(e.connection_handle);
                arbiter.add_connection(e.connection_handle);
            }

            Ok(Event::DisconnectionComplete(ref e)) if e.status == Status::Success => {
                log::debug!("A2dpModule::in_evt: {:?}, handle: {:?}", e, e.connection_handle);
                let mut state = self.state.lock().unwrap();

                if state.stream_conn_handle == Some(e.connection_handle) {
                    if let Err(e) = Service::stop_stream(e.connection_handle) {
                        log::error!("DisconnectionComplete: Failed to stop stream: {}", e);
                    }
                    log::debug!(
                        "DisconnectionComplete: Removing stream handle: {:?}",
                        e.connection_handle
                    );
                    state.stream_conn_handle = None;
                }

                if let Some(arbiter) = state.arbiter.as_ref() {
                    let arbiter_queued_packets_count =
                        arbiter.remove_connection(e.connection_handle);
                    if arbiter_queued_packets_count != 0 {
                        // Arbiter cleared packets that were queued but not yet sent to the controller,
                        // so we need to pass the number of completed packets to the stack.
                        let event = hci::NumberOfCompletedPackets {
                            handles: vec![hci::NumberOfCompletedPacketsHandle {
                                connection_handle: e.connection_handle,
                                num_completed_packets: arbiter_queued_packets_count,
                            }],
                        };
                        log::warn!(
                            "DisconnectionComplete: pass generated number of complete packets to the stack: {:?}",
                            event
                        );
                        self.next().in_evt(&event.to_bytes());
                    }
                }
                if let Some(index) =
                    state.connection_handles.iter().position(|&h| h == e.connection_handle)
                {
                    state.connection_handles.swap_remove(index);
                }
            }

            Ok(Event::NumberOfCompletedPackets(ref e)) => 'event: {
                log::debug!("A2dpModule::in_evt: {:?}", e);
                let state = self.state.lock().unwrap();
                let Some(arbiter) = state.arbiter.as_ref() else {
                    log::error!("NumberOfCompletedPackets: Arbiter not initialized");
                    break 'event;
                };

                // We need to filter out the number of completed packets that belong to the
                // offloaded audio stream, as the stack is not aware of them and should not
                // receive credits for them.
                let mut stack_event =
                    hci::NumberOfCompletedPackets { handles: Vec::with_capacity(e.handles.len()) };

                for item in &e.handles {
                    let handle = item.connection_handle;

                    // Update the arbiter with the total number of completed packets.
                    // It returns the number of packets that were sent by the stack (Origin::Incoming).
                    let stack_count =
                        arbiter.set_completed(handle, item.num_completed_packets.into());

                    // Only report back to the stack if there are completed packets
                    // that originated from the stack.
                    if stack_count > 0 {
                        log::debug!("NumberOfCompletedPackets: stack_count: {:?}", stack_count);
                        let mut stack_item = *item;
                        stack_item.num_completed_packets = stack_count as u16;
                        stack_event.handles.push(stack_item);
                    } else {
                        log::debug!("NumberOfCompletedPackets: drop audio event");
                    }
                }

                // If there are any handles left to report to the stack, send the modified event.
                if !stack_event.handles.is_empty() {
                    log::debug!(
                        "NumberOfCompletedPackets: pass event to the stack: {:?}",
                        stack_event
                    );
                    self.next().in_evt(&stack_event.to_bytes());
                }
                return;
            }

            Ok(_) => (),
            Err(code) => {
                log::error!("A2dpModule::in_evt: Malformed event with code: {code:?}");
            }
        }
        self.next().in_evt(data);
    }

    fn out_acl(&self, data: &[u8]) {
        let acl_packet = match AclData::from_bytes(data) {
            Some(packet) => packet,
            None => {
                // If we can't parse the packet (e.g. malformed or truncated),
                // we can't determine if it belongs to an offloaded connection.
                // Pass it through to the controller to handle (or reject).
                log::warn!("A2dpModule::out_acl: Malformed ACL!() packet");
                self.next().out_acl(data);
                return;
            }
        };

        log::debug!(
            "A2dpModule::out_acl: handle: {:?}, acl_packet len: {:?}",
            acl_packet.connection_handle,
            acl_packet.payload.len()
        );

        let state = self.state.lock().unwrap();
        let arbiter = state.arbiter.as_ref().unwrap();
        arbiter.push_incoming(&acl_packet);
    }
}
