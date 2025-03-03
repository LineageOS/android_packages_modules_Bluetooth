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

use crate::ffi::{CInterface, CStatus, Callbacks, DataCallbacks, Ffi};
use android_hardware_bluetooth::aidl::android::hardware::bluetooth::IBluetoothHci::{
    BnBluetoothHci, BpBluetoothHci, IBluetoothHci,
};
use android_hardware_bluetooth::aidl::android::hardware::bluetooth::IBluetoothHciCallbacks::IBluetoothHciCallbacks;
use android_hardware_bluetooth::aidl::android::hardware::bluetooth::Status::Status;
use binder::{DeathRecipient, ExceptionCode, IBinder, Interface, Result as BinderResult, Strong};
use bluetooth_offload_hci::{Module, ModuleBuilder};
use bluetooth_offload_leaudio_hci::LeAudioModuleBuilder;
use std::sync::{Arc, RwLock};

/// Service Implementation of AIDL interface `hardware/interface/bluetoot/aidl`,
/// including a proxy interface usable by third party modules.
pub struct HciHalProxy {
    modules: Vec<Box<dyn ModuleBuilder>>,
    ffi: Arc<Ffi<FfiCallbacks>>,
    state: Arc<RwLock<State>>,
}

struct FfiCallbacks {
    callbacks: Strong<dyn IBluetoothHciCallbacks>,
    proxy: Arc<dyn Module>,
    state: Arc<RwLock<State>>,
}

struct SinkModule<T: Callbacks> {
    ffi: Arc<Ffi<T>>,
    callbacks: Strong<dyn IBluetoothHciCallbacks>,
}

enum State {
    Closed,
    Opened { proxy: Arc<dyn Module>, _death_recipient: DeathRecipient },
}

impl Interface for HciHalProxy {}

impl HciHalProxy {
    /// Create the HAL Proxy interface binded to the Bluetooth HCI HAL interface.
    pub fn new(modules: Vec<Box<dyn ModuleBuilder>>, cintf: CInterface) -> Self {
        Self {
            modules,
            ffi: Arc::new(Ffi::new(cintf)),
            state: Arc::new(RwLock::new(State::Closed)),
        }
    }
}

impl IBluetoothHci for HciHalProxy {
    fn initialize(&self, callbacks: &Strong<dyn IBluetoothHciCallbacks>) -> BinderResult<()> {
        let (ffi, callbacks) = {
            let mut state = self.state.write().unwrap();

            if !matches!(*state, State::Closed) {
                let _ = callbacks.initializationComplete(Status::ALREADY_INITIALIZED);
                return Ok(());
            }

            let mut proxy: Arc<dyn Module> =
                Arc::new(SinkModule::new(self.ffi.clone(), callbacks.clone()));
            for m in self.modules.iter().rev() {
                proxy = m.build(proxy);
            }

            let mut death_recipient = {
                let (ffi, state) = (self.ffi.clone(), self.state.clone());
                DeathRecipient::new(move || {
                    log::info!("Bluetooth stack has died");
                    let mut state = state.write().unwrap();
                    ffi.client_died();
                    *state = State::Closed;
                })
            };
            callbacks.as_binder().link_to_death(&mut death_recipient)?;

            *state = State::Opened { proxy: proxy.clone(), _death_recipient: death_recipient };
            (
                self.ffi.clone(),
                FfiCallbacks::new(callbacks.clone(), proxy.clone(), self.state.clone()),
            )
        };

        ffi.initialize(callbacks);
        Ok(())
    }

    fn close(&self) -> BinderResult<()> {
        *self.state.write().unwrap() = State::Closed;
        self.ffi.close();
        Ok(())
    }

    fn sendHciCommand(&self, data: &[u8]) -> BinderResult<()> {
        let State::Opened { ref proxy, .. } = *self.state.read().unwrap() else {
            return Err(ExceptionCode::ILLEGAL_STATE.into());
        };

        proxy.out_cmd(data);
        Ok(())
    }

    fn sendAclData(&self, data: &[u8]) -> BinderResult<()> {
        let State::Opened { ref proxy, .. } = *self.state.read().unwrap() else {
            return Err(ExceptionCode::ILLEGAL_STATE.into());
        };

        proxy.out_acl(data);
        Ok(())
    }

    fn sendScoData(&self, data: &[u8]) -> BinderResult<()> {
        let State::Opened { ref proxy, .. } = *self.state.read().unwrap() else {
            return Err(ExceptionCode::ILLEGAL_STATE.into());
        };

        proxy.out_sco(data);
        Ok(())
    }

    fn sendIsoData(&self, data: &[u8]) -> BinderResult<()> {
        let State::Opened { ref proxy, .. } = *self.state.read().unwrap() else {
            return Err(ExceptionCode::ILLEGAL_STATE.into());
        };

        proxy.out_iso(data);
        Ok(())
    }
}

impl<T: Callbacks> SinkModule<T> {
    pub(crate) fn new(ffi: Arc<Ffi<T>>, callbacks: Strong<dyn IBluetoothHciCallbacks>) -> Self {
        Self { ffi, callbacks }
    }
}

impl<T: Callbacks> Module for SinkModule<T> {
    fn next(&self) -> &dyn Module {
        unreachable!()
    }

    fn out_cmd(&self, data: &[u8]) {
        self.ffi.send_command(data);
    }
    fn out_acl(&self, data: &[u8]) {
        self.ffi.send_acl(data);
    }
    fn out_iso(&self, data: &[u8]) {
        self.ffi.send_iso(data);
    }
    fn out_sco(&self, data: &[u8]) {
        self.ffi.send_sco(data);
    }

    fn in_evt(&self, data: &[u8]) {
        if let Err(e) = self.callbacks.hciEventReceived(data) {
            log::error!("Cannot send event to client: {:?}", e);
        }
    }
    fn in_acl(&self, data: &[u8]) {
        if let Err(e) = self.callbacks.aclDataReceived(data) {
            log::error!("Cannot send ACL to client: {:?}", e);
        }
    }
    fn in_sco(&self, data: &[u8]) {
        if let Err(e) = self.callbacks.scoDataReceived(data) {
            log::error!("Cannot send SCO to client: {:?}", e);
        }
    }
    fn in_iso(&self, data: &[u8]) {
        if let Err(e) = self.callbacks.isoDataReceived(data) {
            log::error!("Cannot send ISO to client: {:?}", e);
        }
    }
}

impl FfiCallbacks {
    fn new(
        callbacks: Strong<dyn IBluetoothHciCallbacks>,
        proxy: Arc<dyn Module>,
        state: Arc<RwLock<State>>,
    ) -> Self {
        Self { callbacks, proxy, state }
    }
}

impl Callbacks for FfiCallbacks {
    fn initialization_complete(&self, status: CStatus) {
        let mut state = self.state.write().unwrap();
        if status != CStatus::Success {
            *state = State::Closed;
        }
        if let Err(e) = self.callbacks.initializationComplete(status.into()) {
            log::error!("Cannot call-back client: {:?}", e);
            *state = State::Closed;
        }
    }
}

impl DataCallbacks for FfiCallbacks {
    fn event_received(&self, data: &[u8]) {
        self.proxy.in_evt(data);
    }

    fn acl_received(&self, data: &[u8]) {
        self.proxy.in_acl(data);
    }

    fn sco_received(&self, data: &[u8]) {
        self.proxy.in_sco(data);
    }

    fn iso_received(&self, data: &[u8]) {
        self.proxy.in_iso(data);
    }
}

impl From<CStatus> for Status {
    fn from(value: CStatus) -> Self {
        match value {
            CStatus::Success => Status::SUCCESS,
            CStatus::AlreadyInitialized => Status::ALREADY_INITIALIZED,
            CStatus::UnableToOpenInterface => Status::UNABLE_TO_OPEN_INTERFACE,
            CStatus::HardwareInitializationError => Status::HARDWARE_INITIALIZATION_ERROR,
            CStatus::Unknown => Status::UNKNOWN,
        }
    }
}

#[no_mangle]
pub extern "C" fn __add_bluetooth_hci_service(cintf: CInterface) {
    binder::add_service(
        &format!("{}/default", BpBluetoothHci::get_descriptor()),
        BnBluetoothHci::new_binder(
            HciHalProxy::new(vec![Box::new(LeAudioModuleBuilder {})], cintf),
            binder::BinderFeatures::default(),
        )
        .as_binder(),
    )
    .expect("Failed to register service");
}
