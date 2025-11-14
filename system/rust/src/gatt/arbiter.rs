//! This module handles "arbitration" of ATT packets, to determine whether they
//! should be handled by the primary stack or by the Rust stack

use super::ids::{AdvertiserId, TransportIndex};
use super::mtu::MtuEvent;
use super::opcode_types::OperationType;
use super::server::isolation_manager::IsolationManager;
use crate::gatt::ffi::Handler;
use crate::packets::att;
use ffi::InterceptAction;
use log::{error, trace};
use pdl_runtime::Packet;
use std::ops::Deref;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

#[cxx::bridge]
#[allow(clippy::missing_safety_doc)]
#[allow(clippy::needless_maybe_sized)]
#[allow(missing_docs)]
pub mod ffi {
    /// What action the arbiter should take in response to an incoming packet
    #[namespace = "bluetooth::shim::arbiter"]
    enum InterceptAction {
        /// Forward the packet to the legacy stack
        #[cxx_name = "FORWARD"]
        Forward = 0u32,
        /// Discard the packet (typically because it has been intercepted)
        #[cxx_name = "DROP"]
        Drop = 1u32,
    }

    #[namespace = "bluetooth::shim::arbiter"]
    unsafe extern "C++" {
        include!("src/gatt/ffi/gatt_shim.h");

        type InterceptAction;
        type AclArbiter;
        type ArbiterShim;

        #[cxx_name = "RegisterArbiter"]
        /// # Safety
        ///
        /// The caller must meet the lifetime requirements for both `acl_arbiter` and `arbiter`:
        /// neither must be dropped before the returned `ArbiterShim` is dropped.
        unsafe fn register_arbiter(
            acl_arbiter: *const AclArbiter,
            arbiter: *const Arbiter,
        ) -> UniquePtr<ArbiterShim>;
    }

    #[namespace = "bluetooth::gatt"]
    extern "Rust" {
        type Arbiter;

        #[cxx_name = "OnLeConnect"]
        fn on_le_connect(&self, tcb_idx: u8, advertiser: u8);
        #[cxx_name = "OnLeDisconnect"]
        fn on_le_disconnect(&self, tcb_idx: u8);
        #[cxx_name = "InterceptPacket"]
        fn intercept_packet(&self, tcb_idx: u8, packet: Vec<u8>) -> InterceptAction;
        #[cxx_name = "OnOutgoingMtuReq"]
        fn on_outgoing_mtu_req(&self, tcb_idx: u8);
        #[cxx_name = "OnIncomingMtuResp"]
        fn on_incoming_mtu_resp(&self, tcb_idx: u8, mtu: usize);
        #[cxx_name = "OnIncomingMtuReq"]
        fn on_incoming_mtu_req(&self, tcb_idx: u8, mtu: usize);
    }
}

/// Arbiter handles "arbitration" of ATT packets, to determine whether they should be handled by the
/// primary stack or by the Rust stack.
pub struct Arbiter {
    isolation_manager: Arc<Mutex<IsolationManager>>,
    handler: Handler,
}

/// RegisteredArbiter registers `arbiter` and ensures it is unregistered when dropped.
pub struct RegisteredArbiter {
    // Rust drops fields in declaration order.  It's important `shim` comes *before* `arbiter`
    // so that the arbiter is unregistered before `arbiter` is dropped.
    _shim: cxx::UniquePtr<ffi::ArbiterShim>,

    arbiter: Pin<Box<Arbiter>>,
}

impl RegisteredArbiter {
    /// Returns a new Arbiter.
    pub fn new(acl_arbiter: &'static ffi::AclArbiter, handler: Handler) -> Self {
        let arbiter = Box::pin(Arbiter {
            isolation_manager: Arc::new(Mutex::new(IsolationManager::new())),
            handler,
        });
        // SAFETY: Safe because `arbiter` is pinned, the shim will unregister the arbiter when
        // dropped, and because we drop the shim before we drop the arbiter.
        let shim = unsafe { ffi::register_arbiter(acl_arbiter, &*arbiter) };
        Self { _shim: shim, arbiter }
    }
}

impl Deref for RegisteredArbiter {
    type Target = Arbiter;

    fn deref(&self) -> &Arbiter {
        &self.arbiter
    }
}

/// Test to see if a buffer contains a valid ATT packet with an opcode we
/// are interested in intercepting (those intended for servers that are isolated)
fn try_parse_att_server_packet(
    isolation_manager: &IsolationManager,
    tcb_idx: TransportIndex,
    packet: &[u8],
) -> Option<att::Att> {
    isolation_manager.get_server_id(tcb_idx)?;

    let att = att::Att::decode_full(packet).ok()?;

    if att.opcode == att::AttOpcode::ExchangeMtuRequest {
        // special case: this server opcode is handled by legacy stack, and we snoop
        // on its handling, since the MTU is shared between the client + server
        return None;
    }

    match att.opcode.operation_type() {
        OperationType::Command | OperationType::Request | OperationType::Confirmation => Some(att),
        _ => None,
    }
}

impl Arbiter {
    /// Returns the isolation manager.
    pub fn isolation_manager(&self) -> &Arc<Mutex<IsolationManager>> {
        &self.isolation_manager
    }

    /// Acquire the mutex holding the Arbiter and provide a mutable reference to the
    /// supplied closure
    pub fn with_arbiter<T>(&self, f: impl FnOnce(&mut IsolationManager) -> T) -> T {
        f(&mut self.isolation_manager.lock().unwrap())
    }

    /// Intercepts LE connected.
    pub fn on_le_connect(&self, tcb_idx: u8, advertiser: u8) {
        let tcb_idx = TransportIndex(tcb_idx);
        let advertiser = AdvertiserId(advertiser);
        let is_isolated = self.with_arbiter(|arbiter| arbiter.is_advertiser_isolated(advertiser));
        if is_isolated {
            self.handler.handle(move |modules| {
                if let Err(err) = modules.gatt_module.on_le_connect(tcb_idx, Some(advertiser)) {
                    error!("{err:?}")
                }
            })
        }
    }

    /// Intercepts LE disconnected.
    pub fn on_le_disconnect(&self, tcb_idx: u8) {
        let tcb_idx = TransportIndex(tcb_idx);
        let was_isolated = self.with_arbiter(|arbiter| arbiter.is_connection_isolated(tcb_idx));
        if was_isolated {
            self.handler.handle(move |modules| {
                if let Err(err) = modules.gatt_module.on_le_disconnect(tcb_idx) {
                    error!("{err:?}")
                }
            })
        }
    }

    /// Intercepts incoming packets.
    pub fn intercept_packet(&self, tcb_idx: u8, packet: Vec<u8>) -> InterceptAction {
        let tcb_idx = TransportIndex(tcb_idx);
        if let Some(att) =
            self.with_arbiter(|arbiter| try_parse_att_server_packet(arbiter, tcb_idx, &packet))
        {
            self.handler.handle(move |modules| {
                trace!("pushing packet to GATT");
                if let Some(bearer) = modules.gatt_module.get_bearer(tcb_idx) {
                    bearer.handle_packet(att)
                } else {
                    error!("Bearer for {tcb_idx:?} not found");
                }
            });
            InterceptAction::Drop
        } else {
            InterceptAction::Forward
        }
    }

    /// Intercepts outgoing MTU requests.
    pub fn on_outgoing_mtu_req(&self, tcb_idx: u8) {
        self.on_mtu_event(TransportIndex(tcb_idx), MtuEvent::OutgoingRequest);
    }

    /// Intercepts incoming MTU responses.
    pub fn on_incoming_mtu_resp(&self, tcb_idx: u8, mtu: usize) {
        self.on_mtu_event(TransportIndex(tcb_idx), MtuEvent::IncomingResponse(mtu));
    }

    /// Intercepts incoming MTU requests.
    pub fn on_incoming_mtu_req(&self, tcb_idx: u8, mtu: usize) {
        self.on_mtu_event(TransportIndex(tcb_idx), MtuEvent::IncomingRequest(mtu));
    }

    fn on_mtu_event(&self, tcb_idx: TransportIndex, event: MtuEvent) {
        if self.with_arbiter(|arbiter| arbiter.is_connection_isolated(tcb_idx)) {
            self.handler.handle(move |modules| {
                let Some(bearer) = modules.gatt_module.get_bearer(tcb_idx) else {
                    error!("Bearer for {tcb_idx:?} not found");
                    return;
                };
                if let Err(err) = bearer.handle_mtu_event(event) {
                    error!("{err:?}")
                }
            });
        }
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use crate::gatt::ids::{AttHandle, ServerId};
    use crate::packets::att;

    const TCB_IDX: TransportIndex = TransportIndex(1);
    const ADVERTISER_ID: AdvertiserId = AdvertiserId(3);
    const SERVER_ID: ServerId = ServerId(4);

    fn create_manager_with_isolated_connection(
        tcb_idx: TransportIndex,
        server_id: ServerId,
    ) -> IsolationManager {
        let mut isolation_manager = IsolationManager::new();
        isolation_manager.associate_server_with_advertiser(server_id, ADVERTISER_ID);
        isolation_manager.on_le_connect(tcb_idx, Some(ADVERTISER_ID));
        isolation_manager
    }

    #[test]
    fn test_packet_capture_when_isolated() {
        let isolation_manager = create_manager_with_isolated_connection(TCB_IDX, SERVER_ID);
        let packet = att::AttReadRequest { attribute_handle: AttHandle(1).into() };

        let out = try_parse_att_server_packet(
            &isolation_manager,
            TCB_IDX,
            &packet.encode_to_vec().unwrap(),
        );

        assert!(out.is_some());
    }

    #[test]
    fn test_packet_bypass_when_isolated() {
        let isolation_manager = create_manager_with_isolated_connection(TCB_IDX, SERVER_ID);
        let packet = att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::ReadResponse,
            handle_in_error: AttHandle(1).into(),
            error_code: att::AttErrorCode::InvalidHandle,
        };

        let out = try_parse_att_server_packet(
            &isolation_manager,
            TCB_IDX,
            &packet.encode_to_vec().unwrap(),
        );

        assert!(out.is_none());
    }

    #[test]
    fn test_mtu_bypass() {
        let isolation_manager = create_manager_with_isolated_connection(TCB_IDX, SERVER_ID);
        let packet = att::AttExchangeMtuRequest { mtu: 64 };

        let out = try_parse_att_server_packet(
            &isolation_manager,
            TCB_IDX,
            &packet.encode_to_vec().unwrap(),
        );

        assert!(out.is_none());
    }

    #[test]
    fn test_packet_bypass_when_not_isolated() {
        let isolation_manager = IsolationManager::new();
        let packet = att::AttReadRequest { attribute_handle: AttHandle(1).into() };

        let out = try_parse_att_server_packet(
            &isolation_manager,
            TCB_IDX,
            &packet.encode_to_vec().unwrap(),
        );

        assert!(out.is_none());
    }
}
