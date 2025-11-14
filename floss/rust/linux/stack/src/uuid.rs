//! Collection of Profile UUIDs and helpers to use them.

use num_derive::{FromPrimitive, ToPrimitive};
use std::collections::{HashMap, HashSet};
use std::fmt::{Debug, Display, Formatter};
use std::sync::LazyLock;

use bt_topshim::btif::Uuid;

// List of profile uuids
pub const A2DP_SINK: &str = "0000110B-0000-1000-8000-00805F9B34FB";
pub const A2DP_SOURCE: &str = "0000110A-0000-1000-8000-00805F9B34FB";
pub const ADV_AUDIO_DIST: &str = "0000110D-0000-1000-8000-00805F9B34FB";
pub const BAS: &str = "0000180F-0000-1000-8000-00805F9B34FB";
pub const DIS: &str = "0000180A-0000-1000-8000-00805F9B34FB";
pub const HSP: &str = "00001108-0000-1000-8000-00805F9B34FB";
pub const HSP_AG: &str = "00001112-0000-1000-8000-00805F9B34FB";
pub const HFP: &str = "0000111E-0000-1000-8000-00805F9B34FB";
pub const HFP_AG: &str = "0000111F-0000-1000-8000-00805F9B34FB";
pub const AVRCP_CONTROLLER: &str = "0000110E-0000-1000-8000-00805F9B34FB";
pub const AVRCP_TARGET: &str = "0000110C-0000-1000-8000-00805F9B34FB";
pub const OBEX_OBJECT_PUSH: &str = "00001105-0000-1000-8000-00805f9b34fb";
pub const HID: &str = "00001124-0000-1000-8000-00805f9b34fb";
pub const HOGP: &str = "00001812-0000-1000-8000-00805f9b34fb";
pub const PANU: &str = "00001115-0000-1000-8000-00805F9B34FB";
pub const NAP: &str = "00001116-0000-1000-8000-00805F9B34FB";
pub const BNEP: &str = "0000000f-0000-1000-8000-00805F9B34FB";
pub const PBAP_PCE: &str = "0000112e-0000-1000-8000-00805F9B34FB";
pub const PBAP_PSE: &str = "0000112f-0000-1000-8000-00805F9B34FB";
pub const MAP: &str = "00001134-0000-1000-8000-00805F9B34FB";
pub const MNS: &str = "00001133-0000-1000-8000-00805F9B34FB";
pub const MAS: &str = "00001132-0000-1000-8000-00805F9B34FB";
pub const SAP: &str = "0000112D-0000-1000-8000-00805F9B34FB";
pub const HEARING_AID: &str = "0000FDF0-0000-1000-8000-00805f9b34fb";
pub const LE_AUDIO: &str = "0000184E-0000-1000-8000-00805F9B34FB";
pub const DIP: &str = "00001200-0000-1000-8000-00805F9B34FB";
pub const VOLUME_CONTROL: &str = "00001844-0000-1000-8000-00805F9B34FB";
pub const GENERIC_MEDIA_CONTROL: &str = "00001849-0000-1000-8000-00805F9B34FB";
pub const MEDIA_CONTROL: &str = "00001848-0000-1000-8000-00805F9B34FB";
pub const COORDINATED_SET: &str = "00001846-0000-1000-8000-00805F9B34FB";
pub const BASE_UUID: &str = "00000000-0000-1000-8000-00805F9B34FB";

// List of descriptor uuids
pub const CCCD_UUID: &str = "00002902-0000-1000-8000-00805f9b34fb";

/// List of profiles that with known uuids.
/// Append new profiles to the end of the enum. Do not insert it in the middle.
#[derive(Clone, Debug, Hash, PartialEq, PartialOrd, Eq, Ord, FromPrimitive, ToPrimitive, Copy)]
#[repr(u32)]
pub enum Profile {
    A2dpSink,
    A2dpSource,
    AdvAudioDist,
    Bas,
    Dis,
    Hsp,
    HspAg,
    Hfp,
    HfpAg,
    AvrcpController,
    AvrcpTarget,
    ObexObjectPush,
    Hid,
    Hogp,
    Panu,
    Nap,
    Bnep,
    PbapPce,
    PbapPse,
    Map,
    Mns,
    Mas,
    Sap,
    HearingAid,
    LeAudio,
    Dip,
    VolumeControl,
    GenericMediaControl,
    MediaControl,
    CoordinatedSet,
}

impl Display for Profile {
    fn fmt(&self, f: &mut Formatter) -> std::fmt::Result {
        Debug::fmt(self, f)
    }
}

pub struct UuidHelper {}

// AVRCP fights with A2DP when initializing, so let's initiate profiles in a known good order.
// Specifically, A2DP must be initialized before AVRCP.
// TODO (b/286991526): remove after issue is resolved
static ORDERED_SUPPORTED_PROFILES: LazyLock<Vec<Profile>> = LazyLock::new(|| {
    vec![
        Profile::A2dpSink,
        Profile::A2dpSource,
        Profile::AvrcpController,
        Profile::AvrcpTarget,
        Profile::Bas,
        Profile::Hsp,
        Profile::Hfp,
        Profile::Hid,
        Profile::Hogp,
        Profile::LeAudio,
        Profile::Panu,
        Profile::PbapPce,
        Profile::Map,
        Profile::HearingAid,
        Profile::VolumeControl,
        Profile::CoordinatedSet,
    ]
});

static SUPPORTED_PROFILES: LazyLock<HashSet<Profile>> =
    LazyLock::new(|| ORDERED_SUPPORTED_PROFILES.iter().cloned().collect());

static PROFILES: LazyLock<HashMap<Uuid, Profile>> = LazyLock::new(|| {
    [
        (Uuid::from_string(A2DP_SINK).unwrap(), Profile::A2dpSink),
        (Uuid::from_string(A2DP_SOURCE).unwrap(), Profile::A2dpSource),
        (Uuid::from_string(ADV_AUDIO_DIST).unwrap(), Profile::AdvAudioDist),
        (Uuid::from_string(BAS).unwrap(), Profile::Bas),
        (Uuid::from_string(DIS).unwrap(), Profile::Dis),
        (Uuid::from_string(HSP).unwrap(), Profile::Hsp),
        (Uuid::from_string(HSP_AG).unwrap(), Profile::HspAg),
        (Uuid::from_string(HFP).unwrap(), Profile::Hfp),
        (Uuid::from_string(HFP_AG).unwrap(), Profile::HfpAg),
        (Uuid::from_string(AVRCP_CONTROLLER).unwrap(), Profile::AvrcpController),
        (Uuid::from_string(AVRCP_TARGET).unwrap(), Profile::AvrcpTarget),
        (Uuid::from_string(OBEX_OBJECT_PUSH).unwrap(), Profile::ObexObjectPush),
        (Uuid::from_string(HID).unwrap(), Profile::Hid),
        (Uuid::from_string(HOGP).unwrap(), Profile::Hogp),
        (Uuid::from_string(PANU).unwrap(), Profile::Panu),
        (Uuid::from_string(NAP).unwrap(), Profile::Nap),
        (Uuid::from_string(BNEP).unwrap(), Profile::Bnep),
        (Uuid::from_string(PBAP_PCE).unwrap(), Profile::PbapPce),
        (Uuid::from_string(PBAP_PSE).unwrap(), Profile::PbapPse),
        (Uuid::from_string(MAP).unwrap(), Profile::Map),
        (Uuid::from_string(MNS).unwrap(), Profile::Mns),
        (Uuid::from_string(MAS).unwrap(), Profile::Mas),
        (Uuid::from_string(SAP).unwrap(), Profile::Sap),
        (Uuid::from_string(HEARING_AID).unwrap(), Profile::HearingAid),
        (Uuid::from_string(LE_AUDIO).unwrap(), Profile::LeAudio),
        (Uuid::from_string(DIP).unwrap(), Profile::Dip),
        (Uuid::from_string(VOLUME_CONTROL).unwrap(), Profile::VolumeControl),
        (Uuid::from_string(GENERIC_MEDIA_CONTROL).unwrap(), Profile::GenericMediaControl),
        (Uuid::from_string(MEDIA_CONTROL).unwrap(), Profile::MediaControl),
        (Uuid::from_string(COORDINATED_SET).unwrap(), Profile::CoordinatedSet),
    ]
    .iter()
    .cloned()
    .collect()
});

static PROFILES_UUIDS: LazyLock<HashMap<Profile, Uuid>> =
    LazyLock::new(|| PROFILES.iter().map(|(k, v)| (*v, *k)).collect());

impl UuidHelper {
    /// Checks whether a UUID corresponds to a currently enabled profile.
    pub fn is_profile_supported(profile: &Profile) -> bool {
        SUPPORTED_PROFILES.contains(profile)
    }

    /// Converts a UUID to a known profile enum.
    pub fn is_known_profile(uuid: &Uuid) -> Option<Profile> {
        PROFILES.get(uuid).cloned()
    }

    // AVRCP fights with A2DP when initializing, so let's initiate profiles in a known good order.
    // TODO (b/286991526): remove after issue is resolved
    pub fn get_ordered_supported_profiles() -> Vec<Profile> {
        ORDERED_SUPPORTED_PROFILES.clone()
    }

    pub fn get_supported_profiles() -> HashSet<Profile> {
        SUPPORTED_PROFILES.clone()
    }

    /// Converts a profile enum to its UUID if known.
    pub fn get_profile_uuid(profile: &Profile) -> Option<&Uuid> {
        PROFILES_UUIDS.get(profile)
    }

    /// If a uuid is known to be a certain service, convert it into a formatted
    /// string that shows the service name. Else just format the uuid.
    pub fn known_uuid_to_string(uuid: &Uuid) -> String {
        if let Some(p) = Self::is_known_profile(uuid) {
            format!("{}: {:?}", uuid, p)
        } else {
            uuid.to_string()
        }
    }
}

#[cfg(test)]
mod tests {
    use bt_topshim::btif::Uuid;

    #[test]
    fn test_uuidhelper() {
        for (uuid, _) in super::PROFILES.iter() {
            let converted = Uuid::from_string(uuid.to_string()).unwrap();
            assert_eq!(*uuid, converted);
        }
    }
}
