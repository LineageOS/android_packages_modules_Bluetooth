///! Rule group for general information.
use chrono::NaiveDateTime;
use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};
use std::convert::Into;
use std::fmt;
use std::hash::Hash;
use std::io::Write;

use crate::engine::{Rule, RuleGroup, Signal};
use crate::parser::{get_acl_content, AclContent, Packet, PacketChild};
use hcidoc_packets::hci::{
    Address, CommandChild, DisconnectReason, ErrorCode, EventChild, GapData, GapDataType,
    LeMetaEventChild,
};
use hcidoc_packets::l2cap::{ConnectionResponseResult, ControlChild};
use serde::Serialize;
use serde_json::to_writer_pretty;

/// Valid values are in the range 0x0000-0x0EFF.
type ConnectionHandle = u16;

type Psm = u16;
type Cid = u16;

const INVALID_TS: NaiveDateTime = NaiveDateTime::MAX;

fn print_timestamps_and_initiator(
    start: NaiveDateTime,
    start_initiator: InitiatorType,
    end: NaiveDateTime,
    end_initiator: InitiatorType,
) -> String {
    fn print_time_initiator(ts: NaiveDateTime, initiator: InitiatorType) -> String {
        if ts == INVALID_TS {
            return "N/A".to_owned();
        }
        return format!("{} ({})", ts.time(), initiator);
    }

    if start == end && start != INVALID_TS {
        return format!("{} ({}) - Failed", start.time(), start_initiator);
    }
    return format!(
        "{} to {}",
        print_time_initiator(start, start_initiator),
        print_time_initiator(end, end_initiator)
    );
}

#[derive(Copy, Clone, Eq, PartialEq, PartialOrd, Ord)]
enum AddressType {
    None,
    BREDR,
    LE,
    Dual,
}

impl fmt::Display for AddressType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let str = match self {
            AddressType::None => "Unknown type",
            AddressType::BREDR => "BR/EDR",
            AddressType::LE => "LE",
            AddressType::Dual => "Dual",
        };
        write!(f, "{}", str)
    }
}

impl AddressType {
    fn update(&mut self, new_type: AddressType) {
        *self = match self {
            AddressType::None => new_type,
            AddressType::Dual => AddressType::Dual,
            AddressType::BREDR => match new_type {
                AddressType::Dual | AddressType::LE => AddressType::Dual,
                _ => AddressType::BREDR,
            },
            AddressType::LE => match new_type {
                AddressType::Dual | AddressType::BREDR => AddressType::Dual,
                _ => AddressType::LE,
            },
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, Serialize)]
enum Transport {
    Unknown,
    BREDR,
    LE,
}

impl fmt::Display for Transport {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let str = match self {
            Transport::Unknown => "??",
            Transport::BREDR => "BR",
            Transport::LE => "LE",
        };
        write!(f, "{}", str)
    }
}

#[derive(Clone, Copy, PartialEq, Serialize)]
enum InitiatorType {
    Unknown,
    Host,
    Peer,
}

impl fmt::Display for InitiatorType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let str = match self {
            InitiatorType::Unknown => "by ??",
            InitiatorType::Host => "by host",
            InitiatorType::Peer => "by peer",
        };
        write!(f, "{}", str)
    }
}

#[derive(Copy, Clone)]
enum AclState {
    None,
    Initiating,
    Accepting,
    Connected,
}

impl AclState {
    fn get_connection_initiator(&self) -> InitiatorType {
        match self {
            AclState::Initiating => InitiatorType::Host,
            AclState::Accepting => InitiatorType::Peer,
            _ => InitiatorType::Unknown,
        }
    }
}

/// Information about a specific device address
struct DeviceInformation {
    names: HashSet<String>,
    address: Address,
    address_type: AddressType,
    acls: HashMap<Transport, Vec<AclInformation>>,
    acl_state: HashMap<Transport, AclState>,
}

impl DeviceInformation {
    pub fn new(address: Address) -> Self {
        DeviceInformation {
            names: HashSet::new(),
            address: address,
            address_type: AddressType::None,
            acls: HashMap::from([(Transport::BREDR, vec![]), (Transport::LE, vec![])]),
            acl_state: HashMap::from([
                (Transport::BREDR, AclState::None),
                (Transport::LE, AclState::None),
            ]),
        }
    }

    fn is_connection_active(&self, transport: Transport) -> bool {
        if transport == Transport::Unknown {
            return false;
        }

        // not empty and last connection's end time is not set.
        return !self.acls[&transport].is_empty()
            && self.acls[&transport].last().unwrap().end_time == INVALID_TS;
    }

    fn get_or_allocate_connection(
        &mut self,
        handle: ConnectionHandle,
        transport: Transport,
    ) -> &mut AclInformation {
        assert_ne!(transport, Transport::Unknown, "device allocating unknown transport");
        if !self.is_connection_active(transport) {
            let acl = AclInformation::new(handle, transport);
            self.acls.get_mut(&transport).unwrap().push(acl);
        }
        return self.acls.get_mut(&transport).unwrap().last_mut().unwrap();
    }

    fn report_connection_start(
        &mut self,
        handle: ConnectionHandle,
        transport: Transport,
        ts: NaiveDateTime,
    ) {
        if transport == Transport::Unknown {
            return;
        }

        let mut acl = AclInformation::new(handle, transport);
        let initiator = self.acl_state[&transport].get_connection_initiator();
        acl.report_start(initiator, ts);
        self.acls.get_mut(&transport).unwrap().push(acl);
        self.acl_state.insert(transport, AclState::Connected);
    }

    fn report_connection_end(
        &mut self,
        handle: ConnectionHandle,
        initiator: InitiatorType,
        ts: NaiveDateTime,
    ) {
        for transport in [Transport::BREDR, Transport::LE] {
            if self.is_connection_active(transport) {
                if self.acls[&transport].last().unwrap().handle == handle {
                    self.acls
                        .get_mut(&transport)
                        .unwrap()
                        .last_mut()
                        .unwrap()
                        .report_end(initiator, ts);
                    self.acl_state.insert(transport, AclState::None);
                    return;
                }
            }
        }

        eprintln!(
            "device {} receive disconnection of handle {} without corresponding connection at {}",
            self.address, handle, ts
        );
    }

    fn print_names(names: &HashSet<String>) -> String {
        if names.len() > 1 {
            format!("{:?}", names)
        } else {
            names.iter().next().unwrap_or(&String::from("<Unknown name>")).to_owned()
        }
    }
}

impl fmt::Display for DeviceInformation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let _ = writeln!(
            f,
            "{address} ({address_type}, {device_names}), {num_connections} connections",
            address = self.address,
            address_type = self.address_type,
            device_names = DeviceInformation::print_names(&self.names),
            num_connections = self.acls[&Transport::BREDR].len() + self.acls[&Transport::LE].len()
        );
        for acl in &self.acls[&Transport::BREDR] {
            let _ = write!(f, "{}", acl);
        }
        for acl in &self.acls[&Transport::LE] {
            let _ = write!(f, "{}", acl);
        }

        Ok(())
    }
}

#[derive(Debug)]
enum CidState {
    Pending(Psm),
    Connected(Cid, Psm),
}

/// Information for an ACL connection session
struct AclInformation {
    start_time: NaiveDateTime,
    end_time: NaiveDateTime,
    handle: ConnectionHandle,
    transport: Transport,
    start_initiator: InitiatorType,
    end_initiator: InitiatorType,
    active_profiles: HashMap<ProfileId, ProfileInformation>,
    inactive_profiles: Vec<ProfileInformation>,
    host_cids: HashMap<Cid, CidState>,
    peer_cids: HashMap<Cid, CidState>,
}

impl AclInformation {
    pub fn new(handle: ConnectionHandle, transport: Transport) -> Self {
        AclInformation {
            start_time: INVALID_TS,
            end_time: INVALID_TS,
            handle,
            transport,
            start_initiator: InitiatorType::Unknown,
            end_initiator: InitiatorType::Unknown,
            active_profiles: HashMap::new(),
            inactive_profiles: vec![],
            host_cids: HashMap::new(),
            peer_cids: HashMap::new(),
        }
    }

    fn report_start(&mut self, initiator: InitiatorType, ts: NaiveDateTime) {
        self.start_initiator = initiator;
        self.start_time = ts;
    }

    fn report_end(&mut self, initiator: InitiatorType, ts: NaiveDateTime) {
        // disconnect the active profiles
        for (_, mut profile) in self.active_profiles.drain() {
            profile.report_end(initiator, ts);
            self.inactive_profiles.push(profile);
        }
        self.end_initiator = initiator;
        self.end_time = ts;
    }

    fn report_profile_start(
        &mut self,
        profile_type: ProfileType,
        profile_id: ProfileId,
        initiator: InitiatorType,
        ts: NaiveDateTime,
    ) {
        let mut profile = ProfileInformation::new(profile_type, profile_id);
        profile.report_start(initiator, ts);
        let old_profile = self.active_profiles.insert(profile_id, profile);
        if let Some(profile) = old_profile {
            self.inactive_profiles.push(profile);
        }
    }

    fn report_profile_end(
        &mut self,
        profile_type: ProfileType,
        profile_id: ProfileId,
        initiator: InitiatorType,
        ts: NaiveDateTime,
    ) {
        let mut profile = self
            .active_profiles
            .remove(&profile_id)
            .unwrap_or(ProfileInformation::new(profile_type, profile_id));
        profile.report_end(initiator, ts);
        self.inactive_profiles.push(profile);
    }

    fn report_l2cap_conn_req(
        &mut self,
        psm: Psm,
        cid: Cid,
        initiator: InitiatorType,
        _ts: NaiveDateTime,
    ) {
        if initiator == InitiatorType::Host {
            self.host_cids.insert(cid, CidState::Pending(psm));
        } else if initiator == InitiatorType::Peer {
            self.peer_cids.insert(cid, CidState::Pending(psm));
        }
    }

    // For pending connections, we report whether the PSM successfully connected and
    // store the profile as started at this time.
    fn report_l2cap_conn_rsp(
        &mut self,
        status: ConnectionResponseResult,
        cid_info: CidInformation,
        initiator: InitiatorType,
        ts: NaiveDateTime,
    ) {
        let host_cid = cid_info.host_cid;
        let peer_cid = cid_info.peer_cid;
        let cid_state_option = match initiator {
            InitiatorType::Host => self.host_cids.get(&host_cid),
            InitiatorType::Peer => self.peer_cids.get(&peer_cid),
            _ => None,
        };

        let psm_option = match cid_state_option {
            Some(cid_state) => match cid_state {
                CidState::Pending(psm) => Some(*psm),
                _ => None,
            },
            None => None,
        };

        if let Some(psm) = psm_option {
            let profile_option = ProfileType::from_psm(psm);
            let profile_id = ProfileId::L2capCid(cid_info);
            if status == ConnectionResponseResult::Success {
                self.host_cids.insert(host_cid, CidState::Connected(peer_cid, psm));
                self.peer_cids.insert(peer_cid, CidState::Connected(host_cid, psm));
                if let Some(profile) = profile_option {
                    self.report_profile_start(profile, profile_id, initiator, ts);
                }
            } else {
                // On failure, report start and end on the same time.
                if let Some(profile) = profile_option {
                    self.report_profile_start(profile, profile_id, initiator, ts);
                    self.report_profile_end(profile, profile_id, initiator, ts);
                }
            }
        } // TODO: debug on the else case.
    }

    // L2cap disconnected so report profile connection closed if we were tracking it.
    fn report_l2cap_disconn_rsp(
        &mut self,
        cid_info: CidInformation,
        initiator: InitiatorType,
        ts: NaiveDateTime,
    ) {
        let host_cid = cid_info.host_cid;
        let host_cid_state_option = self.host_cids.remove(&host_cid);
        let host_psm = match host_cid_state_option {
            Some(cid_state) => match cid_state {
                // TODO: assert that the peer cids match.
                CidState::Connected(_peer_cid, psm) => Some(psm),
                _ => None, // TODO: assert that state is connected.
            },
            None => None,
        };

        let peer_cid = cid_info.peer_cid;
        let peer_cid_state_option = self.peer_cids.remove(&peer_cid);
        let peer_psm = match peer_cid_state_option {
            Some(cid_state) => match cid_state {
                // TODO: assert that the host cids match.
                CidState::Connected(_host_cid, psm) => Some(psm),
                _ => None, // TODO: assert that state is connected.
            },
            None => None,
        };

        if host_psm != peer_psm {
            eprintln!(
                "psm for host and peer mismatches at l2cap disc for handle {} at {}",
                self.handle, ts
            );
        }
        let psm = match host_psm.or(peer_psm) {
            Some(psm) => psm,
            None => return, // No recorded PSM, no need to report.
        };

        let profile_option = ProfileType::from_psm(psm);
        if let Some(profile) = profile_option {
            let profile_id = ProfileId::L2capCid(cid_info);
            self.report_profile_end(profile, profile_id, initiator, ts)
        }
    }
}

impl fmt::Display for AclInformation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let _ = writeln!(
            f,
            "  Handle: {handle} ({transport}), {timestamp_initiator_info}",
            transport = self.transport,
            handle = self.handle,
            timestamp_initiator_info = print_timestamps_and_initiator(
                self.start_time,
                self.start_initiator,
                self.end_time,
                self.end_initiator
            ),
        );

        for profile in self.inactive_profiles.iter() {
            let _ = write!(f, "{}", profile);
        }
        for (_, profile) in self.active_profiles.iter() {
            let _ = write!(f, "{}", profile);
        }

        Ok(())
    }
}

#[derive(Copy, Clone, Eq, PartialEq, Hash, Serialize)]
enum ProfileType {
    Att,
    Avctp,
    Avdtp,
    Eatt,
    Hfp,
    HidCtrl,
    HidIntr,
    Rfcomm,
    Sdp,
}

impl fmt::Display for ProfileType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let str = match self {
            ProfileType::Att => "ATT",
            ProfileType::Avctp => "AVCTP",
            ProfileType::Avdtp => "AVDTP",
            ProfileType::Eatt => "EATT",
            ProfileType::Hfp => "HFP",
            ProfileType::HidCtrl => "HID CTRL",
            ProfileType::HidIntr => "HID INTR",
            ProfileType::Rfcomm => "RFCOMM",
            ProfileType::Sdp => "SDP",
        };
        write!(f, "{}", str)
    }
}

impl ProfileType {
    fn from_psm(psm: Psm) -> Option<Self> {
        match psm {
            1 => Some(ProfileType::Sdp),
            3 => Some(ProfileType::Rfcomm),
            17 => Some(ProfileType::HidCtrl),
            19 => Some(ProfileType::HidIntr),
            23 => Some(ProfileType::Avctp),
            25 => Some(ProfileType::Avdtp),
            31 => Some(ProfileType::Att),
            39 => Some(ProfileType::Eatt),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Eq, Hash, PartialEq)]
struct CidInformation {
    host_cid: Cid,
    peer_cid: Cid,
}

// Use to distinguish between the same profiles within one ACL connection.
// Later we can add RFCOMM's DLCI, for example.
// This is used as the key of the map of active profiles in AclInformation.
#[derive(Clone, Copy, Eq, Hash, PartialEq)]
enum ProfileId {
    OnePerConnection(ProfileType),
    L2capCid(CidInformation),
}

impl fmt::Display for ProfileId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let str = match self {
            ProfileId::OnePerConnection(_) => "".to_string(),
            ProfileId::L2capCid(cid_info) => {
                format!("(CID: host={}, peer={})", cid_info.host_cid, cid_info.peer_cid)
            }
        };
        write!(f, "{}", str)
    }
}

struct ProfileInformation {
    start_time: NaiveDateTime,
    end_time: NaiveDateTime,
    profile_type: ProfileType,
    start_initiator: InitiatorType,
    end_initiator: InitiatorType,
    profile_id: ProfileId,
}

impl ProfileInformation {
    pub fn new(profile_type: ProfileType, profile_id: ProfileId) -> Self {
        ProfileInformation {
            start_time: INVALID_TS,
            end_time: INVALID_TS,
            profile_type: profile_type,
            start_initiator: InitiatorType::Unknown,
            end_initiator: InitiatorType::Unknown,
            profile_id: profile_id,
        }
    }

    fn report_start(&mut self, initiator: InitiatorType, ts: NaiveDateTime) {
        self.start_initiator = initiator;
        self.start_time = ts;
    }

    fn report_end(&mut self, initiator: InitiatorType, ts: NaiveDateTime) {
        self.end_initiator = initiator;
        self.end_time = ts;
    }
}

impl fmt::Display for ProfileInformation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(
            f,
            "    {profile}, {timestamp_initiator_info} {profile_id}",
            profile = self.profile_type,
            timestamp_initiator_info = print_timestamps_and_initiator(
                self.start_time,
                self.start_initiator,
                self.end_time,
                self.end_initiator
            ),
            profile_id = self.profile_id,
        )
    }
}

#[derive(Serialize)]
struct JsonReport {
    devices_connections: HashMap<String, Vec<JsonDeviceAclInfo>>,
}

impl From<&HashMap<Address, DeviceInformation>> for JsonReport {
    fn from(devices_info: &HashMap<Address, DeviceInformation>) -> Self {
        JsonReport {
            devices_connections: devices_info
                .iter()
                .map(|(address, info)| {
                    let device_acls =
                        info.acls.values().flatten().map(JsonDeviceAclInfo::from).collect();
                    (address.to_string(), device_acls)
                })
                .collect(),
        }
    }
}

#[derive(Serialize)]
struct JsonDeviceAclInfo {
    transport: Transport,
    start_time: String,
    end_time: Option<String>,
    initiator: InitiatorType,
    end_initiator: InitiatorType,
    profile_connections: HashMap<ProfileType, Vec<JsonDeviceProfileInfo>>,
}

impl From<&AclInformation> for JsonDeviceAclInfo {
    fn from(acl: &AclInformation) -> Self {
        // This closure now groups profiles into the HashMap.
        let profiles_map = acl
            .active_profiles
            .values()
            .chain(acl.inactive_profiles.iter())
            .map(JsonDeviceProfileInfo::from)
            .fold(
                HashMap::new(),
                |mut acc: HashMap<ProfileType, Vec<JsonDeviceProfileInfo>>, profile_info| {
                    acc.entry(profile_info.profile).or_default().push(profile_info);
                    acc
                },
            );

        JsonDeviceAclInfo {
            transport: acl.transport,
            start_time: acl.start_time.to_string(),
            end_time: (acl.end_time != INVALID_TS).then(|| acl.end_time.to_string()),
            initiator: acl.start_initiator,
            end_initiator: acl.end_initiator,
            profile_connections: profiles_map,
        }
    }
}

#[derive(Serialize)]
struct JsonDeviceProfileInfo {
    profile: ProfileType,
    start_time: String,
    end_time: Option<String>,
    initiator: InitiatorType,
    end_initiator: InitiatorType,
}

impl From<&ProfileInformation> for JsonDeviceProfileInfo {
    fn from(profile: &ProfileInformation) -> Self {
        JsonDeviceProfileInfo {
            profile: profile.profile_type,
            start_time: profile.start_time.to_string(),
            end_time: (profile.end_time != INVALID_TS).then(|| profile.end_time.to_string()),
            initiator: profile.start_initiator,
            end_initiator: profile.end_initiator,
        }
    }
}

/// This rule prints devices names and connection/disconnection time.
struct InformationalRule {
    devices: HashMap<Address, DeviceInformation>,
    handles: HashMap<ConnectionHandle, Address>,
    sco_handles: HashMap<ConnectionHandle, ConnectionHandle>,
    /// unknownConnections store connections which is initiated before btsnoop starts.
    unknown_connections: HashMap<ConnectionHandle, AclInformation>,
    /// Store the pending disconnection so we can retrieve who initiates it upon report.
    /// This needs its own map instead of reusing the AclState, because that requires us to have the
    /// address of the peer device, but on disconnection we are given only the handle - the address
    /// might be unknown, or clash in case of a SCO connection.
    /// Also, when powering off, the controller might or might not reply the disconnection request.
    /// Therefore also store this information so we can correctly handle both scenario.
    pending_disconnections: HashMap<ConnectionHandle, bool>, // is powering off?
}

impl InformationalRule {
    pub fn new() -> Self {
        InformationalRule {
            devices: HashMap::new(),
            handles: HashMap::new(),
            sco_handles: HashMap::new(),
            unknown_connections: HashMap::new(),
            pending_disconnections: HashMap::new(),
        }
    }

    fn get_or_allocate_device(&mut self, address: &Address) -> &mut DeviceInformation {
        if !self.devices.contains_key(address) {
            self.devices.insert(*address, DeviceInformation::new(*address));
        }
        return self.devices.get_mut(address).unwrap();
    }

    fn get_or_allocate_unknown_connection(
        &mut self,
        handle: ConnectionHandle,
        transport: Transport,
    ) -> &mut AclInformation {
        if !self.unknown_connections.contains_key(&handle) {
            self.unknown_connections.insert(handle, AclInformation::new(handle, transport));
        }
        return self.unknown_connections.get_mut(&handle).unwrap();
    }

    fn get_or_allocate_connection(
        &mut self,
        handle: ConnectionHandle,
        transport: Transport,
    ) -> &mut AclInformation {
        if !self.handles.contains_key(&handle) || transport == Transport::Unknown {
            let conn = self.get_or_allocate_unknown_connection(handle, transport);
            return conn;
        }

        let address = &self.handles.get(&handle).unwrap().clone();
        let device = self.get_or_allocate_device(address);
        return device.get_or_allocate_connection(handle, transport);
    }

    fn report_address_type(&mut self, address: &Address, address_type: AddressType) {
        let device = self.get_or_allocate_device(address);
        device.address_type.update(address_type);
    }

    fn report_name(&mut self, address: &Address, name: &String) {
        let device = self.get_or_allocate_device(address);
        device.names.insert(name.into());
    }

    fn report_acl_state(&mut self, address: &Address, transport: Transport, state: AclState) {
        let device = self.get_or_allocate_device(address);
        device.acl_state.insert(transport, state);
    }

    fn report_connection_start(
        &mut self,
        address: &Address,
        handle: ConnectionHandle,
        transport: Transport,
        ts: NaiveDateTime,
    ) {
        let device = self.get_or_allocate_device(address);
        device.report_connection_start(handle, transport, ts);
        self.handles.insert(handle, *address);
        self.pending_disconnections.remove(&handle);
    }

    fn report_sco_connection_start(
        &mut self,
        address: &Address,
        handle: ConnectionHandle,
        ts: NaiveDateTime,
    ) {
        if !self.devices.contains_key(address) {
            // To simplify things, let's not process unknown devices
            return;
        }

        let device = self.devices.get_mut(address).unwrap();
        if !device.is_connection_active(Transport::BREDR) {
            // SCO is connected, but ACL is not. This is weird, but let's ignore for simplicity.
            eprintln!("[{}] SCO is connected, but ACL is not.", address);
            return;
        }

        // Whatever handle value works here - we aren't allocating a new one.
        let acl = device.get_or_allocate_connection(0, Transport::BREDR);
        let acl_handle = acl.handle;
        // We need to listen the HCI commands to determine the correct initiator.
        // Here we just assume host for simplicity.
        acl.report_profile_start(
            ProfileType::Hfp,
            ProfileId::OnePerConnection(ProfileType::Hfp),
            InitiatorType::Host,
            ts,
        );

        self.sco_handles.insert(handle, acl_handle);
    }

    fn report_connection_end(&mut self, handle: ConnectionHandle, ts: NaiveDateTime) {
        let initiator = match self.pending_disconnections.contains_key(&handle) {
            true => InitiatorType::Host,
            false => InitiatorType::Peer,
        };

        // This might be a SCO disconnection event, so check that first
        if self.sco_handles.contains_key(&handle) {
            let acl_handle = self.sco_handles[&handle];
            let conn = self.get_or_allocate_connection(acl_handle, Transport::BREDR);
            // in case of HFP failure, the initiator here would be set to peer, which is incorrect,
            // but when printing we detect by the timestamp that it was a failure anyway.
            conn.report_profile_end(
                ProfileType::Hfp,
                ProfileId::OnePerConnection(ProfileType::Hfp),
                initiator,
                ts,
            );
            return;
        }

        // Not recognized as SCO, assume it's an ACL handle.
        if let Some(address) = self.handles.get(&handle) {
            // This device is known
            let device: &mut DeviceInformation = self.devices.get_mut(address).unwrap();
            device.report_connection_end(handle, initiator, ts);
            self.handles.remove(&handle);

            // remove the associated SCO handle, if any
            self.sco_handles.retain(|_sco_handle, acl_handle| *acl_handle != handle);
        } else {
            // Unknown device.
            let conn = self.get_or_allocate_unknown_connection(handle, Transport::Unknown);
            conn.report_end(initiator, ts);
        }
    }

    fn report_reset(&mut self, ts: NaiveDateTime) {
        // report_connection_end removes the entries from the map, so store all the keys first.
        let handles: Vec<ConnectionHandle> = self.handles.keys().cloned().collect();
        for handle in handles {
            self.report_connection_end(handle, ts);
        }
        self.sco_handles.clear();
        self.pending_disconnections.clear();
    }

    fn process_gap_data(&mut self, address: &Address, data: &GapData) {
        match data.data_type {
            GapDataType::CompleteLocalName | GapDataType::ShortenedLocalName => {
                let name = String::from_utf8_lossy(data.data.as_slice()).into_owned();
                self.report_name(address, &name);
            }

            _ => {}
        }
    }

    fn process_raw_gap_data(&mut self, address: &Address, data: &[u8]) {
        let mut offset = 0;
        while offset < data.len() {
            match GapData::parse(&data[offset..]) {
                Ok(gap_data) => {
                    self.process_gap_data(&address, &gap_data);
                    // advance data len + 2 (size = 1, type = 1)
                    offset += gap_data.data.len() + 2;
                }
                Err(err) => {
                    eprintln!("[{}] GAP data is not parsed correctly: {}", address, err);
                    break;
                }
            }
            if offset >= data.len() {
                break;
            }
        }
    }

    fn report_l2cap_conn_req(
        &mut self,
        handle: ConnectionHandle,
        psm: Psm,
        cid: Cid,
        initiator: InitiatorType,
        ts: NaiveDateTime,
    ) {
        let conn = self.get_or_allocate_connection(handle, Transport::BREDR);
        conn.report_l2cap_conn_req(psm, cid, initiator, ts);
    }

    fn report_l2cap_conn_rsp(
        &mut self,
        handle: ConnectionHandle,
        status: ConnectionResponseResult,
        host_cid: Cid,
        peer_cid: Cid,
        initiator: InitiatorType,
        ts: NaiveDateTime,
    ) {
        if status == ConnectionResponseResult::Pending {
            return;
        }
        let conn = self.get_or_allocate_connection(handle, Transport::BREDR);
        let cid_info = CidInformation { host_cid, peer_cid };
        conn.report_l2cap_conn_rsp(status, cid_info, initiator, ts);
    }

    fn report_l2cap_disconn_rsp(
        &mut self,
        handle: ConnectionHandle,
        host_cid: Cid,
        peer_cid: Cid,
        initiator: InitiatorType,
        ts: NaiveDateTime,
    ) {
        let conn = self.get_or_allocate_connection(handle, Transport::BREDR);
        let cid_info = CidInformation { host_cid, peer_cid };
        conn.report_l2cap_disconn_rsp(cid_info, initiator, ts);
    }
}

impl Rule for InformationalRule {
    fn process(&mut self, packet: &Packet) {
        match &packet.inner {
            PacketChild::HciEvent(ev) => match ev.specialize() {
                EventChild::ConnectionComplete(ev) => {
                    self.report_connection_start(
                        &ev.get_bd_addr(),
                        ev.get_connection_handle(),
                        Transport::BREDR,
                        packet.ts,
                    );

                    // If failed, assume it's the end of connection.
                    if ev.get_status() != ErrorCode::Success {
                        self.report_connection_end(ev.get_connection_handle(), packet.ts);
                    }
                }

                EventChild::SynchronousConnectionComplete(ev) => {
                    self.report_sco_connection_start(
                        &ev.get_bd_addr(),
                        ev.get_connection_handle(),
                        packet.ts,
                    );
                    // If failed, assume it's the end of connection.
                    if ev.get_status() != ErrorCode::Success {
                        self.report_connection_end(ev.get_connection_handle(), packet.ts);
                    }
                }

                EventChild::DisconnectionComplete(ev) => {
                    // If disconnected because host is powering off, the event has been processed.
                    // We can't just query the reason here because it's different across vendors.
                    let handle = ev.get_connection_handle();
                    if !self.pending_disconnections.get(&handle).unwrap_or(&false) {
                        self.report_connection_end(handle, packet.ts);
                    }
                    self.pending_disconnections.remove(&handle);
                }

                EventChild::ExtendedInquiryResult(ev) => {
                    self.process_raw_gap_data(
                        &ev.get_address(),
                        ev.get_extended_inquiry_response(),
                    );
                    self.report_address_type(&ev.get_address(), AddressType::BREDR);
                }

                EventChild::RemoteNameRequestComplete(ev) => {
                    if ev.get_status() != ErrorCode::Success {
                        return;
                    }
                    let name = String::from_utf8_lossy(ev.get_remote_name());
                    let name = name.trim_end_matches(char::from(0));
                    self.report_name(&ev.get_bd_addr(), &name.to_owned());
                    self.report_address_type(&ev.get_bd_addr(), AddressType::BREDR);
                }

                EventChild::LeMetaEvent(ev) => match ev.specialize() {
                    LeMetaEventChild::LeConnectionComplete(ev) => {
                        if ev.get_status() != ErrorCode::Success {
                            return;
                        }

                        // Determining LE initiator is complex, for simplicity assume host inits.
                        self.report_acl_state(
                            &ev.get_peer_address(),
                            Transport::LE,
                            AclState::Initiating,
                        );
                        self.report_connection_start(
                            &ev.get_peer_address(),
                            ev.get_connection_handle(),
                            Transport::LE,
                            packet.ts,
                        );
                        self.report_address_type(&ev.get_peer_address(), AddressType::LE);
                    }

                    LeMetaEventChild::LeEnhancedConnectionCompleteV1(ev) => {
                        if ev.get_status() != ErrorCode::Success {
                            return;
                        }

                        // Determining LE initiator is complex, for simplicity assume host inits.
                        self.report_acl_state(
                            &ev.get_peer_address(),
                            Transport::LE,
                            AclState::Initiating,
                        );
                        self.report_connection_start(
                            &ev.get_peer_address(),
                            ev.get_connection_handle(),
                            Transport::LE,
                            packet.ts,
                        );
                        self.report_address_type(&ev.get_peer_address(), AddressType::LE);
                    }

                    LeMetaEventChild::LeEnhancedConnectionCompleteV2(ev) => {
                        if ev.get_status() != ErrorCode::Success {
                            return;
                        }

                        // Determining LE initiator is complex, for simplicity assume host inits.
                        self.report_acl_state(
                            &ev.get_peer_address(),
                            Transport::LE,
                            AclState::Initiating,
                        );
                        self.report_connection_start(
                            &ev.get_peer_address(),
                            ev.get_connection_handle(),
                            Transport::LE,
                            packet.ts,
                        );
                        self.report_address_type(&ev.get_peer_address(), AddressType::LE);
                    }

                    LeMetaEventChild::LeAdvertisingReport(ev) => {
                        for resp in ev.get_responses() {
                            self.process_raw_gap_data(&resp.address, &resp.advertising_data);
                            self.report_address_type(&resp.address, AddressType::LE);
                        }
                    }

                    LeMetaEventChild::LeExtendedAdvertisingReport(ev) => {
                        for resp in ev.get_responses() {
                            self.process_raw_gap_data(&resp.address, &resp.advertising_data);
                            self.report_address_type(&resp.address, AddressType::LE);
                        }
                    }

                    // EventChild::LeMetaEvent(ev).specialize()
                    _ => {}
                },

                // PacketChild::HciEvent(ev) => match ev.specialize()
                _ => {}
            },

            PacketChild::HciCommand(cmd) => match cmd.specialize() {
                CommandChild::Reset(_cmd) => {
                    self.report_reset(packet.ts);
                }
                CommandChild::CreateConnection(cmd) => {
                    self.report_acl_state(
                        &cmd.get_bd_addr(),
                        Transport::BREDR,
                        AclState::Initiating,
                    );
                    self.report_address_type(&cmd.get_bd_addr(), AddressType::BREDR);
                }
                CommandChild::AcceptConnectionRequest(cmd) => {
                    self.report_acl_state(
                        &cmd.get_bd_addr(),
                        Transport::BREDR,
                        AclState::Accepting,
                    );
                    self.report_address_type(&cmd.get_bd_addr(), AddressType::BREDR);
                }
                CommandChild::Disconnect(cmd) => {
                    // If reason is power off, the host might not wait for connection complete event
                    let is_power_off = cmd.get_reason()
                        == DisconnectReason::RemoteDeviceTerminatedConnectionPowerOff;
                    let handle = cmd.get_connection_handle();
                    self.pending_disconnections.insert(handle, is_power_off);
                    if is_power_off {
                        self.report_connection_end(handle, packet.ts);
                    }
                }

                // PacketChild::HciCommand(cmd).specialize()
                _ => {}
            },

            PacketChild::AclTx(tx) => {
                let content = get_acl_content(tx);
                match content {
                    AclContent::Control(control) => match control.specialize() {
                        ControlChild::ConnectionRequest(creq) => {
                            self.report_l2cap_conn_req(
                                tx.get_handle(),
                                creq.get_psm(),
                                creq.get_source_cid(),
                                InitiatorType::Host,
                                packet.ts,
                            );
                        }
                        ControlChild::ConnectionResponse(crsp) => {
                            self.report_l2cap_conn_rsp(
                                tx.get_handle(),
                                crsp.get_result(),
                                crsp.get_destination_cid(),
                                crsp.get_source_cid(),
                                InitiatorType::Peer,
                                packet.ts,
                            );
                        }
                        ControlChild::DisconnectionResponse(drsp) => {
                            self.report_l2cap_disconn_rsp(
                                tx.get_handle(),
                                drsp.get_destination_cid(),
                                drsp.get_source_cid(),
                                InitiatorType::Peer,
                                packet.ts,
                            );
                        }

                        // AclContent::Control.specialize()
                        _ => {}
                    },

                    // PacketChild::AclTx(tx).specialize()
                    _ => {}
                }
            }

            PacketChild::AclRx(rx) => {
                let content = get_acl_content(rx);
                match content {
                    AclContent::Control(control) => match control.specialize() {
                        ControlChild::ConnectionRequest(creq) => {
                            self.report_l2cap_conn_req(
                                rx.get_handle(),
                                creq.get_psm(),
                                creq.get_source_cid(),
                                InitiatorType::Peer,
                                packet.ts,
                            );
                        }
                        ControlChild::ConnectionResponse(crsp) => {
                            self.report_l2cap_conn_rsp(
                                rx.get_handle(),
                                crsp.get_result(),
                                crsp.get_source_cid(),
                                crsp.get_destination_cid(),
                                InitiatorType::Host,
                                packet.ts,
                            );
                        }
                        ControlChild::DisconnectionResponse(drsp) => {
                            self.report_l2cap_disconn_rsp(
                                rx.get_handle(),
                                drsp.get_source_cid(),
                                drsp.get_destination_cid(),
                                InitiatorType::Host,
                                packet.ts,
                            );
                        }

                        // AclContent::Control.specialize()
                        _ => {}
                    },

                    // PacketChild::AclRx(rx).specialize()
                    _ => {}
                }
            }

            // End packet.inner match
            _ => (),
        }
    }

    fn report(&self, writer: &mut dyn Write) {
        /* Sort when displaying the addresses, from the most to the least important:
         * (1) Device with connections > Device without connections
         * (2) Device with known name > Device with unknown name
         * (3) BREDR > LE > Dual
         * (4) Name, lexicographically (case sensitive)
         * (5) Address, alphabetically
         */
        fn sort_addresses(a: &DeviceInformation, b: &DeviceInformation) -> Ordering {
            let a_empty = a.acls[&Transport::BREDR].is_empty() && a.acls[&Transport::LE].is_empty();
            let b_empty = b.acls[&Transport::BREDR].is_empty() && b.acls[&Transport::LE].is_empty();
            let connection_order = a_empty.cmp(&b_empty);
            if connection_order != Ordering::Equal {
                return connection_order;
            }

            let known_name_order = a.names.is_empty().cmp(&b.names.is_empty());
            if known_name_order != Ordering::Equal {
                return known_name_order;
            }

            let address_type_order = a.address_type.cmp(&b.address_type);
            if address_type_order != Ordering::Equal {
                return address_type_order;
            }

            let a_name = format!("{}", DeviceInformation::print_names(&a.names));
            let b_name = format!("{}", DeviceInformation::print_names(&b.names));
            let name_order = a_name.cmp(&b_name);
            if name_order != Ordering::Equal {
                return name_order;
            }

            let a_address = <[u8; 6]>::from(a.address);
            let b_address = <[u8; 6]>::from(b.address);
            for i in (0..6).rev() {
                let address_order = a_address[i].cmp(&b_address[i]);
                if address_order != Ordering::Equal {
                    return address_order;
                }
            }
            // This shouldn't be executed
            return Ordering::Equal;
        }

        if self.devices.is_empty() && self.unknown_connections.is_empty() {
            return;
        }

        let mut addresses: Vec<Address> = self.devices.keys().cloned().collect();
        addresses.sort_unstable_by(|a, b| sort_addresses(&self.devices[a], &self.devices[b]));

        let _ = writeln!(writer, "InformationalRule report:");
        if !self.unknown_connections.is_empty() {
            let _ = writeln!(
                writer,
                "Connections initiated before snoop start, {} connections",
                self.unknown_connections.len()
            );
            for (_, acl) in &self.unknown_connections {
                let _ = write!(writer, "{}", acl);
            }
        }
        for address in addresses {
            let _ = write!(writer, "{}", self.devices[&address]);
        }
    }

    fn report_signals(&self) -> &[Signal] {
        &[]
    }

    fn output_json(&self, writer: &mut dyn Write) {
        let json_report = JsonReport::from(&self.devices);
        to_writer_pretty(writer, &json_report).expect("Failed to write JSON");
    }
}

/// Get a rule group with collision rules.
pub fn get_informational_group() -> RuleGroup {
    let mut group = RuleGroup::new();
    group.add_rule(Box::new(InformationalRule::new()));

    group
}
