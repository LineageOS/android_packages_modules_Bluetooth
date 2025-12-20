use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::time::Duration;

use async_trait::async_trait;
use log::{trace, warn};
use tokio::sync::oneshot;
use tokio::time::timeout;

use crate::gatt::callbacks::{GattCallbacks, TransactionDecision};
use crate::gatt::ids::{AttHandle, ConnectionId, ServerId, TransactionId, TransportIndex};
use crate::packets::att::AttErrorCode;

use super::{AttributeBackingType, GattWriteRequestType, GattWriteType, RawGattDatastore};

struct PendingTransaction {
    response: oneshot::Sender<Result<Vec<u8>, AttErrorCode>>,
}

#[derive(Debug)]
struct PendingTransactionWatcher {
    conn_id: ConnectionId,
    trans_id: TransactionId,
    rx: oneshot::Receiver<Result<Vec<u8>, AttErrorCode>>,
}

/// This struct converts the asynchronus read/write operations of GattDatastore
/// into the callback-based interface expected by JNI
pub struct CallbackTransactionManager {
    callbacks: Rc<dyn GattCallbacks>,
    pending_transactions: RefCell<PendingTransactionsState>,
}

struct PendingTransactionsState {
    pending_transactions: HashMap<(ConnectionId, TransactionId), PendingTransaction>,
    next_transaction_id: u32,
}

/// We expect all responses to be provided within this timeout
/// It should be less than 30s, as that is the ATT timeout that causes
/// the client to disconnect.
const TIMEOUT: Duration = Duration::from_secs(15);

/// The cause of a failure to dispatch a call to send_response()
#[derive(Debug, PartialEq, Eq)]
pub enum CallbackResponseError {
    /// The TransactionId supplied was invalid for the specified connection
    NonExistentTransaction(TransactionId),
    /// The TransactionId was valid but has since terminated
    ListenerHungUp(TransactionId),
}

impl CallbackTransactionManager {
    /// Constructor, wrapping a GattCallbacks instance with the GattDatastore
    /// interface
    pub fn new(callbacks: Rc<dyn GattCallbacks>) -> Self {
        Self {
            callbacks,
            pending_transactions: RefCell::new(PendingTransactionsState {
                pending_transactions: HashMap::new(),
                next_transaction_id: 1,
            }),
        }
    }

    /// Invoked from server implementations in response to read/write requests
    pub fn send_response(
        &self,
        conn_id: ConnectionId,
        trans_id: TransactionId,
        value: Result<Vec<u8>, AttErrorCode>,
    ) -> Result<(), CallbackResponseError> {
        let mut pending = self.pending_transactions.borrow_mut();
        if let Some(transaction) = pending.pending_transactions.remove(&(conn_id, trans_id)) {
            if transaction.response.send(value).is_err() {
                Err(CallbackResponseError::ListenerHungUp(trans_id))
            } else {
                trace!("got expected response for transaction {trans_id:?}");
                Ok(())
            }
        } else {
            Err(CallbackResponseError::NonExistentTransaction(trans_id))
        }
    }

    /// Get an impl GattDatastore tied to a particular server
    pub fn get_datastore(self: &Rc<Self>, server_id: ServerId) -> impl RawGattDatastore {
        GattDatastoreImpl { callback_transaction_manager: self.clone(), server_id }
    }
}

impl PendingTransactionsState {
    fn alloc_transaction_id(&mut self) -> TransactionId {
        let trans_id = TransactionId(self.next_transaction_id);
        self.next_transaction_id = self.next_transaction_id.wrapping_add(1);
        trans_id
    }

    fn start_new_transaction(&mut self, conn_id: ConnectionId) -> PendingTransactionWatcher {
        let trans_id = self.alloc_transaction_id();
        let (tx, rx) = oneshot::channel();
        self.pending_transactions.insert((conn_id, trans_id), PendingTransaction { response: tx });
        PendingTransactionWatcher { conn_id, trans_id, rx }
    }
}

impl PendingTransactionWatcher {
    /// Wait for the transaction to resolve, or to hit the timeout. If the
    /// timeout is reached, clean up state related to transaction watching.
    async fn wait(self, manager: &CallbackTransactionManager) -> Result<Vec<u8>, AttErrorCode> {
        if let Ok(Ok(result)) = timeout(TIMEOUT, self.rx).await {
            result
        } else {
            manager
                .pending_transactions
                .borrow_mut()
                .pending_transactions
                .remove(&(self.conn_id, self.trans_id));
            warn!("no response received from Java after timeout - returning UNLIKELY_ERROR");
            Err(AttErrorCode::UnlikelyError)
        }
    }
}

struct GattDatastoreImpl {
    callback_transaction_manager: Rc<CallbackTransactionManager>,
    server_id: ServerId,
}

#[async_trait(?Send)]
impl RawGattDatastore for GattDatastoreImpl {
    async fn read(
        &self,
        tcb_idx: TransportIndex,
        handle: AttHandle,
        offset: u32,
        attr_type: AttributeBackingType,
    ) -> Result<Vec<u8>, AttErrorCode> {
        let conn_id = ConnectionId::new(tcb_idx, self.server_id);

        let pending_transaction = self
            .callback_transaction_manager
            .pending_transactions
            .borrow_mut()
            .start_new_transaction(conn_id);
        let trans_id = pending_transaction.trans_id;

        self.callback_transaction_manager.callbacks.on_server_read(
            ConnectionId::new(tcb_idx, self.server_id),
            trans_id,
            handle,
            attr_type,
            offset,
        );

        pending_transaction.wait(&self.callback_transaction_manager).await
    }

    async fn write(
        &self,
        tcb_idx: TransportIndex,
        handle: AttHandle,
        attr_type: AttributeBackingType,
        write_type: GattWriteRequestType,
        data: &[u8],
    ) -> Result<(), AttErrorCode> {
        let conn_id = ConnectionId::new(tcb_idx, self.server_id);

        let pending_transaction = self
            .callback_transaction_manager
            .pending_transactions
            .borrow_mut()
            .start_new_transaction(conn_id);
        let trans_id = pending_transaction.trans_id;

        self.callback_transaction_manager.callbacks.on_server_write(
            conn_id,
            trans_id,
            handle,
            attr_type,
            GattWriteType::Request(write_type),
            data,
        );

        // the data passed back is irrelevant for write requests
        pending_transaction.wait(&self.callback_transaction_manager).await.map(|_| ())
    }

    fn write_no_response(
        &self,
        tcb_idx: TransportIndex,
        handle: AttHandle,
        attr_type: AttributeBackingType,
        data: &[u8],
    ) {
        let conn_id = ConnectionId::new(tcb_idx, self.server_id);

        let trans_id = self
            .callback_transaction_manager
            .pending_transactions
            .borrow_mut()
            .alloc_transaction_id();
        self.callback_transaction_manager.callbacks.on_server_write(
            conn_id,
            trans_id,
            handle,
            attr_type,
            GattWriteType::Command,
            data,
        );
    }

    async fn execute(
        &self,
        tcb_idx: TransportIndex,
        decision: TransactionDecision,
    ) -> Result<(), AttErrorCode> {
        let conn_id = ConnectionId::new(tcb_idx, self.server_id);

        let pending_transaction = self
            .callback_transaction_manager
            .pending_transactions
            .borrow_mut()
            .start_new_transaction(conn_id);
        let trans_id = pending_transaction.trans_id;

        self.callback_transaction_manager.callbacks.on_execute(conn_id, trans_id, decision);

        // the data passed back is irrelevant for execute requests
        pending_transaction.wait(&self.callback_transaction_manager).await.map(|_| ())
    }
}

#[cfg(test)]
mod tests {
    use std::rc::Rc;
    use std::time::Duration;

    use super::{
        CallbackResponseError, CallbackTransactionManager, GattWriteRequestType, GattWriteType,
        RawGattDatastore, TransactionDecision,
    };
    use crate::gatt::ffi::AttributeBackingType;
    use crate::gatt::ids::{AttHandle, ConnectionId, ServerId, TransactionId, TransportIndex};
    use crate::gatt::mocks::mock_callbacks::{MockCallbackEvents, MockCallbacks};
    use crate::packets::att::AttErrorCode;
    use crate::utils::task::block_on_locally;
    use tokio::sync::mpsc::UnboundedReceiver;
    use tokio::task::spawn_local;
    use tokio::time::Instant;

    const TCB_IDX: TransportIndex = TransportIndex(1);
    const SERVER_ID: ServerId = ServerId(2);

    const CONN_ID: ConnectionId = ConnectionId::new(TCB_IDX, SERVER_ID);

    const HANDLE_1: AttHandle = AttHandle(3);
    const BACKING_TYPE: AttributeBackingType = AttributeBackingType::Descriptor;

    const OFFSET: u32 = 12;
    const WRITE_REQUEST_TYPE: GattWriteRequestType = GattWriteRequestType::Prepare { offset: 7 };

    fn initialize_manager_with_connection(
    ) -> (Rc<CallbackTransactionManager>, UnboundedReceiver<MockCallbackEvents>) {
        let (callbacks, callbacks_rx) = MockCallbacks::new();
        let callback_manager = Rc::new(CallbackTransactionManager::new(Rc::new(callbacks)));
        (callback_manager, callbacks_rx)
    }

    async fn pull_trans_id(events_rx: &mut UnboundedReceiver<MockCallbackEvents>) -> TransactionId {
        match events_rx.recv().await.unwrap() {
            MockCallbackEvents::OnServerRead(_, trans_id, _, _, _) => trans_id,
            MockCallbackEvents::OnServerWrite(_, trans_id, _, _, _, _) => trans_id,
            MockCallbackEvents::OnExecute(_, trans_id, _) => trans_id,
            _ => unimplemented!(),
        }
    }

    #[test]
    fn test_read_characteristic_callback() {
        block_on_locally(async {
            crate::utils::init_logging();

            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();

            // act: start read operation
            spawn_local(async move {
                callback_manager
                    .get_datastore(SERVER_ID)
                    .read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE)
                    .await
            });

            // assert: verify the read callback is received
            let MockCallbackEvents::OnServerRead(CONN_ID, _, HANDLE_1, BACKING_TYPE, OFFSET) =
                callbacks_rx.recv().await.unwrap()
            else {
                unreachable!()
            };
        });
    }

    #[test]
    fn test_read_characteristic_response() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();
            let data = [1, 2];

            // act: start read operation
            let datastore = callback_manager.get_datastore(SERVER_ID);
            let pending_read = spawn_local(async move {
                datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await
            });
            // provide a response
            let trans_id = pull_trans_id(&mut callbacks_rx).await;
            callback_manager.send_response(CONN_ID, trans_id, Ok(data.to_vec())).unwrap();

            // assert: that the supplied data was correctly read
            assert_eq!(pending_read.await.unwrap(), Ok(data.to_vec()));
        });
    }

    #[test]
    fn test_sequential_reads() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();
            let data1 = [1, 2];
            let data2 = [3, 4];

            // act: start read operation
            let datastore = callback_manager.get_datastore(SERVER_ID);
            let pending_read_1 = spawn_local(async move {
                datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await
            });
            // respond to first
            let trans_id = pull_trans_id(&mut callbacks_rx).await;
            callback_manager.send_response(CONN_ID, trans_id, Ok(data1.to_vec())).unwrap();

            // do a second read operation
            let datastore = callback_manager.get_datastore(SERVER_ID);
            let pending_read_2 = spawn_local(async move {
                datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await
            });
            // respond to second
            let trans_id = pull_trans_id(&mut callbacks_rx).await;
            callback_manager.send_response(CONN_ID, trans_id, Ok(data2.to_vec())).unwrap();

            // assert: that both operations got the correct response
            assert_eq!(pending_read_1.await.unwrap(), Ok(data1.to_vec()));
            assert_eq!(pending_read_2.await.unwrap(), Ok(data2.to_vec()));
        });
    }

    #[test]
    fn test_concurrent_reads() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();
            let data1 = [1, 2];
            let data2 = [3, 4];

            // act: start read operation
            let datastore = callback_manager.get_datastore(SERVER_ID);
            let pending_read_1 = spawn_local(async move {
                datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await
            });

            // do a second read operation
            let datastore = callback_manager.get_datastore(SERVER_ID);
            let pending_read_2 = spawn_local(async move {
                datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await
            });

            // respond to first
            let trans_id = pull_trans_id(&mut callbacks_rx).await;
            callback_manager.send_response(CONN_ID, trans_id, Ok(data1.to_vec())).unwrap();

            // respond to second
            let trans_id = pull_trans_id(&mut callbacks_rx).await;
            callback_manager.send_response(CONN_ID, trans_id, Ok(data2.to_vec())).unwrap();

            // assert: that both operations got the correct response
            assert_eq!(pending_read_1.await.unwrap(), Ok(data1.to_vec()));
            assert_eq!(pending_read_2.await.unwrap(), Ok(data2.to_vec()));
        });
    }

    #[test]
    fn test_distinct_transaction_ids() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();

            // act: start two read operations concurrently
            let datastore = callback_manager.get_datastore(SERVER_ID);
            spawn_local(
                async move { datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await },
            );
            let datastore = callback_manager.get_datastore(SERVER_ID);
            spawn_local(
                async move { datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await },
            );

            // pull both trans_ids
            let trans_id_1 = pull_trans_id(&mut callbacks_rx).await;
            let trans_id_2 = pull_trans_id(&mut callbacks_rx).await;

            // assert: that the trans_ids are distinct
            assert_ne!(trans_id_1, trans_id_2);
        });
    }

    #[test]
    fn test_invalid_trans_id() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();
            let data = [1, 2];

            // act: start a read operation
            let datastore = callback_manager.get_datastore(SERVER_ID);
            spawn_local(
                async move { datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await },
            );
            // respond with the correct conn_id but an invalid trans_id
            let trans_id = pull_trans_id(&mut callbacks_rx).await;
            let invalid_trans_id = TransactionId(trans_id.0 + 1);
            let err = callback_manager
                .send_response(CONN_ID, invalid_trans_id, Ok(data.to_vec()))
                .unwrap_err();

            // assert
            assert_eq!(err, CallbackResponseError::NonExistentTransaction(invalid_trans_id));
        });
    }

    #[test]
    fn test_invalid_conn_id() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();
            let data = [1, 2];

            // act: start a read operation
            let datastore = callback_manager.get_datastore(SERVER_ID);
            spawn_local(
                async move { datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await },
            );
            // respond with the correct trans_id but an invalid conn_id
            let trans_id = pull_trans_id(&mut callbacks_rx).await;
            let invalid_conn_id = ConnectionId(CONN_ID.0 + 1);
            let err = callback_manager
                .send_response(invalid_conn_id, trans_id, Ok(data.to_vec()))
                .unwrap_err();

            // assert
            assert_eq!(err, CallbackResponseError::NonExistentTransaction(trans_id));
        });
    }

    #[test]
    fn test_write_characteristic_callback() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();

            // act: start write operation
            let data = [1, 2];
            spawn_local(async move {
                callback_manager
                    .get_datastore(SERVER_ID)
                    .write(TCB_IDX, HANDLE_1, BACKING_TYPE, WRITE_REQUEST_TYPE, &data)
                    .await
            });

            // assert: verify the write callback is received
            let MockCallbackEvents::OnServerWrite(
                CONN_ID,
                _,
                HANDLE_1,
                BACKING_TYPE,
                GattWriteType::Request(WRITE_REQUEST_TYPE),
                recv_data,
            ) = callbacks_rx.recv().await.unwrap()
            else {
                unreachable!()
            };
            assert_eq!(recv_data, data);
        });
    }

    #[test]
    fn test_write_characteristic_response() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();

            // act: start write operation
            let data = [1, 2];
            let datastore = callback_manager.get_datastore(SERVER_ID);
            let pending_write = spawn_local(async move {
                datastore
                    .write(TCB_IDX, HANDLE_1, BACKING_TYPE, GattWriteRequestType::Request, &data)
                    .await
            });
            // provide a response with some error code
            let trans_id = pull_trans_id(&mut callbacks_rx).await;
            callback_manager
                .send_response(CONN_ID, trans_id, Err(AttErrorCode::WriteNotPermitted))
                .unwrap();

            // assert: that the error code was received
            assert_eq!(pending_write.await.unwrap(), Err(AttErrorCode::WriteNotPermitted));
        });
    }

    #[test]
    fn test_response_timeout() {
        block_on_locally(async {
            // arrange
            let (callback_manager, _callbacks_rx) = initialize_manager_with_connection();

            // act: start operation
            let time_sent = Instant::now();
            let datastore = callback_manager.get_datastore(SERVER_ID);
            let pending_write = spawn_local(async move {
                datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await
            });

            // assert: that we time-out after 15s
            assert_eq!(pending_write.await.unwrap(), Err(AttErrorCode::UnlikelyError));
            let time_slept = Instant::now().duration_since(time_sent);
            assert!(time_slept > Duration::from_secs(14));
            assert!(time_slept < Duration::from_secs(16));
        });
    }

    #[test]
    fn test_transaction_cleanup_after_timeout() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();

            // act: start an operation
            let datastore = callback_manager.get_datastore(SERVER_ID);
            let pending = spawn_local(async move {
                datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await
            });
            let trans_id = pull_trans_id(&mut callbacks_rx).await;
            // let it time out
            assert_eq!(pending.await.unwrap(), Err(AttErrorCode::UnlikelyError));
            // try responding to it now
            let resp =
                callback_manager.send_response(CONN_ID, trans_id, Err(AttErrorCode::InvalidHandle));

            // assert: the response failed
            assert_eq!(resp, Err(CallbackResponseError::NonExistentTransaction(trans_id)));
        });
    }

    #[test]
    fn test_listener_hang_up() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();

            // act: start an operation
            let datastore = callback_manager.get_datastore(SERVER_ID);
            let pending = spawn_local(async move {
                datastore.read(TCB_IDX, HANDLE_1, OFFSET, BACKING_TYPE).await
            });
            let trans_id = pull_trans_id(&mut callbacks_rx).await;
            // cancel the listener, wait for it to stop
            pending.abort();
            pending.await.unwrap_err();
            // try responding to it now
            let resp =
                callback_manager.send_response(CONN_ID, trans_id, Err(AttErrorCode::InvalidHandle));

            // assert: we get the expected error
            assert_eq!(resp, Err(CallbackResponseError::ListenerHungUp(trans_id)));
        });
    }

    #[test]
    fn test_write_no_response_callback() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();

            // act: start write_no_response operation
            let data = [1, 2];
            callback_manager.get_datastore(SERVER_ID).write_no_response(
                TCB_IDX,
                HANDLE_1,
                BACKING_TYPE,
                &data,
            );

            // assert: verify the write callback is received
            let MockCallbackEvents::OnServerWrite(
                CONN_ID,
                _,
                HANDLE_1,
                BACKING_TYPE,
                GattWriteType::Command,
                recv_data,
            ) = callbacks_rx.recv().await.unwrap()
            else {
                unreachable!()
            };
            assert_eq!(recv_data, data);
        });
    }

    #[test]
    fn test_execute_callback() {
        block_on_locally(async {
            // arrange
            let (callback_manager, mut callbacks_rx) = initialize_manager_with_connection();

            // act: start execute operation
            spawn_local(async move {
                callback_manager
                    .get_datastore(SERVER_ID)
                    .execute(TCB_IDX, TransactionDecision::Execute)
                    .await
            });

            // assert: verify the execute callback is received
            let MockCallbackEvents::OnExecute(CONN_ID, _, TransactionDecision::Execute) =
                callbacks_rx.recv().await.unwrap()
            else {
                unreachable!()
            };
        });
    }
}
