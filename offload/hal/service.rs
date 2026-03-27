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

use crate::{HciHal, HciHalStatus};

use android_hardware_bluetooth::aidl::android::hardware::bluetooth::IBluetoothHci::IBluetoothHci;
use android_hardware_bluetooth::aidl::android::hardware::bluetooth::IBluetoothHciCallbacks::IBluetoothHciCallbacks;
use android_hardware_bluetooth::aidl::android::hardware::bluetooth::Status::Status;
use binder::{DeathRecipient, ExceptionCode, IBinder, Interface, Result as BinderResult, Strong};
use bluetooth_offload_hci::{Module, ModuleBuilder};
use core::ffi::CStr;
use std::io::Write;
use std::sync::{Arc, RwLock};

/// Service Implementation of AIDL interface `hardware/interface/bluetoot/aidl`,
/// including a proxy interface usable by third party modules.
pub struct HciProxy<T: HciHal> {
    modules: Vec<Box<dyn ModuleBuilder>>,
    ffi: Arc<T>,
    state: Arc<RwLock<State>>,
}

/// Callbacks Proxy, from the HAL to the AIDL interface
pub struct HciProxyCallbacks {
    callbacks: Strong<dyn IBluetoothHciCallbacks>,
    proxy: Arc<dyn Module>,
    state: Arc<RwLock<State>>,
}

struct SinkModule<T: HciHal> {
    ffi: Arc<T>,
    callbacks: Strong<dyn IBluetoothHciCallbacks>,
}

enum State {
    Closed,
    Opened { proxy: Arc<dyn Module>, _death_recipient: DeathRecipient },
}

impl<T: HciHal> HciProxy<T> {
    /// Create the HAL Proxy interface binded to the Bluetooth HCI HAL interface.
    pub fn new(modules: Vec<Box<dyn ModuleBuilder>>, hal: T) -> Self {
        Self { modules, ffi: Arc::new(hal), state: Arc::new(RwLock::new(State::Closed)) }
    }
}

impl<T: HciHal + 'static> Interface for HciProxy<T> {
    fn dump(&self, writer: &mut dyn Write, args: &[&CStr]) -> Result<(), binder::StatusCode> {
        // SAFETY: The writer, coming from binder, is backed by a concrete File type.
        unsafe {
            self.ffi.dump(writer, args);
        }
        Ok(())
    }
}

impl<T: HciHal + 'static> IBluetoothHci for HciProxy<T> {
    fn initialize(&self, callbacks: &Strong<dyn IBluetoothHciCallbacks>) -> BinderResult<()> {
        log::debug!("HciProxy::initialize");
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
                HciProxyCallbacks::new(callbacks.clone(), proxy.clone(), self.state.clone()),
            )
        };

        ffi.initialize(callbacks);
        Ok(())
    }

    fn close(&self) -> BinderResult<()> {
        log::debug!("HciProxy::close");
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

impl<T: HciHal> SinkModule<T> {
    pub(crate) fn new(ffi: Arc<T>, callbacks: Strong<dyn IBluetoothHciCallbacks>) -> Self {
        Self { ffi, callbacks }
    }
}

impl<T: HciHal> Module for SinkModule<T> {
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
            log::error!("SinkModule::in_evt: Cannot send event to client: {e:?}");
        }
    }
    fn in_acl(&self, data: &[u8]) {
        if let Err(e) = self.callbacks.aclDataReceived(data) {
            log::error!("SinkModule::in_acl: Cannot send ACL to client: {e:?}");
        }
    }
    fn in_sco(&self, data: &[u8]) {
        if let Err(e) = self.callbacks.scoDataReceived(data) {
            log::error!("SinkModule::in_sco: Cannot send SCO to client: {e:?}");
        }
    }
    fn in_iso(&self, data: &[u8]) {
        if let Err(e) = self.callbacks.isoDataReceived(data) {
            log::error!("SinkModule::in_iso: Cannot send ISO to client: {e:?}");
        }
    }
}

#[allow(missing_docs)]
impl HciProxyCallbacks {
    fn new(
        callbacks: Strong<dyn IBluetoothHciCallbacks>,
        proxy: Arc<dyn Module>,
        state: Arc<RwLock<State>>,
    ) -> Self {
        Self { callbacks, proxy, state }
    }

    pub fn initialization_complete(&self, status: HciHalStatus) {
        log::debug!("HciProxyCallbacks::initialization_complete: status: {:?}", status);
        let mut state = self.state.write().unwrap();
        if status != HciHalStatus::Success {
            *state = State::Closed;
        }
        if let Err(e) = self.callbacks.initializationComplete(status.into()) {
            log::error!(
                "HciProxyCallbacks::initialization_complete: Cannot call-back client: {e:?}"
            );
            *state = State::Closed;
        }
    }

    pub fn event_received(&self, data: &[u8]) {
        self.proxy.in_evt(data);
    }

    pub fn acl_received(&self, data: &[u8]) {
        self.proxy.in_acl(data);
    }

    pub fn sco_received(&self, data: &[u8]) {
        self.proxy.in_sco(data);
    }

    pub fn iso_received(&self, data: &[u8]) {
        self.proxy.in_iso(data);
    }
}

impl From<HciHalStatus> for Status {
    fn from(value: HciHalStatus) -> Self {
        match value {
            HciHalStatus::Success => Status::SUCCESS,
            HciHalStatus::AlreadyInitialized => Status::ALREADY_INITIALIZED,
            HciHalStatus::UnableToOpenInterface => Status::UNABLE_TO_OPEN_INTERFACE,
            HciHalStatus::HardwareInitializationError => Status::HARDWARE_INITIALIZATION_ERROR,
            HciHalStatus::Unknown => Status::UNKNOWN,
        }
    }
}
