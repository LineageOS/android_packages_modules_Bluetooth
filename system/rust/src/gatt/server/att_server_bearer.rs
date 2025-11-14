//! This module handles an individual connection on the ATT fixed channel.
//! It handles ATT transactions and unacknowledged operations, backed by an
//! database (that may in turn be backed by an upper-layer protocol)

use pdl_runtime::EncodeError;
use std::cell::Cell;
use std::future::Future;

use anyhow::Result;
use log::{error, trace, warn};
use tokio::task::spawn_local;

use crate::core::shared_box::SharedBox;
use crate::core::shared_mutex::SharedMutex;
use crate::gatt::ids::AttHandle;
use crate::gatt::mtu::{AttMtu, MtuEvent};
use crate::gatt::opcode_types::{AttRequest, AttType};
use crate::gatt::server::att_client::WeakAttClient;
use crate::packets::att::{self, AttErrorCode};
use crate::utils::owned_handle::OwnedHandle;

use super::command_handler::AttCommandHandler;
use super::indication_handler::{ConfirmationWatcher, IndicationError, IndicationHandler};
use super::request_handler::AttRequestHandler;

enum AttRequestState {
    Idle(AttRequestHandler),
    Pending { _task: OwnedHandle<()> },
    Replacing,
}

/// The errors that can occur while trying to send a packet
#[derive(Debug)]
pub enum SendError {
    /// The packet failed to serialize
    #[allow(dead_code)]
    SerializeError(EncodeError),
    /// The connection no longer exists
    ConnectionDropped,
}

/// This represents a single ATT bearer (currently, always the unenhanced fixed
/// channel on LE) The AttRequestState ensures that only one transaction can
/// take place at a time
pub struct AttServerBearer {
    // general
    send_packet: Box<dyn Fn(att::Att) -> Result<(), EncodeError>>,
    mtu: AttMtu,

    // request state
    curr_request: Cell<AttRequestState>,

    // indication state
    indication_handler: SharedMutex<IndicationHandler>,
    pending_confirmation: ConfirmationWatcher,

    // command handler (across all bearers)
    command_handler: AttCommandHandler,
}

impl AttServerBearer {
    /// Constructor, wrapping an ATT channel (for outgoing packets).
    pub fn new(
        client: WeakAttClient,
        send_packet: impl Fn(att::Att) -> Result<(), EncodeError> + 'static,
    ) -> Self {
        let (indication_handler, pending_confirmation) = IndicationHandler::new(client.clone());
        Self {
            send_packet: Box::new(send_packet),
            mtu: AttMtu::new(),

            curr_request: AttRequestState::Idle(AttRequestHandler::new(client.clone())).into(),

            indication_handler: SharedMutex::new(indication_handler),
            pending_confirmation,

            command_handler: AttCommandHandler::new(client),
        }
    }

    /// Sends the specified packet on the bearer.
    pub fn send_packet(&self, packet: att::Att) -> Result<(), EncodeError> {
        (self.send_packet)(packet)
    }
}

impl SharedBox<AttServerBearer> {
    /// Handle an incoming packet, and send outgoing packets as appropriate
    /// using the owned ATT channel.
    pub fn handle_packet(&self, packet: att::Att) {
        match packet.into() {
            AttType::Command(packet) => self.command_handler.process_packet(packet),
            AttType::Request(packet) => self.handle_request(packet),
            AttType::Confirmation(_) => self.pending_confirmation.on_confirmation(),
            AttType::Response(_) | AttType::Notification(_) | AttType::Indication(_) => {
                unreachable!("the arbiter should not let us receive these packet types")
            }
        }
    }

    /// Send an indication, wait for the peer confirmation, and return the
    /// appropriate status If multiple calls are outstanding, they are
    /// executed in FIFO order.
    pub fn send_indication(
        &self,
        handle: AttHandle,
        data: Vec<u8>,
    ) -> impl Future<Output = Result<(), IndicationError>> {
        trace!("sending indication for handle {handle:?}");

        let locked_indication_handler = self.indication_handler.lock();
        let pending_mtu = self.mtu.snapshot();

        async move {
            // first wait until we are at the head of the queue and are ready to send
            // indications
            let mut indication_handler = locked_indication_handler
                .await
                .ok_or_else(|| {
                    warn!("indication for handle {handle:?} cancelled while queued since the connection dropped");
                    IndicationError::SendError(SendError::ConnectionDropped)
                })?;
            // then, if MTU negotiation is taking place, wait for it to complete
            let mtu = pending_mtu
                .await
                .ok_or_else(|| {
                    warn!("indication for handle {handle:?} cancelled while waiting for MTU exchange to complete since the connection dropped");
                    IndicationError::SendError(SendError::ConnectionDropped)
                })?;
            // finally, send, and wait for a response
            indication_handler.send(handle, &data, mtu).await
        }
    }

    /// Handle a snooped MTU event, to update the MTU we use for our various
    /// operations
    pub fn handle_mtu_event(&self, mtu_event: MtuEvent) -> Result<()> {
        self.mtu.handle_event(mtu_event)
    }

    fn handle_request(&self, packet: AttRequest) {
        let curr_request = self.curr_request.replace(AttRequestState::Replacing);
        self.curr_request.replace(match curr_request {
            AttRequestState::Idle(mut request_handler) => {
                // even if the MTU is updated afterwards, 5.3 3F 3.4.2.2 states that the
                // request-time MTU should be used
                let mtu = self.mtu.snapshot_or_default();
                let this = self.downgrade();
                let opcode = packet.opcode;
                let task = spawn_local(async move {
                    trace!("starting ATT transaction");
                    let reply = request_handler.process_packet(packet, mtu).await;
                    this.with(|this| {
                        this.map(|this| {
                            match this.send_packet(reply) {
                                Ok(_) => {
                                    trace!("reply packet sent")
                                }
                                Err(err) => {
                                    error!("serializer failure {err:?}, dropping packet and sending failed reply");
                                    // if this also fails, we're stuck
                                    if let Err(err) = this.send_packet(att::AttErrorResponse {
                                        opcode_in_error: opcode,
                                        handle_in_error: AttHandle(0).into(),
                                        error_code: AttErrorCode::UnlikelyError,
                                    }.try_into().unwrap()) {
                                        panic!("unexpected serialize error for known-good packet {err:?}")
                                    }
                                }
                            };
                            // ready for next transaction
                            this.curr_request.replace(AttRequestState::Idle(request_handler));
                        })
                    });
                });
                AttRequestState::Pending { _task: task.into() }
            }
            AttRequestState::Pending { .. } => {
                warn!("multiple ATT operations cannot simultaneously take place, dropping one");
                // TODO(aryarahul) - disconnect connection here;
                curr_request
            }
            AttRequestState::Replacing => {
              panic!("Replacing is an ephemeral state");
            }
        });
    }
}

#[cfg(test)]
mod test {
    use std::rc::Rc;

    use tokio::sync::mpsc::error::TryRecvError;
    use tokio::sync::mpsc::UnboundedReceiver;

    use super::*;

    use crate::core::shared_box::SharedBox;
    use crate::core::uuid::Uuid;
    use crate::gatt::ffi::AttributeBackingType;
    use crate::gatt::ids::TransportIndex;
    use crate::gatt::mocks::mock_datastore::{MockDatastore, MockDatastoreEvents};
    use crate::gatt::server::att_client::AttClient;
    use crate::gatt::server::att_database::{AttAttribute, AttPermissions};
    use crate::gatt::server::gatt_database::{
        GattCharacteristicWithHandle, GattDatabase, GattServiceWithHandle,
    };
    use crate::gatt::server::test::test_att_db::new_test_database;
    use crate::packets::att;
    use crate::utils::task::{block_on_locally, try_await};

    const VALID_HANDLE: AttHandle = AttHandle(3);
    const INVALID_HANDLE: AttHandle = AttHandle(4);
    const ANOTHER_VALID_HANDLE: AttHandle = AttHandle(10);

    const TCB_IDX: TransportIndex = TransportIndex(1);

    fn open_connection(
    ) -> (SharedBox<GattDatabase>, SharedBox<AttClient>, UnboundedReceiver<att::Att>) {
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: VALID_HANDLE,
                    type_: Uuid::new(0x1234),
                    permissions: AttPermissions::READABLE | AttPermissions::INDICATE,
                },
                vec![5, 6],
            ),
            (
                AttAttribute {
                    handle: ANOTHER_VALID_HANDLE,
                    type_: Uuid::new(0x5678),
                    permissions: AttPermissions::READABLE | AttPermissions::INDICATE,
                },
                vec![5, 6],
            ),
        ]);
        let (client, rx) = AttClient::new_test_client(TCB_IDX, &db);
        (db, client, rx)
    }

    #[test]
    fn test_single_transaction() {
        block_on_locally(async {
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();
            conn.handle_packet(
                att::AttReadRequest { attribute_handle: VALID_HANDLE.into() }.try_into().unwrap(),
            );
            assert_eq!(rx.recv().await.unwrap().opcode, att::AttOpcode::ReadResponse);
            assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));
        });
    }

    #[test]
    fn test_sequential_transactions() {
        block_on_locally(async {
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();
            conn.handle_packet(
                att::AttReadRequest { attribute_handle: INVALID_HANDLE.into() }.try_into().unwrap(),
            );
            assert_eq!(rx.recv().await.unwrap().opcode, att::AttOpcode::ErrorResponse);
            assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));

            conn.handle_packet(
                att::AttReadRequest { attribute_handle: VALID_HANDLE.into() }.try_into().unwrap(),
            );
            assert_eq!(rx.recv().await.unwrap().opcode, att::AttOpcode::ReadResponse);
            assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));
        });
    }

    #[test]
    fn test_concurrent_transaction_failure() {
        // arrange: AttServerBearer linked to a backing datastore and packet queue, with
        // two characteristics in the database
        let (datastore, mut data_rx) = MockDatastore::new();
        let datastore = Rc::new(datastore);
        let db = SharedBox::new(GattDatabase::new());
        db.add_service_with_handles(
            GattServiceWithHandle {
                handle: AttHandle(1),
                type_: Uuid::new(1),
                characteristics: vec![
                    GattCharacteristicWithHandle {
                        handle: VALID_HANDLE,
                        type_: Uuid::new(2),
                        permissions: AttPermissions::READABLE,
                        descriptors: vec![],
                    },
                    GattCharacteristicWithHandle {
                        handle: ANOTHER_VALID_HANDLE,
                        type_: Uuid::new(2),
                        permissions: AttPermissions::READABLE,
                        descriptors: vec![],
                    },
                ],
            },
            datastore,
        )
        .unwrap();
        let (client, mut rx) = AttClient::new_test_client(TCB_IDX, &db);
        let conn = client.bearer();
        let data = [1, 2];

        // act: send two read requests before replying to either read
        // first request
        block_on_locally(async {
            let req1 = att::AttReadRequest { attribute_handle: VALID_HANDLE.into() };
            conn.handle_packet(req1.try_into().unwrap());
            // second request
            let req2 = att::AttReadRequest { attribute_handle: ANOTHER_VALID_HANDLE.into() };
            conn.handle_packet(req2.try_into().unwrap());
            // handle first reply
            let MockDatastoreEvents::Read(
                TCB_IDX,
                VALID_HANDLE,
                AttributeBackingType::Characteristic,
                data_resp,
            ) = data_rx.recv().await.unwrap()
            else {
                unreachable!();
            };
            data_resp.send(Ok(data.to_vec())).unwrap();
            trace!("reply sent from upper tester");

            // assert: that the first reply was made
            let resp = rx.recv().await.unwrap();
            assert_eq!(resp, att::AttReadResponse { value: data.to_vec() }.try_into().unwrap());
            // assert no other replies were made
            assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));
            // assert no callbacks are pending
            assert_eq!(data_rx.try_recv().unwrap_err(), TryRecvError::Empty);
        });
    }

    #[test]
    fn test_indication_confirmation() {
        block_on_locally(async {
            // arrange
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();

            // act: send an indication
            let pending_send = spawn_local(conn.send_indication(VALID_HANDLE, vec![1, 2, 3]));
            assert_eq!(rx.recv().await.unwrap().opcode, att::AttOpcode::HandleValueIndication);
            assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));
            // and the confirmation
            conn.handle_packet(att::AttHandleValueConfirmation {}.try_into().unwrap());

            // assert: the indication was correctly sent
            assert!(matches!(pending_send.await.unwrap(), Ok(())));
        });
    }

    #[test]
    fn test_sequential_indications() {
        block_on_locally(async {
            // arrange
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();

            // act: send the first indication
            let pending_send1 = spawn_local(conn.send_indication(VALID_HANDLE, vec![1, 2, 3]));
            // wait for/capture the outgoing packet
            let sent1 = rx.recv().await.unwrap();
            // send the response
            conn.handle_packet(att::AttHandleValueConfirmation {}.try_into().unwrap());
            // send the second indication
            let pending_send2 = spawn_local(conn.send_indication(VALID_HANDLE, vec![1, 2, 3]));
            // wait for/capture the outgoing packet
            let sent2 = rx.recv().await.unwrap();
            // and the response
            conn.handle_packet(att::AttHandleValueConfirmation {}.try_into().unwrap());

            // assert: exactly two indications were sent
            assert_eq!(sent1.opcode, att::AttOpcode::HandleValueIndication);
            assert_eq!(sent2.opcode, att::AttOpcode::HandleValueIndication);
            assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));
            // and that both got successful responses
            assert!(matches!(pending_send1.await.unwrap(), Ok(())));
            assert!(matches!(pending_send2.await.unwrap(), Ok(())));
        });
    }

    #[test]
    fn test_queued_indications_only_one_sent() {
        block_on_locally(async {
            // arrange
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();

            // act: send two indications simultaneously
            let pending_send1 = spawn_local(conn.send_indication(VALID_HANDLE, vec![1, 2, 3]));
            let pending_send2 =
                spawn_local(conn.send_indication(ANOTHER_VALID_HANDLE, vec![1, 2, 3]));
            // assert: only one was initially sent
            assert_eq!(rx.recv().await.unwrap().opcode, att::AttOpcode::HandleValueIndication);
            assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));
            // and both are still pending
            assert!(!pending_send1.is_finished());
            assert!(!pending_send2.is_finished());
        });
    }

    #[test]
    fn test_queued_indications_dequeue_second() {
        block_on_locally(async {
            // arrange
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();

            // act: send two indications simultaneously
            let pending_send1 = spawn_local(conn.send_indication(VALID_HANDLE, vec![1, 2, 3]));
            let pending_send2 =
                spawn_local(conn.send_indication(ANOTHER_VALID_HANDLE, vec![1, 2, 3]));
            // wait for/capture the outgoing packet
            let sent1 = rx.recv().await.unwrap();
            // send response for the first one
            conn.handle_packet(att::AttHandleValueConfirmation {}.try_into().unwrap());
            // wait for/capture the outgoing packet
            let sent2 = rx.recv().await.unwrap();

            // assert: the first future has completed successfully, the second one is
            // pending
            assert!(matches!(pending_send1.await.unwrap(), Ok(())));
            assert!(!pending_send2.is_finished());
            // and that both indications have been sent
            assert_eq!(sent1.opcode, att::AttOpcode::HandleValueIndication);
            assert_eq!(sent2.opcode, att::AttOpcode::HandleValueIndication);
            assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));
        });
    }

    #[test]
    fn test_queued_indications_complete_both() {
        block_on_locally(async {
            // arrange
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();

            // act: send two indications simultaneously
            let pending_send1 = spawn_local(conn.send_indication(VALID_HANDLE, vec![1, 2, 3]));
            let pending_send2 =
                spawn_local(conn.send_indication(ANOTHER_VALID_HANDLE, vec![1, 2, 3]));
            // wait for/capture the outgoing packet
            let sent1 = rx.recv().await.unwrap();
            // send response for the first one
            conn.handle_packet(att::AttHandleValueConfirmation {}.try_into().unwrap());
            // wait for/capture the outgoing packet
            let sent2 = rx.recv().await.unwrap();
            // and now the second
            conn.handle_packet(att::AttHandleValueConfirmation {}.try_into().unwrap());

            // assert: both futures have completed successfully
            assert!(matches!(pending_send1.await.unwrap(), Ok(())));
            assert!(matches!(pending_send2.await.unwrap(), Ok(())));
            // and both indications have been sent
            assert_eq!(sent1.opcode, att::AttOpcode::HandleValueIndication);
            assert_eq!(sent2.opcode, att::AttOpcode::HandleValueIndication);
            assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));
        });
    }

    #[test]
    fn test_indication_connection_drop() {
        block_on_locally(async {
            // arrange: a pending indication
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();
            let pending_send = spawn_local(conn.send_indication(VALID_HANDLE, vec![1, 2, 3]));

            // act: drop the connection after the indication is sent
            rx.recv().await.unwrap();
            drop(client);

            // assert: the pending indication fails with the appropriate error
            assert!(matches!(
                pending_send.await.unwrap(),
                Err(IndicationError::ConnectionDroppedWhileWaitingForConfirmation)
            ));
        });
    }

    #[test]
    fn test_single_indication_pending_mtu() {
        block_on_locally(async {
            // arrange: pending MTU negotiation
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();
            conn.handle_mtu_event(MtuEvent::OutgoingRequest).unwrap();

            // act: try to send an indication with a large payload size
            let _ = try_await(conn.send_indication(VALID_HANDLE, (1..50).collect())).await;
            // then resolve the MTU negotiation with a large MTU
            conn.handle_mtu_event(MtuEvent::IncomingResponse(100)).unwrap();

            // assert: the indication was sent
            assert_eq!(rx.recv().await.unwrap().opcode, att::AttOpcode::HandleValueIndication);
        });
    }

    #[test]
    fn test_single_indication_pending_mtu_fail() {
        block_on_locally(async {
            // arrange: pending MTU negotiation
            let (_db, client, _rx) = open_connection();
            let conn = client.bearer();
            conn.handle_mtu_event(MtuEvent::OutgoingRequest).unwrap();

            // act: try to send an indication with a large payload size
            let pending_mtu =
                try_await(conn.send_indication(VALID_HANDLE, (1..50).collect())).await.unwrap_err();
            // then resolve the MTU negotiation with a small MTU
            conn.handle_mtu_event(MtuEvent::IncomingResponse(32)).unwrap();

            // assert: the indication failed to send
            assert!(matches!(pending_mtu.await, Err(IndicationError::DataExceedsMtu { .. })));
        });
    }

    #[test]
    fn test_server_transaction_pending_mtu() {
        block_on_locally(async {
            // arrange: pending MTU negotiation
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();
            conn.handle_mtu_event(MtuEvent::OutgoingRequest).unwrap();

            // act: send server packet
            conn.handle_packet(
                att::AttReadRequest { attribute_handle: VALID_HANDLE.into() }.try_into().unwrap(),
            );

            // assert: that we reply even while the MTU req is outstanding
            assert_eq!(rx.recv().await.unwrap().opcode, att::AttOpcode::ReadResponse);
        });
    }

    #[test]
    fn test_queued_indication_pending_mtu_uses_mtu_on_dequeue() {
        block_on_locally(async {
            // arrange: an outstanding indication
            let (_db, client, mut rx) = open_connection();
            let conn = client.bearer();
            let _ = try_await(conn.send_indication(VALID_HANDLE, vec![1, 2, 3])).await;
            rx.recv().await.unwrap(); // flush rx_queue

            // act: enqueue an indication with a large payload
            let _ = try_await(conn.send_indication(VALID_HANDLE, (1..50).collect())).await;
            // then perform MTU negotiation to upgrade to a large MTU
            conn.handle_mtu_event(MtuEvent::OutgoingRequest).unwrap();
            conn.handle_mtu_event(MtuEvent::IncomingResponse(512)).unwrap();
            // finally resolve the first indication, so the second indication can be sent
            conn.handle_packet(att::AttHandleValueConfirmation {}.try_into().unwrap());

            // assert: the second indication successfully sent (so it used the new MTU)
            assert_eq!(rx.recv().await.unwrap().opcode, att::AttOpcode::HandleValueIndication);
        });
    }
}
