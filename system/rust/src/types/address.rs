/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

use std::fmt;

/// Represents Bluetooth address.
#[repr(transparent)]
#[derive(Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Address {
    /// Internal representation.
    value: u64,
}

// SAFETY: The memory layout of the Rust `Address` struct matches the
// memory layout of the `ffi::Address` struct in C++ (a single uint64_t).
unsafe impl cxx::ExternType for Address {
    type Id = cxx::type_id!("ffi::Address");
    type Kind = cxx::kind::Trivial;
}

impl Address {
    /// Creates an Address from big-endian bytes (6 bytes).
    pub fn from_be_bytes(bytes: [u8; 6]) -> Self {
        let val = ((bytes[0] as u64) << 40)
            | ((bytes[1] as u64) << 32)
            | ((bytes[2] as u64) << 24)
            | ((bytes[3] as u64) << 16)
            | ((bytes[4] as u64) << 8)
            | (bytes[5] as u64);
        Self { value: val }
    }

    /// Returns the address as big-endian bytes (6 bytes).
    pub fn to_be_bytes(&self) -> [u8; 6] {
        [
            (self.value >> 40) as u8,
            (self.value >> 32) as u8,
            (self.value >> 24) as u8,
            (self.value >> 16) as u8,
            (self.value >> 8) as u8,
            self.value as u8,
        ]
    }

    /// Returns the full, non-anonymized address string (e.g., "AA:BB:CC:DD:EE:FF").
    /// Use this for JNI or internal system calls where the real address is required.
    pub fn to_full_string(&self) -> String {
        format!("{:?}", self)
    }
}

/// Anonymized display format: XX:XX:XX:XX:aa:bb
impl fmt::Display for Address {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let b = self.to_be_bytes();
        write!(f, "XX:XX:XX:XX:{:02X}:{:02X}", b[4], b[5])
    }
}

/// Detailed debug format: Shows full address.
/// Use of this for user-facing logs is discouraged.
impl fmt::Debug for Address {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let b = self.to_be_bytes();
        write!(f, "{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}", b[0], b[1], b[2], b[3], b[4], b[5])
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn test_address_display_anonymized() {
        let addr = Address::from_be_bytes([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]);
        assert_eq!(format!("{}", addr), "XX:XX:XX:XX:EE:FF");
    }

    #[test]
    fn test_address_debug_full() {
        let addr = Address::from_be_bytes([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]);
        assert_eq!(format!("{:?}", addr), "AA:BB:CC:DD:EE:FF");
    }

    #[test]
    fn test_endian_explicit_conversion() {
        let bytes = [0x01, 0x02, 0x03, 0x04, 0x05, 0x06];
        let addr = Address::from_be_bytes(bytes);
        assert_eq!(addr.to_be_bytes(), bytes);
    }
}
