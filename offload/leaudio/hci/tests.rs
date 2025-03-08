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

use bluetooth_offload_hci as hci;

use crate::proxy::LeAudioModule;
use hci::{CommandToBytes, EventToBytes, IsoData, Module, ReturnParameters, Status};
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;

struct ModuleSinkState {
    out_cmd: Vec<Vec<u8>>,
    in_evt: Vec<Vec<u8>>,
    out_iso: mpsc::Receiver<Vec<u8>>,
}

struct ModuleSink {
    state: Mutex<ModuleSinkState>,
    out_iso: mpsc::Sender<Vec<u8>>,
}

impl ModuleSink {
    fn new() -> Self {
        let (out_iso_tx, out_iso_rx) = mpsc::channel();
        ModuleSink {
            state: Mutex::new(ModuleSinkState {
                out_cmd: Default::default(),
                in_evt: Default::default(),
                out_iso: out_iso_rx,
            }),
            out_iso: out_iso_tx,
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
    fn out_iso(&self, data: &[u8]) {
        self.out_iso.send(data.to_vec()).expect("Sending ISO packet");
    }

    fn next(&self) -> &dyn Module {
        panic!();
    }
}

#[test]
fn cig() {
    let sink: Arc<ModuleSink> = Arc::new(ModuleSink::new());
    let m = LeAudioModule::new(sink.clone());

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::Reset(hci::ResetComplete {
                status: Status::Success,
            }),
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeReadBufferSizeV2(
                hci::LeReadBufferSizeV2Complete {
                    status: Status::Success,
                    le_acl_data_packet_length: 0,
                    total_num_le_acl_data_packets: 0,
                    iso_data_packet_length: 16,
                    total_num_iso_data_packets: 2,
                },
            ),
        }
        .to_bytes(),
    );

    m.out_cmd(
        &hci::LeSetCigParameters {
            cig_id: 0x01,
            sdu_interval_c_to_p: 10_000,
            sdu_interval_p_to_c: 10_000,
            worst_case_sca: 0,
            packing: 0,
            framing: 0,
            max_transport_latency_c_to_p: 0,
            max_transport_latency_p_to_c: 0,
            cis: vec![
                hci::LeCisInCigParameters {
                    cis_id: 0,
                    max_sdu_c_to_p: 120,
                    max_sdu_p_to_c: 120,
                    phy_c_to_p: 0,
                    phy_p_to_c: 0,
                    rtn_c_to_p: 0,
                    rtn_p_to_c: 0,
                },
                hci::LeCisInCigParameters {
                    cis_id: 1,
                    max_sdu_c_to_p: 150,
                    max_sdu_p_to_c: 150,
                    phy_c_to_p: 0,
                    phy_p_to_c: 0,
                    rtn_c_to_p: 0,
                    rtn_p_to_c: 0,
                },
            ],
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeSetCigParameters(
                hci::LeSetCigParametersComplete {
                    status: Status::Success,
                    cig_id: 0x01,
                    connection_handles: vec![0x123, 0x456],
                },
            ),
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::LeCisEstablished {
            status: Status::Success,
            connection_handle: 0x456,
            cig_sync_delay: 0,
            cis_sync_delay: 0,
            transport_latency_c_to_p: 0,
            transport_latency_p_to_c: 0,
            phy_c_to_p: 0x02,
            phy_p_to_c: 0x02,
            nse: 0,
            bn_c_to_p: 2,
            bn_p_to_c: 2,
            ft_c_to_p: 1,
            ft_p_to_c: 1,
            max_pdu_c_to_p: 10,
            max_pdu_p_to_c: 0,
            iso_interval: 20_000 / 1250,
        }
        .to_bytes(),
    );

    m.out_cmd(
        &hci::LeSetupIsoDataPath {
            connection_handle: 0x456,
            data_path_direction: hci::LeDataPathDirection::Input,
            data_path_id: 0,
            codec_id: hci::LeCodecId {
                coding_format: hci::CodingFormat::Transparent,
                company_id: 0,
                vendor_id: 0,
            },
            controller_delay: 0,
            codec_configuration: vec![],
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeSetupIsoDataPath(hci::LeIsoDataPathComplete {
                status: Status::Success,
                connection_handle: 0x456,
            }),
        }
        .to_bytes(),
    );

    m.out_iso(&IsoData::new(0x456, 0, &[0x00, 0x11]).to_bytes());
    m.out_iso(&IsoData::new(0x456, 1, &[]).to_bytes());
    {
        let state = sink.state.lock().unwrap();
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
    }

    m.in_evt(
        &hci::NumberOfCompletedPackets {
            handles: vec![hci::NumberOfCompletedPacketsHandle {
                connection_handle: 0x456,
                num_completed_packets: 1,
            }],
        }
        .to_bytes(),
    );

    m.out_iso(&IsoData::new(0x456, 2, &[0x22]).to_bytes());
    {
        let state = sink.state.lock().unwrap();
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
    }

    m.in_evt(
        &hci::LeCisEstablished {
            status: Status::Success,
            connection_handle: 0x123,
            cig_sync_delay: 0,
            cis_sync_delay: 0,
            transport_latency_c_to_p: 0,
            transport_latency_p_to_c: 0,
            phy_c_to_p: 0x02,
            phy_p_to_c: 0x02,
            nse: 0,
            bn_c_to_p: 2,
            bn_p_to_c: 2,
            ft_c_to_p: 1,
            ft_p_to_c: 1,
            max_pdu_c_to_p: 10,
            max_pdu_p_to_c: 0,
            iso_interval: 20_000 / 1250,
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::NumberOfCompletedPackets {
            handles: vec![hci::NumberOfCompletedPacketsHandle {
                connection_handle: 0x456,
                num_completed_packets: 1,
            }],
        }
        .to_bytes(),
    );

    m.out_iso(&IsoData::new(0x123, 0, &[]).to_bytes());
    {
        let state = sink.state.lock().unwrap();
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
    }

    m.in_evt(
        &hci::DisconnectionComplete {
            status: Status::Success,
            connection_handle: 0x456,
            reason: 0,
        }
        .to_bytes(),
    );

    m.out_iso(&IsoData::new(0x456, 3, &[0x33]).to_bytes());
    m.out_iso(&IsoData::new(0x123, 1, &[0x11, 0x22]).to_bytes());
    {
        let state = sink.state.lock().unwrap();
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
    }

    {
        let state = sink.state.lock().unwrap();
        assert_eq!(state.out_cmd.len(), 2);
        assert_eq!(state.in_evt.len(), 9);
    }
}

#[test]
fn big() {
    let sink: Arc<ModuleSink> = Arc::new(ModuleSink::new());
    let m = LeAudioModule::new(sink.clone());

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::Reset(hci::ResetComplete {
                status: Status::Success,
            }),
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeReadBufferSizeV2(
                hci::LeReadBufferSizeV2Complete {
                    status: Status::Success,
                    le_acl_data_packet_length: 0,
                    total_num_le_acl_data_packets: 0,
                    iso_data_packet_length: 16,
                    total_num_iso_data_packets: 2,
                },
            ),
        }
        .to_bytes(),
    );

    m.out_cmd(
        &hci::LeCreateBig {
            big_handle: 0x10,
            advertising_handle: 0,
            num_bis: 2,
            sdu_interval: 10_000,
            max_sdu: 120,
            max_transport_latency: 0,
            rtn: 5,
            phy: 0x02,
            packing: 0,
            framing: 0,
            encryption: 0,
            broadcast_code: [0u8; 16],
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::LeCreateBigComplete {
            status: Status::Success,
            big_handle: 0x10,
            big_sync_delay: 0,
            big_transport_latency: 0,
            phy: 0x02,
            nse: 0,
            bn: 2,
            pto: 0,
            irc: 0,
            max_pdu: 10,
            iso_interval: 20_000 / 1250,
            bis_handles: vec![0x123, 0x456],
        }
        .to_bytes(),
    );

    m.out_cmd(
        &hci::LeSetupIsoDataPath {
            connection_handle: 0x123,
            data_path_direction: hci::LeDataPathDirection::Input,
            data_path_id: 0,
            codec_id: hci::LeCodecId {
                coding_format: hci::CodingFormat::Transparent,
                company_id: 0,
                vendor_id: 0,
            },
            controller_delay: 0,
            codec_configuration: vec![],
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeSetupIsoDataPath(hci::LeIsoDataPathComplete {
                status: Status::Success,
                connection_handle: 0x123,
            }),
        }
        .to_bytes(),
    );

    m.out_cmd(
        &hci::LeSetupIsoDataPath {
            connection_handle: 0x456,
            data_path_direction: hci::LeDataPathDirection::Input,
            data_path_id: 0,
            codec_id: hci::LeCodecId {
                coding_format: hci::CodingFormat::Transparent,
                company_id: 0,
                vendor_id: 0,
            },
            controller_delay: 0,
            codec_configuration: vec![],
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeSetupIsoDataPath(hci::LeIsoDataPathComplete {
                status: Status::Success,
                connection_handle: 0x456,
            }),
        }
        .to_bytes(),
    );

    m.out_iso(&IsoData::new(0x456, 0, &[0x00, 0x11]).to_bytes());
    m.out_iso(&IsoData::new(0x456, 1, &[]).to_bytes());
    {
        let state = sink.state.lock().unwrap();
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
    }

    m.in_evt(
        &hci::NumberOfCompletedPackets {
            handles: vec![hci::NumberOfCompletedPacketsHandle {
                connection_handle: 0x456,
                num_completed_packets: 2,
            }],
        }
        .to_bytes(),
    );

    m.out_iso(&IsoData::new(0x123, 0, &[0x22, 0x33]).to_bytes());
    m.out_iso(&IsoData::new(0x456, 2, &[]).to_bytes());
    {
        let state = sink.state.lock().unwrap();
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
    }

    m.in_evt(
        &hci::NumberOfCompletedPackets {
            handles: vec![
                hci::NumberOfCompletedPacketsHandle {
                    connection_handle: 0x456,
                    num_completed_packets: 1,
                },
                hci::NumberOfCompletedPacketsHandle {
                    connection_handle: 0x123,
                    num_completed_packets: 1,
                },
            ],
        }
        .to_bytes(),
    );

    m.out_iso(&IsoData::new(0x123, 1, &[0x44]).to_bytes());
    m.out_iso(&IsoData::new(0x123, 2, &[0x55, 0x66]).to_bytes());
    {
        let state = sink.state.lock().unwrap();
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
    }

    m.in_evt(
        &hci::NumberOfCompletedPackets {
            handles: vec![hci::NumberOfCompletedPacketsHandle {
                connection_handle: 0x123,
                num_completed_packets: 2,
            }],
        }
        .to_bytes(),
    );

    m.out_iso(&IsoData::new(0x123, 3, &[]).to_bytes());
    m.out_iso(&IsoData::new(0x123, 4, &[]).to_bytes());
    {
        let state = sink.state.lock().unwrap();
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
    }

    {
        let state = sink.state.lock().unwrap();
        assert_eq!(state.out_cmd.len(), 3);
        assert_eq!(state.in_evt.len(), 8);
    }
}

#[test]
fn merge() {
    let sink: Arc<ModuleSink> = Arc::new(ModuleSink::new());
    let m = LeAudioModule::new(sink.clone());

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::Reset(hci::ResetComplete {
                status: Status::Success,
            }),
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeReadBufferSizeV2(
                hci::LeReadBufferSizeV2Complete {
                    status: Status::Success,
                    le_acl_data_packet_length: 0,
                    total_num_le_acl_data_packets: 0,
                    iso_data_packet_length: 16,
                    total_num_iso_data_packets: 2,
                },
            ),
        }
        .to_bytes(),
    );

    m.out_cmd(
        &hci::LeSetCigParameters {
            cig_id: 0x01,
            sdu_interval_c_to_p: 10_000,
            sdu_interval_p_to_c: 10_000,
            worst_case_sca: 0,
            packing: 0,
            framing: 0,
            max_transport_latency_c_to_p: 0,
            max_transport_latency_p_to_c: 0,
            cis: vec![
                hci::LeCisInCigParameters {
                    cis_id: 0,
                    max_sdu_c_to_p: 120,
                    max_sdu_p_to_c: 120,
                    phy_c_to_p: 0,
                    phy_p_to_c: 0,
                    rtn_c_to_p: 0,
                    rtn_p_to_c: 0,
                },
                hci::LeCisInCigParameters {
                    cis_id: 1,
                    max_sdu_c_to_p: 150,
                    max_sdu_p_to_c: 150,
                    phy_c_to_p: 0,
                    phy_p_to_c: 0,
                    rtn_c_to_p: 0,
                    rtn_p_to_c: 0,
                },
            ],
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeSetCigParameters(
                hci::LeSetCigParametersComplete {
                    status: Status::Success,
                    cig_id: 0x01,
                    connection_handles: vec![0x123, 0x456],
                },
            ),
        }
        .to_bytes(),
    );

    // Establish CIS 0x123, using Software Offload path
    // Establish CIS 0x456, using HCI (stack) path

    m.in_evt(
        &hci::LeCisEstablished {
            status: Status::Success,
            connection_handle: 0x123,
            cig_sync_delay: 0,
            cis_sync_delay: 0,
            transport_latency_c_to_p: 0,
            transport_latency_p_to_c: 0,
            phy_c_to_p: 0x02,
            phy_p_to_c: 0x02,
            nse: 0,
            bn_c_to_p: 2,
            bn_p_to_c: 2,
            ft_c_to_p: 1,
            ft_p_to_c: 1,
            max_pdu_c_to_p: 10,
            max_pdu_p_to_c: 0,
            iso_interval: 20_000 / 1250,
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::LeCisEstablished {
            status: Status::Success,
            connection_handle: 0x456,
            cig_sync_delay: 0,
            cis_sync_delay: 0,
            transport_latency_c_to_p: 0,
            transport_latency_p_to_c: 0,
            phy_c_to_p: 0x02,
            phy_p_to_c: 0x02,
            nse: 0,
            bn_c_to_p: 2,
            bn_p_to_c: 2,
            ft_c_to_p: 1,
            ft_p_to_c: 1,
            max_pdu_c_to_p: 10,
            max_pdu_p_to_c: 0,
            iso_interval: 20_000 / 1250,
        }
        .to_bytes(),
    );

    m.out_cmd(
        &hci::LeSetupIsoDataPath {
            connection_handle: 0x123,
            data_path_direction: hci::LeDataPathDirection::Input,
            data_path_id: 0x19,
            codec_id: hci::LeCodecId {
                coding_format: hci::CodingFormat::Transparent,
                company_id: 0,
                vendor_id: 0,
            },
            controller_delay: 0,
            codec_configuration: vec![],
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeSetupIsoDataPath(hci::LeIsoDataPathComplete {
                status: Status::Success,
                connection_handle: 0x123,
            }),
        }
        .to_bytes(),
    );

    m.out_cmd(
        &hci::LeSetupIsoDataPath {
            connection_handle: 0x456,
            data_path_direction: hci::LeDataPathDirection::Input,
            data_path_id: 0,
            codec_id: hci::LeCodecId {
                coding_format: hci::CodingFormat::Transparent,
                company_id: 0,
                vendor_id: 0,
            },
            controller_delay: 0,
            codec_configuration: vec![],
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeSetupIsoDataPath(hci::LeIsoDataPathComplete {
                status: Status::Success,
                connection_handle: 0x456,
            }),
        }
        .to_bytes(),
    );

    {
        let mut state = sink.state.lock().unwrap();
        assert_eq!(state.out_cmd.len(), 3);
        assert_eq!(state.in_evt.len(), 7);
        state.out_cmd.clear();
        state.in_evt.clear();
    }

    // Send 2 Packets on 0x123
    // -> The packets are sent to the controller, and fulfill the FIFO

    m.arbiter().unwrap().push_audio(&IsoData::new(0x123, 1, &[0x44]));
    m.arbiter().unwrap().push_audio(&IsoData::new(0x123, 2, &[0x55, 0x66]));
    {
        let state = sink.state.lock().unwrap();
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
        state.out_iso.recv_timeout(Duration::from_millis(100)).expect("Receiving ISO Packet");
    }

    // Send 2 packets on 0x456
    // -> The packets are buffered, the controller FIFO is full

    m.out_iso(&IsoData::new(0x456, 1, &[0x11]).to_bytes());
    m.out_iso(&IsoData::new(0x456, 2, &[0x22, 0x33]).to_bytes());
    {
        let state = sink.state.lock().unwrap();
        assert_eq!(
            state.out_iso.recv_timeout(Duration::from_millis(100)),
            Err(mpsc::RecvTimeoutError::Timeout),
        );
    }

    // Acknowledge packet 1 on 0x123:
    // -> The acknowledgment is filtered
    // -> Packet 1 on 0x456 is tranmitted

    m.in_evt(
        &hci::NumberOfCompletedPackets {
            handles: vec![hci::NumberOfCompletedPacketsHandle {
                connection_handle: 0x123,
                num_completed_packets: 1,
            }],
        }
        .to_bytes(),
    );

    {
        let mut state = sink.state.lock().unwrap();
        assert_eq!(state.in_evt.pop(), None);
        assert_eq!(
            state.out_iso.recv_timeout(Duration::from_millis(100)),
            Ok(IsoData::new(0x456, 1, &[0x11]).to_bytes())
        );
        assert_eq!(
            state.out_iso.recv_timeout(Duration::from_millis(100)),
            Err(mpsc::RecvTimeoutError::Timeout),
        );
    }

    // Link 0x123 disconnect (implicitly ack packet 2 on 0x123)
    // -> Packet 2 on 0x456 is tranmitted

    m.in_evt(
        &hci::DisconnectionComplete {
            status: Status::Success,
            connection_handle: 0x123,
            reason: 0,
        }
        .to_bytes(),
    );

    {
        let state = sink.state.lock().unwrap();
        assert_eq!(
            state.out_iso.recv_timeout(Duration::from_millis(100)),
            Ok(IsoData::new(0x456, 2, &[0x22, 0x33]).to_bytes())
        );
    }

    // Send packets and ack packets 1 and 2 on 0x456.
    // -> Connection 0x123 is disconnected, ignored
    // -> Packets 3, and 4 on 0x456 are sent
    // -> Packet 5 on 0x456 is buffered

    m.out_iso(&IsoData::new(0x456, 3, &[0x33]).to_bytes());
    m.arbiter().unwrap().push_audio(&IsoData::new(0x123, 3, &[0x33]));
    m.arbiter().unwrap().push_audio(&IsoData::new(0x123, 4, &[0x44, 0x55]));
    m.out_iso(&IsoData::new(0x456, 4, &[0x44, 0x55]).to_bytes());
    m.out_iso(&IsoData::new(0x456, 5, &[0x55, 0x66]).to_bytes());
    {
        let state = sink.state.lock().unwrap();
        assert_eq!(
            state.out_iso.recv_timeout(Duration::from_millis(100)),
            Err(mpsc::RecvTimeoutError::Timeout),
        );
    }

    m.in_evt(
        &hci::NumberOfCompletedPackets {
            handles: vec![hci::NumberOfCompletedPacketsHandle {
                connection_handle: 0x456,
                num_completed_packets: 2,
            }],
        }
        .to_bytes(),
    );

    {
        let mut state = sink.state.lock().unwrap();
        assert_eq!(
            state.in_evt.pop(),
            Some(
                hci::NumberOfCompletedPackets {
                    handles: vec![hci::NumberOfCompletedPacketsHandle {
                        connection_handle: 0x456,
                        num_completed_packets: 2,
                    }],
                }
                .to_bytes(),
            )
        );
        assert_eq!(state.in_evt.len(), 1);
        assert_eq!(
            state.out_iso.recv_timeout(Duration::from_millis(100)),
            Ok(IsoData::new(0x456, 3, &[0x33]).to_bytes())
        );
        assert_eq!(
            state.out_iso.recv_timeout(Duration::from_millis(100)),
            Ok(IsoData::new(0x456, 4, &[0x44, 0x55]).to_bytes())
        );
        state.in_evt.clear();
    }

    // Re-establish CIS 0x123, using Software Offload path

    m.in_evt(
        &hci::LeCisEstablished {
            status: Status::Success,
            connection_handle: 0x123,
            cig_sync_delay: 0,
            cis_sync_delay: 0,
            transport_latency_c_to_p: 0,
            transport_latency_p_to_c: 0,
            phy_c_to_p: 0x02,
            phy_p_to_c: 0x02,
            nse: 0,
            bn_c_to_p: 2,
            bn_p_to_c: 2,
            ft_c_to_p: 1,
            ft_p_to_c: 1,
            max_pdu_c_to_p: 10,
            max_pdu_p_to_c: 0,
            iso_interval: 20_000 / 1250,
        }
        .to_bytes(),
    );

    m.out_cmd(
        &hci::LeSetupIsoDataPath {
            connection_handle: 0x123,
            data_path_direction: hci::LeDataPathDirection::Input,
            data_path_id: 0x19,
            codec_id: hci::LeCodecId {
                coding_format: hci::CodingFormat::Transparent,
                company_id: 0,
                vendor_id: 0,
            },
            controller_delay: 0,
            codec_configuration: vec![],
        }
        .to_bytes(),
    );

    m.in_evt(
        &hci::CommandComplete {
            num_hci_command_packets: 0,
            return_parameters: ReturnParameters::LeSetupIsoDataPath(hci::LeIsoDataPathComplete {
                status: Status::Success,
                connection_handle: 0x123,
            }),
        }
        .to_bytes(),
    );

    // Acknowledge packets 3 and 4 on 0x456
    // -> Packet 5 on 0x456 is sent

    m.in_evt(
        &hci::NumberOfCompletedPackets {
            handles: vec![hci::NumberOfCompletedPacketsHandle {
                connection_handle: 0x456,
                num_completed_packets: 2,
            }],
        }
        .to_bytes(),
    );

    {
        let mut state = sink.state.lock().unwrap();
        assert_eq!(
            state.in_evt.pop(),
            Some(
                hci::NumberOfCompletedPackets {
                    handles: vec![hci::NumberOfCompletedPacketsHandle {
                        connection_handle: 0x456,
                        num_completed_packets: 2,
                    }],
                }
                .to_bytes(),
            )
        );
        assert_eq!(
            state.out_iso.recv_timeout(Duration::from_millis(100)),
            Ok(IsoData::new(0x456, 5, &[0x55, 0x66]).to_bytes())
        );
        assert_eq!(
            state.out_iso.recv_timeout(Duration::from_millis(100)),
            Err(mpsc::RecvTimeoutError::Timeout),
        );
        state.out_cmd.clear();
        state.in_evt.clear();
    }

    // Acknowledge packet 5 on 0x456
    // -> Controller FIFO is now empty

    m.in_evt(
        &hci::NumberOfCompletedPackets {
            handles: vec![hci::NumberOfCompletedPacketsHandle {
                connection_handle: 0x456,
                num_completed_packets: 1,
            }],
        }
        .to_bytes(),
    );

    {
        let mut state = sink.state.lock().unwrap();
        assert_eq!(
            state.in_evt.pop(),
            Some(
                hci::NumberOfCompletedPackets {
                    handles: vec![hci::NumberOfCompletedPacketsHandle {
                        connection_handle: 0x456,
                        num_completed_packets: 1,
                    }],
                }
                .to_bytes(),
            )
        );
    }

    // Send 1 packet on each CIS, and acknowledge them
    // -> The CIS 0x123 is removed from "NumberOfCompletedPackets" event

    m.out_iso(&IsoData::new(0x123, 0, &[]).to_bytes());
    m.arbiter().unwrap().push_audio(&IsoData::new(0x456, 6, &[0x66, 0x77]));

    {
        let state = sink.state.lock().unwrap();
        for _ in 0..2 {
            let pkt = state.out_iso.recv_timeout(Duration::from_millis(100));
            assert!(
                pkt == Ok(IsoData::new(0x123, 0, &[]).to_bytes())
                    || pkt == Ok(IsoData::new(0x456, 6, &[0x66, 0x77]).to_bytes())
            );
        }
    }

    m.in_evt(
        &hci::NumberOfCompletedPackets {
            handles: vec![
                hci::NumberOfCompletedPacketsHandle {
                    connection_handle: 0x456,
                    num_completed_packets: 1,
                },
                hci::NumberOfCompletedPacketsHandle {
                    connection_handle: 0x123,
                    num_completed_packets: 1,
                },
            ],
        }
        .to_bytes(),
    );

    {
        let mut state = sink.state.lock().unwrap();
        assert_eq!(
            state.in_evt.pop(),
            Some(
                hci::NumberOfCompletedPackets {
                    handles: vec![hci::NumberOfCompletedPacketsHandle {
                        connection_handle: 0x456,
                        num_completed_packets: 1,
                    }],
                }
                .to_bytes(),
            )
        );
    }
}
