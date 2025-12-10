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

//! HCI HAL Binder implementation with proxy integration

mod service;

pub use service::{HciProxy, HciProxyCallbacks};

use core::ffi::CStr;
use std::io::Write;

#[allow(missing_docs)]
pub trait HciHal: Send + Sync {
    fn initialize(&self, client: HciProxyCallbacks);
    fn send_command(&self, data: &[u8]);
    fn send_acl(&self, data: &[u8]);
    fn send_iso(&self, data: &[u8]);
    fn send_sco(&self, data: &[u8]);
    fn close(&self);
    fn client_died(&self) {}

    /// # Safety
    ///
    /// `_writer` must be backed by a concrete std::fs::File type
    unsafe fn dump(&self, _writer: &mut dyn Write, _args: &[&CStr]) {}
}

#[allow(missing_docs)]
#[derive(Debug, PartialEq)]
pub enum HciHalStatus {
    Success,
    AlreadyInitialized,
    UnableToOpenInterface,
    HardwareInitializationError,
    Unknown,
}

/// C Interface for C/C++ HAl implementation
mod ffi;

use android_hardware_bluetooth::aidl::android::hardware::bluetooth::IBluetoothHci::{
    BnBluetoothHci, BpBluetoothHci, IBluetoothHci,
};
use bluetooth_offload_leaudio_hci::LeAudioModuleBuilder;
use ffi::{CInterface, Ffi};

/// Entry-point for C/C++ implementation
/// Add the binder service, and use HAL C/C++ backend defined as CInterface.
#[no_mangle]
pub extern "C" fn __add_bluetooth_hci_service(cintf: CInterface) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("swoff_hal")
            .with_max_level(log::LevelFilter::Info),
    );
    binder::add_service(
        &format!("{}/default", BpBluetoothHci::get_descriptor()),
        BnBluetoothHci::new_binder(
            HciProxy::new(vec![Box::new(LeAudioModuleBuilder {})], Ffi::new(cintf)),
            binder::BinderFeatures::default(),
        )
        .as_binder(),
    )
    .expect("Failed to register service");
}
