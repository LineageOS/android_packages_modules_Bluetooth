use bitflags::bitflags;

use crate::core::uuid::Uuid;
use crate::gatt::ids::AttHandle;
use crate::packets::att;

impl From<att::AttHandle> for AttHandle {
    fn from(value: att::AttHandle) -> Self {
        AttHandle(value.handle)
    }
}

impl From<AttHandle> for att::AttHandle {
    fn from(value: AttHandle) -> Self {
        att::AttHandle { handle: value.0 }
    }
}

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub struct AttAttribute {
    pub handle: AttHandle,
    pub type_: Uuid,
    pub permissions: AttPermissions,
}

bitflags! {
    /// The attribute properties supported by the current GATT server implementation
    /// Unimplemented properties will default to false.
    ///
    /// These values are from Core Spec 5.3 Vol 3G 3.3.1.1 Characteristic Properties,
    /// and also match what Android uses in JNI.
    #[derive(Copy, Clone, Debug, PartialEq, Eq)]
    pub struct AttPermissions : u8 {
        /// Attribute can be read using READ_REQ
        const READABLE = 0x02;
        /// Attribute can be written to using WRITE_CMD
        const WRITABLE_WITHOUT_RESPONSE = 0x04;
        /// Attribute can be written to using WRITE_REQ
        const WRITABLE_WITH_RESPONSE = 0x08;
        /// Attribute value may be sent using indications
        const INDICATE = 0x20;
    }
}

impl AttPermissions {
    /// Attribute can be read using READ_REQ
    pub fn readable(&self) -> bool {
        self.contains(AttPermissions::READABLE)
    }
    /// Attribute can be written to using WRITE_REQ
    pub fn writable_with_response(&self) -> bool {
        self.contains(AttPermissions::WRITABLE_WITH_RESPONSE)
    }
    /// Attribute can be written to using WRITE_CMD
    pub fn writable_without_response(&self) -> bool {
        self.contains(AttPermissions::WRITABLE_WITHOUT_RESPONSE)
    }
    /// Attribute value may be sent using indications
    pub fn indicate(&self) -> bool {
        self.contains(AttPermissions::INDICATE)
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn test_att_permissions() {
        let p = AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE;
        assert!(p.readable());
        assert!(p.writable_with_response());
        assert!(!p.writable_without_response());
        assert!(!p.indicate());
    }
}
