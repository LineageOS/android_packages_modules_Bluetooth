// packages/modules/Bluetooth/offload/a2dp/hci/tests.rs
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

use bluetooth_offload_hci as hci;

use crate::proxy::{A2dpModule, A2dpModuleBuilder};
use bluetooth_offload_hci::BluetoothAddress;
use hci::{
    AclData, AclType, CommandToBytes, EventToBytes, Module, ModuleBuilder, ReturnParameters, Status,
};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

struct ModuleSinkState {
    out_cmd: Vec<Vec<u8>>,
    in_evt: Vec<Vec<u8>>,
    out_acl: Vec<Vec<u8>>,
}

struct ModuleSink {
    state: Mutex<ModuleSinkState>,
}

impl ModuleSink {
    fn new() -> Self {
        ModuleSink {
            state: Mutex::new(ModuleSinkState {
                out_cmd: Default::default(),
                in_evt: Default::default(),
                out_acl: Default::default(),
            }),
        }
    }
}

impl Module for ModuleSink {
    fn out_cmd(&self, data: &[u8]) {
        self.state.lock().unwrap().out_cmd.push(data.to_vec());
    }
    fn in_evt(&self, data: &[u8]) {
        self.state.lock().unwrap().in_evt.push(data.to_vec());
    }
    fn out_acl(&self, data: &[u8]) {
        self.state.lock().unwrap().out_acl.push(data.to_vec());
    }
    fn out_iso(&self, _data: &[u8]) {
        // A2DP does not use ISO
    }

    fn next(&self) -> &dyn Module {
        panic!("Sink has no next module");
    }
}

/// Helper to setup the A2DP module chain and initialize it with default buffer sizes
fn setup() -> (Arc<A2dpModule>, Arc<ModuleSink>) {
    let sink = Arc::new(ModuleSink::new());
    let module = Arc::new(A2dpModule::new(sink.clone()));

    // 1. Send Reset Complete to clear state
    module.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 1,
            return_parameters: ReturnParameters::Reset(hci::ResetComplete {
                status: Status::Success,
            }),
        }
        .to_bytes(),
    );

    // 2. Send ReadBufferSize Complete to initialize the Arbiter
    module.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 1,
            return_parameters: ReturnParameters::ReadBufferSize(hci::ReadBufferSizeComplete {
                status: Status::Success,
                acl_data_packet_length: 1024,
                synchronous_data_packet_length: 0,
                total_num_acl_data_packets: 10,
                total_num_synchronous_data_packets: 0,
            }),
        }
        .to_bytes(),
    );

    // Clear any events captured in the sink during setup
    {
        let mut state = sink.state.lock().unwrap();
        state.out_cmd.clear();
        state.in_evt.clear();
        state.out_acl.clear();
    }

    (module, sink)
}

#[test]
fn test_a2dp_start_stop() {
    let (m, sink) = setup();
    let handle = 0x0001;

    // --- Start A2DP Offload ---
    let start_cmd = hci::A2dpHardwareOffload::StartA2dpOffload(hci::StartA2dpOffload {
        connection_handle: handle,
        l2cap_channel_id: 0x0040,
        data_path_direction: 0,
        peer_mtu: 600,
        cp_enable_scms_t: 0,
        cp_header_scms_t: 0,
        vendor_specific_parameters: vec![],
    });

    m.out_cmd(&start_cmd.to_bytes());

    {
        let state = sink.state.lock().unwrap();
        // The command should be consumed by the proxy and NOT forwarded to the sink
        assert!(state.out_cmd.is_empty());
        // The proxy should generate a CommandComplete event
        assert_eq!(state.in_evt.len(), 1);

        if let Ok(hci::Event::CommandComplete(e)) = hci::Event::from_bytes(&state.in_evt[0]) {
            if let ReturnParameters::A2dpHardwareOffload(p) = e.return_parameters {
                assert_eq!(p.status, Status::Success);
            } else {
                panic!("Unexpected return parameters");
            }
        } else {
            panic!("Unexpected event type");
        }
    }

    // --- Stop A2DP Offload ---
    let stop_cmd = hci::A2dpHardwareOffload::StopA2dpOffload(hci::StopA2dpOffload {
        connection_handle: handle,
        l2cap_channel_id: 0x0040,
        data_path_direction: 0,
    });

    m.out_cmd(&stop_cmd.to_bytes());

    {
        let state = sink.state.lock().unwrap();
        // Command consumed
        assert!(state.out_cmd.is_empty());
        // New CommandComplete event added
        assert_eq!(state.in_evt.len(), 2);
    }
}

#[test]
fn test_vendor_capabilities_override() {
    let (m, sink) = setup();

    // Simulate Controller sending Vendor Capabilities with an older version
    // that doesn't explicitly support V2 offload.
    let caps = hci::LeGetVendorCapabilitiesComplete {
        status: Status::Success,
        max_advt_instances: 0,
        offloaded_resolution_of_private_address: 0,
        total_scan_results_storage: 0,
        max_irk_list_sz: 0,
        filtering_support: 0,
        max_filter: 0,
        activity_energy_info_support: 0,
        version_supported: Some(0x0103), // Version < 0x0104
        total_num_of_advt_tracked: None,
        extended_scan_support: None,
        debug_logging_supported: None,
        le_address_generation_offloading_support: None,
        a2dp_source_offload_capability_mask: None,
        bluetooth_quality_report_support: None,
        dynamic_audio_buffer_support: None,
        a2dp_offload_v2_support: Some(0), // Not supported by controller
        iso_link_feedback_support: None,
        sniff_offload_support: None,
        big_set_channel_map_classification_support: None,
        vendor_connection_handle_min: None,
        vendor_connection_handle_max: None,
    };

    let event = hci::CommandComplete {
        num_hci_command_packets: 1,
        return_parameters: ReturnParameters::LeGetVendorCapabilities(caps),
    };

    m.in_evt(&event.to_bytes());

    {
        let state = sink.state.lock().unwrap();
        assert_eq!(state.in_evt.len(), 1);

        // Verify the proxy intercepted and modified the event
        if let Ok(hci::Event::CommandComplete(e)) = hci::Event::from_bytes(&state.in_evt[0]) {
            if let ReturnParameters::LeGetVendorCapabilities(p) = e.return_parameters {
                // Proxy should override version to 0x0104 and enable V2 support
                assert_eq!(p.version_supported, Some(0x0104));
                assert_eq!(p.a2dp_offload_v2_support, Some(1));
            } else {
                panic!("Wrong return parameters");
            }
        } else {
            panic!("Failed to parse event");
        }
    }
}

#[test]
fn test_acl_data_routing_before_connection() {
    let (m, sink) = setup();
    let handle = 0x0001;

    // 1. Send ACL data BEFORE ConnectionComplete
    // This simulates the race condition where data arrives before the event.
    // The Arbiter should accept this packet instead of dropping it.
    let acl_data = AclData::new(
        handle,
        AclType::First { is_flushable: true, is_broadcast: false },
        &[0xAA, 0xBB, 0xCC],
    );
    m.out_acl(&acl_data.to_bytes());

    // 2. Wait for Arbiter thread to process the packet
    thread::sleep(Duration::from_millis(50));

    {
        let state = sink.state.lock().unwrap();
        // The packet should reach the sink
        assert_eq!(state.out_acl.len(), 1);
        let forwarded = AclData::from_bytes(&state.out_acl[0]).unwrap();
        assert_eq!(forwarded.connection_handle, handle);
        assert_eq!(forwarded.payload, vec![0xAA, 0xBB, 0xCC]);
    }

    // 3. Establish connection
    // This verifies that adding a connection that was implicitly created doesn't panic.
    let conn_evt = hci::ConnectionComplete {
        status: Status::Success,
        connection_handle: handle,
        link_type: 0x01, // ACL
        encryption_enabled: 0,
        bd_addr: BluetoothAddress::from_be_bytes([0x01, 0x02, 0x03, 0x04, 0x05, 0x06]),
    };
    m.in_evt(&conn_evt.to_bytes());
}

#[test]
fn test_flow_control_event_splitting() {
    let (m, sink) = setup();
    let offload_handle = 0x0001;
    let stack_handle = 0x0002;

    // 1. Establish connections
    for h in [offload_handle, stack_handle] {
        m.in_evt(
            &hci::ConnectionComplete {
                status: Status::Success,
                connection_handle: h,
                link_type: 0x01,
                encryption_enabled: 0,
                bd_addr: BluetoothAddress::from_be_bytes([0x01, 0x02, 0x03, 0x04, 0x05, 0x06]),
            }
            .to_bytes(),
        );
    }

    // 2. Send packets from Stack for stack_handle
    // We need to send packets so the Arbiter tracks them as "Incoming" (from stack).
    // The Arbiter will only return credits for packets it knows came from the stack.
    for _ in 0..2 {
        m.out_acl(
            &AclData::new(
                stack_handle,
                AclType::First { is_flushable: true, is_broadcast: false },
                &[0x01],
            )
            .to_bytes(),
        );
    }

    // 3. Send packets from Audio for offload_handle
    // We need to send packets so the Arbiter tracks them as "Audio" (from audio).
    // The Arbiter will not return credits for packets it knows came from audio.
    {
        let state = m.get_state().lock().unwrap();
        for _ in 0..5 {
            state.arbiter.as_ref().unwrap().push_audio(&AclData::new(
                offload_handle,
                AclType::First { is_flushable: true, is_broadcast: false },
                &[0x01],
            ));
        }
    }

    // Wait for packets to be processed by Arbiter (moved from queue to in_transit)
    thread::sleep(Duration::from_millis(50));

    // Clear sink events from setup/connection
    {
        let mut state = sink.state.lock().unwrap();
        state.in_evt.clear();
    }

    // 4. Simulate NumberOfCompletedPackets event containing both handles
    // offload_handle: 5 completed (but 0 sent by stack, so Arbiter should filter these out)
    // stack_handle: 2 completed (2 sent by stack, so Arbiter should report these)
    let event = hci::NumberOfCompletedPackets {
        handles: vec![
            hci::NumberOfCompletedPacketsHandle {
                connection_handle: offload_handle,
                num_completed_packets: 5,
            },
            hci::NumberOfCompletedPacketsHandle {
                connection_handle: stack_handle,
                num_completed_packets: 2,
            },
        ],
    };
    m.in_evt(&event.to_bytes());

    {
        let state = sink.state.lock().unwrap();
        // We expect one event
        assert!(!state.in_evt.is_empty());

        // Find the NumberOfCompletedPackets event
        let completed_evt = state
            .in_evt
            .iter()
            .find_map(|bytes| {
                if let Ok(hci::Event::NumberOfCompletedPackets(e)) = hci::Event::from_bytes(bytes) {
                    Some(e)
                } else {
                    None
                }
            })
            .expect("NumberOfCompletedPackets event not found in sink");

        // The proxy should filter out the offloaded handle and only forward the stack one
        assert_eq!(completed_evt.handles.len(), 1);
        assert_eq!(completed_evt.handles[0].connection_handle, stack_handle);
        assert_eq!(completed_evt.handles[0].num_completed_packets, 2);
    }
}

#[test]
fn test_disconnection_cleanup() {
    let (m, sink) = setup();
    let handle = 0x0001;

    // 1. Connect
    m.in_evt(
        &hci::ConnectionComplete {
            status: Status::Success,
            connection_handle: handle,
            link_type: 0x01,
            encryption_enabled: 0,
            bd_addr: BluetoothAddress::from_be_bytes([0x01, 0x02, 0x03, 0x04, 0x05, 0x06]),
        }
        .to_bytes(),
    );

    // 2. Start Offload (just to set internal state, though not strictly required for this test)
    let start_cmd = hci::A2dpHardwareOffload::StartA2dpOffload(hci::StartA2dpOffload {
        connection_handle: handle,
        l2cap_channel_id: 0x0040,
        data_path_direction: 0,
        peer_mtu: 600,
        cp_enable_scms_t: 0,
        cp_header_scms_t: 0,
        vendor_specific_parameters: vec![],
    });
    m.out_cmd(&start_cmd.to_bytes());

    // 3. Send Disconnection Complete event
    let disconnect_evt = hci::DisconnectionComplete {
        status: Status::Success,
        connection_handle: handle,
        reason: 0x13, // Remote User Terminated Connection
    };
    m.in_evt(&disconnect_evt.to_bytes());

    // 4. Verify behavior after disconnection
    // The Arbiter is now permissive and will forward packets even if the connection
    // was closed (it implicitly re-creates the queue). This prevents data loss
    // during race conditions.
    let acl_data = AclData::new(
        handle,
        AclType::First { is_flushable: true, is_broadcast: false },
        &[0xDE, 0xAD, 0xBE, 0xEF],
    );
    m.out_acl(&acl_data.to_bytes());

    // Wait for potential processing
    thread::sleep(Duration::from_millis(50));

    {
        let state = sink.state.lock().unwrap();
        // Check that disconnection event passed through
        assert!(state.in_evt.iter().any(|e| matches!(
            hci::Event::from_bytes(e),
            Ok(hci::Event::DisconnectionComplete(_))
        )));

        // Check that ACL data was forwarded (not dropped)
        assert_eq!(state.out_acl.len(), 1);
    }
}

#[test]
fn test_passthrough_non_a2dp_command() {
    let (m, sink) = setup();

    // Send a standard HCI command (e.g., Read Local Version Information)
    // Opcode: 0x1001 (OGF: 4, OCF: 1) -> Little Endian: 0x01, 0x10
    // Parameter Length: 0
    let cmd_bytes = [0x01, 0x10, 0x00];
    m.out_cmd(&cmd_bytes);

    let state = sink.state.lock().unwrap();
    // The command should be forwarded to the sink (Controller)
    assert_eq!(state.out_cmd.len(), 1);
    assert_eq!(state.out_cmd[0], cmd_bytes.to_vec());
}

#[test]
fn test_passthrough_non_intercepted_event() {
    let (m, sink) = setup();

    // Send a standard HCI event (e.g., Hardware Error)
    // Event Code: 0x10
    // Parameter Length: 1
    // Hardware Code: 0x00
    let evt_bytes = [0x10, 0x01, 0x00];
    m.in_evt(&evt_bytes);

    let state = sink.state.lock().unwrap();
    // The event should be forwarded to the sink (Host)
    assert_eq!(state.in_evt.len(), 1);
    assert_eq!(state.in_evt[0], evt_bytes.to_vec());
}

#[test]
fn test_passthrough_malformed_acl() {
    let (m, sink) = setup();

    // Send a truncated ACL packet (header is 4 bytes, sending only 2)
    // This simulates a failure scenario where the transport layer delivers incomplete data.
    let malformed_acl = [0x02, 0x00];
    m.out_acl(&malformed_acl);

    let state = sink.state.lock().unwrap();
    // The packet should be forwarded to the sink (Controller).
    // The proxy should not crash or drop it just because it can't parse the handle.
    assert_eq!(state.out_acl.len(), 1);
    assert_eq!(state.out_acl[0], malformed_acl.to_vec());
}

#[test]
fn test_arbiter_remove_connection_returns_dropped_stack_packets() {
    // Setup: Use the existing ModuleSink and create an Arbiter.
    // We set total_num_acl_data_packets to 1. This allows us to easily saturate
    // the controller's credit limit, forcing subsequent packets to stay in the Arbiter's queue.
    let sink = Arc::new(ModuleSink::new());
    let arbiter = crate::arbiter::Arbiter::new(sink.clone(), 1024, 1);
    let handle = 0x0001;

    arbiter.add_connection(handle);

    // 1. Send one packet to consume the single available credit.
    // This packet will be pulled by the thread and moved to 'in_transit'.
    let packet_sent =
        AclData::new(handle, AclType::First { is_flushable: true, is_broadcast: false }, &[0x01]);
    arbiter.push_incoming(&packet_sent);

    // Wait briefly for the Arbiter thread to process the first packet.
    thread::sleep(Duration::from_millis(50));

    // Verify the first packet reached the sink.
    {
        let state = sink.state.lock().unwrap();
        assert_eq!(state.out_acl.len(), 1, "First packet should be sent to sink");
    }

    // 2. Queue additional packets.
    // Since the credit limit (1) is reached, these will remain in the Arbiter's internal queues.

    // Stack Packet 1 (Should be counted when dropped)
    let packet_stack_1 =
        AclData::new(handle, AclType::First { is_flushable: true, is_broadcast: false }, &[0x02]);
    arbiter.push_incoming(&packet_stack_1);

    // Audio Packet (Should be dropped, but NOT counted as a completed packet for the host stack)
    let packet_audio =
        AclData::new(handle, AclType::First { is_flushable: true, is_broadcast: false }, &[0x03]);
    arbiter.push_audio(&packet_audio);

    // Stack Packet 2 (Should be counted when dropped)
    let packet_stack_2 =
        AclData::new(handle, AclType::First { is_flushable: true, is_broadcast: false }, &[0x04]);
    arbiter.push_incoming(&packet_stack_2);

    // 3. Remove the connection.
    // This simulates a disconnect. The Arbiter should drop all queued packets for this handle
    // and return the count of dropped "Incoming" (stack) packets so they can be acked.
    let dropped_counts = arbiter.remove_connection(handle);

    // 4. Verify the results.
    // We expect exactly 2 packets to be reported (packet_stack_1 and packet_stack_2).
    // The audio packet is ignored for flow control purposes.
    // The first packet is not included because it was already "sent" (in_transit).
    assert_eq!(dropped_counts, 2, "Should return 2 dropped stack packets");

    // Verify that no extra packets were flushed to the sink during removal.
    {
        let state = sink.state.lock().unwrap();
        assert_eq!(state.out_acl.len(), 1, "Sink should still only have the first packet");
    }
}

#[test]
fn test_arbiter_mixed_credit_return() {
    let sink = Arc::new(ModuleSink::new());
    // Give enough credits so we don't block
    let arbiter = crate::arbiter::Arbiter::new(sink.clone(), 1024, 10);
    let handle = 0x0001;
    arbiter.add_connection(handle);

    // 1. Send a mix of Audio and Stack packets.
    // Sequence: Audio, Stack, Audio
    let p_audio =
        AclData::new(handle, AclType::First { is_flushable: true, is_broadcast: false }, &[0x01]);
    let p_stack =
        AclData::new(handle, AclType::First { is_flushable: true, is_broadcast: false }, &[0x02]);

    arbiter.push_audio(&p_audio);
    arbiter.push_incoming(&p_stack);
    arbiter.push_audio(&p_audio);

    thread::sleep(Duration::from_millis(20));

    // 2. Simulate Controller acknowledging 2 packets.
    // The `in_transit` queue for this handle is [Audio, Incoming, Audio].
    // Removing the first 2 items removes [Audio, Incoming].
    // The Arbiter should count how many of those were `Incoming` (Stack) packets.
    let returned_credits = arbiter.set_completed(handle, 2);

    // 3. Verify only the stack packet is returned as a credit to the host.
    assert_eq!(
        returned_credits, 1,
        "Should return 1 credit for the stack packet, ignoring the audio packet"
    );

    // 4. Acknowledge the remaining packet.
    // Remaining queue: [Audio].
    let returned_rest = arbiter.set_completed(handle, 1);
    assert_eq!(returned_rest, 0, "Should return 0 credits for the remaining audio packet");
}

#[test]
fn test_arbiter_remove_connection_frees_credits() {
    let sink = Arc::new(ModuleSink::new());
    // 1 Global Credit shared across all connections
    let arbiter = crate::arbiter::Arbiter::new(sink.clone(), 1024, 1);
    let h1 = 0x0001;
    let h2 = 0x0002;

    arbiter.add_connection(h1);
    arbiter.add_connection(h2);

    // 1. Saturate the single global credit with Connection 1
    let p1 = AclData::new(h1, AclType::First { is_flushable: true, is_broadcast: false }, &[0x11]);
    arbiter.push_incoming(&p1);
    thread::sleep(Duration::from_millis(20));

    // 2. Queue a packet for Connection 2.
    // This should be blocked because the global credit is used by h1.
    let p2 = AclData::new(h2, AclType::First { is_flushable: true, is_broadcast: false }, &[0x22]);
    arbiter.push_incoming(&p2);
    thread::sleep(Duration::from_millis(20));

    {
        let state = sink.state.lock().unwrap();
        assert_eq!(state.out_acl.len(), 1, "Only packet 1 should be sent");
    }

    // 3. Remove Connection 1.
    // This must remove h1's packets from the `in_transit` map.
    // Since `Arbiter::pull` calculates usage based on `in_transit` size,
    // removing h1 should free up the credit, allowing h2 to send.
    arbiter.remove_connection(h1);

    // Wait for Arbiter to wake up and process h2
    thread::sleep(Duration::from_millis(20));

    // 4. Verify Connection 2's packet is now sent.
    {
        let state = sink.state.lock().unwrap();
        assert_eq!(state.out_acl.len(), 2, "Packet 2 should be sent after H1 removal");
        let last_pkt = AclData::from_bytes(state.out_acl.last().unwrap()).unwrap();
        assert_eq!(last_pkt.connection_handle, h2);
        assert_eq!(last_pkt.payload, vec![0x22]);
    }
}

#[test]
fn test_arbiter_set_completed_does_not_modify_invalid_nocp() {
    let sink = Arc::new(ModuleSink::new());
    let arbiter = crate::arbiter::Arbiter::new(sink.clone(), 1024, 10);
    let handle = 0x0001;
    arbiter.add_connection(handle);

    // 1. Send 1 Stack packet.
    let p1 =
        AclData::new(handle, AclType::First { is_flushable: true, is_broadcast: false }, &[0x01]);
    arbiter.push_incoming(&p1);

    // 2. Send 1 Audio packet.
    let p2 =
        AclData::new(handle, AclType::First { is_flushable: true, is_broadcast: false }, &[0x22]);
    arbiter.push_audio(&p2);
    thread::sleep(Duration::from_millis(20));

    // 3. Report 5 completed packets (more than sent).
    // This should be handled by the stack. The implementation should only:
    // - Log an error
    // - Subtract the audio packets count
    // - Return the unchanged count of stack packets found 5 - 1 audio packet = 4
    let returned = arbiter.set_completed(handle, 5);

    assert_eq!(returned, 4, "Should return 4 credit if controller reports excessive completions");
}

#[test]
fn test_disconnect_flushes_queued_packets() {
    let sink = Arc::new(ModuleSink::new());
    let builder = A2dpModuleBuilder {};
    let m = builder.build(sink.clone());

    // 1. Send Reset Complete to clear state.
    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 1,
            return_parameters: ReturnParameters::Reset(hci::ResetComplete {
                status: Status::Success,
            }),
        }
        .to_bytes(),
    );

    // 2. Send ReadBufferSize Complete to initialize the Arbiter.
    // We set total_num_acl_data_packets to 1.
    // This allows us to easily saturate the controller's credit limit, forcing subsequent packets
    // to stay in the Arbiter's queue.
    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 1,
            return_parameters: ReturnParameters::ReadBufferSize(hci::ReadBufferSizeComplete {
                status: Status::Success,
                acl_data_packet_length: 1024,
                synchronous_data_packet_length: 0,
                total_num_acl_data_packets: 1,
                total_num_synchronous_data_packets: 0,
            }),
        }
        .to_bytes(),
    );

    // Clear any events captured in the sink during setup
    {
        let mut state = sink.state.lock().unwrap();
        state.out_cmd.clear();
        state.in_evt.clear();
        state.out_acl.clear();
    }

    let handle = 0x0001;

    // 1. Connect and initialize
    m.in_evt(
        &hci::ConnectionComplete {
            status: Status::Success,
            connection_handle: handle,
            link_type: 0x01,
            encryption_enabled: 0,
            bd_addr: BluetoothAddress::from_be_bytes([0x01, 0x02, 0x03, 0x04, 0x05, 0x06]),
        }
        .to_bytes(),
    );

    // Clear sink events from setup
    {
        let mut state = sink.state.lock().unwrap();
        state.in_evt.clear();
        state.out_acl.clear();
    }

    // 2. Queue some packets
    // We send packets from the Stack. Since we provided only 1 credit to the Arbiter
    // (via LeReadBufferSize response), two of these packets should be queued internally
    // and NOT forwarded to the controller yet.
    let num_packets = 3;
    for _ in 0..num_packets {
        m.out_acl(
            &AclData::new(
                handle,
                AclType::First { is_flushable: true, is_broadcast: false },
                &[0xDE, 0xAD, 0xBE, 0xEF],
            )
            .to_bytes(),
        );
    }

    // Wait briefly for Arbiter to process the input channel
    thread::sleep(Duration::from_millis(20));

    // Verify that only one packet is sent to the controller
    {
        let state = sink.state.lock().unwrap();
        assert_eq!(
            state.out_acl.len(),
            1,
            "Packets should be queued by Arbiter, not sent to Controller"
        );
    }

    // 3. BEFORE arbiter sends them to the controller, disconnect
    m.in_evt(
        &hci::DisconnectionComplete {
            status: Status::Success,
            connection_handle: handle,
            reason: 0x13, // Remote User Terminated Connection
        }
        .to_bytes(),
    );

    // 4. Check that the number of complete packets for the queued packets was correctly sent to the stack
    {
        let state = sink.state.lock().unwrap();

        // We expect the proxy to generate a NumberOfCompletedPackets event
        // to return credits for the packets that were dropped from the queue.
        let completed_evt = state
            .in_evt
            .iter()
            .find_map(|bytes| {
                if let Ok(hci::Event::NumberOfCompletedPackets(e)) = hci::Event::from_bytes(bytes) {
                    Some(e)
                } else {
                    None
                }
            })
            .expect("NumberOfCompletedPackets event not found in sink");

        assert_eq!(completed_evt.handles.len(), 1);
        assert_eq!(completed_evt.handles[0].connection_handle, handle);
        assert_eq!(completed_evt.handles[0].num_completed_packets, 2);
    }
}
