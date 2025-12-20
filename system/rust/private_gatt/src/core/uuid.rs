//! A UUID (See Core Spec 5.3 Vol 1E 2.9.1. Basic Types)

use crate::packets::att;

/// A UUID (See Core Spec 5.3 Vol 1E 2.9.1. Basic Types)
///
/// Note that the underlying storage is BIG-ENDIAN! But this should be viewed
/// as an implementation detail for C++ interop ONLY - all converters etc.
/// should act as though the backing storage is LITTLE-ENDIAN.
#[derive(PartialEq, Eq, Clone, Copy, Debug)]
#[repr(transparent)]
pub struct Uuid([u8; 16]);

const BASE_UUID: u128 = 0x00000000_0000_1000_8000_0080_5F9B_34FB;

impl Uuid {
    /// Constructor from a u32.
    pub const fn new(val: u32) -> Self {
        Self((BASE_UUID + ((val as u128) << 96)).to_be_bytes())
    }

    fn new_from_le_bytes(mut bytes: [u8; 16]) -> Self {
        bytes.reverse();
        Self(bytes)
    }

    fn le_bytes(&self) -> [u8; 16] {
        let mut out = self.0;
        out.reverse();
        out
    }
}

impl TryFrom<att::Uuid> for Uuid {
    type Error = att::Uuid;

    fn try_from(value: att::Uuid) -> Result<Self, att::Uuid> {
        let bytes = value.data.as_slice();
        Ok(match bytes.len() {
            2 => Self::new(u16::from_le_bytes([bytes[0], bytes[1]]) as u32),
            4 => Self::new(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])),
            // TODO(aryarahul) - should we handle >16 byte Uuids and drop extra bytes?
            _ => Self::new_from_le_bytes(bytes.try_into().map_err(|_| value)?),
        })
    }
}

impl From<att::Uuid16> for Uuid {
    fn from(uuid: att::Uuid16) -> Self {
        Self::new(uuid.data as u32)
    }
}

impl From<att::Uuid128> for Uuid {
    fn from(uuid: att::Uuid128) -> Self {
        Self::new_from_le_bytes(uuid.data)
    }
}

impl From<Uuid> for att::Uuid {
    fn from(value: Uuid) -> Self {
        // TODO(aryarahul): compress to UUID-16 if possible
        att::Uuid { data: value.le_bytes().to_vec() }
    }
}

impl TryFrom<Uuid> for att::Uuid16 {
    type Error = Uuid;

    fn try_from(value: Uuid) -> Result<Self, Self::Error> {
        let backing = u128::from_be_bytes(value.0);
        if backing & ((1u128 << 96) - 1) == BASE_UUID {
            if let Ok(data) = u16::try_from(backing >> 96) {
                return Ok(att::Uuid16 { data });
            }
        }
        Err(value)
    }
}

impl From<Uuid> for att::Uuid128 {
    fn from(value: Uuid) -> Self {
        att::Uuid128 { data: value.le_bytes() }
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn test_uuid16_builder_successful() {
        let uuid = Uuid::new(0x0102);
        let builder: att::Uuid16 = uuid.try_into().unwrap();
        assert_eq!(builder.data, 0x0102);
    }

    #[test]
    fn test_uuid16_builder_fail_nonzero_trailing_bytes() {
        let uuid = Uuid::new(0x01020304);
        let res: Result<att::Uuid16, _> = uuid.try_into();
        assert!(res.is_err());
    }

    #[test]
    fn test_uuid16_builder_fail_invalid_prefix() {
        let mut uuid = Uuid::new(0x0102);
        uuid.0[0] = 1;

        let res: Result<att::Uuid16, _> = uuid.try_into();
        assert!(res.is_err());
    }

    #[test]
    fn test_uuid128_builder() {
        let uuid = Uuid::new(0x01020304);
        let builder: att::Uuid128 = uuid.into();
        assert_eq!(builder.data[..12], BASE_UUID.to_le_bytes()[..12]);
        assert_eq!(builder.data[12..], [4, 3, 2, 1]);
    }

    #[test]
    fn test_uuid_builder() {
        let uuid = Uuid::new(0x01020304);
        let builder: att::Uuid = uuid.into();
        assert_eq!(builder.data[..12], BASE_UUID.to_le_bytes()[..12]);
        assert_eq!(builder.data[12..], [4, 3, 2, 1]);
    }

    #[test]
    fn test_uuid_from_16_fixed_view() {
        let expected = Uuid::new(0x0102);
        let actual: Uuid = att::Uuid16 { data: 0x0102 }.into();
        assert_eq!(expected, actual);
    }

    #[test]
    fn test_uuid_from_128_fixed_view() {
        let data = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16];
        let expected = Uuid::new_from_le_bytes(data);
        let actual: Uuid = att::Uuid128 { data }.into();
        assert_eq!(expected, actual);
    }

    #[test]
    fn test_uuid_from_16_view() {
        let expected = Uuid::new(0x0102);
        let actual: Uuid = att::Uuid { data: vec![2, 1] }.try_into().unwrap();
        assert_eq!(expected, actual);
    }

    #[test]
    fn test_uuid_from_32_view() {
        let expected = Uuid::new(0x01020304);
        let actual: Uuid = att::Uuid { data: vec![4, 3, 2, 1] }.try_into().unwrap();
        assert_eq!(expected, actual);
    }

    #[test]
    fn test_uuid_from_128_view() {
        let data = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16];
        let expected = Uuid::new_from_le_bytes(data);
        let actual: Uuid = att::Uuid { data: data.into() }.try_into().unwrap();
        assert_eq!(expected, actual);
    }

    #[test]
    fn test_uuid_from_invalid_view() {
        let packet = att::Uuid { data: vec![10, 9, 8, 7, 6, 5, 4, 3, 2, 1] };
        let res = Uuid::try_from(packet);
        assert!(res.is_err());
    }

    #[test]
    fn test_le_bytes_roundtrip() {
        let mut bytes = [0u8; 16];
        for (i, byte) in bytes.iter_mut().enumerate() {
            *byte = i as u8;
        }
        let uuid = Uuid::new_from_le_bytes(bytes);
        assert_eq!(uuid.le_bytes(), bytes);
    }
}
