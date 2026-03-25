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

// Casing inherited from PDL.
#![allow(non_snake_case)]
#![allow(non_camel_case_types)]
#![allow(warnings, missing_docs)]
#![allow(clippy::all)]

include!(concat!(env!("OUT_DIR"), "/hci_packets.rs"));

impl ErrorCode {
    /// Converts the `ErrorCode` into a `Result<T, Self>`.
    ///
    /// Returns `Ok(v)` if the error code is `Success`.
    /// Otherwise, returns `Err(self)` containing the specific error code.
    ///
    /// # Examples
    /// ```
    /// let status = ErrorCode::Success;
    /// assert_eq!(status.err_or(42), Ok(42));
    /// ```
    pub fn err_or<T>(self, v: T) -> Result<T, Self> {
        if self == Self::Success {
            Ok(v)
        } else {
            Err(self)
        }
    }

    /// Converts the `ErrorCode` into a `Result<T, Self>` using a closure for lazy evaluation.
    ///
    /// Returns `Ok(f())` if the error code is `Success`.
    /// Otherwise, returns `Err(self)`.
    ///
    /// This is preferred over `err_or` when the value `T` is expensive to compute.
    pub fn err_or_else<F, T>(self, f: F) -> Result<T, Self>
    where
        F: FnOnce() -> T,
    {
        if self == Self::Success {
            Ok(f())
        } else {
            Err(self)
        }
    }
}

pub use ErrorCode as HciStatus;
