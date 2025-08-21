use crate::gatt::callbacks::TransactionDecision;
use crate::gatt::server::att_client::WeakAttClient;
use crate::packets::att;
use pdl_runtime::EncodeError;

pub async fn handle_write_request(
    request: att::AttWriteRequest,
    client: &WeakAttClient,
) -> Result<att::Att, EncodeError> {
    let handle = request.handle.into();
    let value = request.value;
    match client.write_attribute(handle, &value).await {
        Ok(()) => att::AttWriteResponse {}.try_into(),
        Err(error_code) => att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::WriteRequest,
            handle_in_error: handle.into(),
            error_code,
        }
        .try_into(),
    }
}

pub async fn handle_prepare_write_request(
    request: att::AttPrepareWriteRequest,
    client: &WeakAttClient,
) -> Result<att::Att, EncodeError> {
    let att::AttPrepareWriteRequest { handle, offset, value } = request;
    match client.prepare_write_attribute(handle.clone().into(), offset as u32, &value).await {
        Ok(()) => att::AttPrepareWriteResponse { handle, offset, value }.try_into(),
        Err(error_code) => att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::PrepareWriteRequest,
            handle_in_error: handle,
            error_code,
        }
        .try_into(),
    }
}

pub async fn handle_execute_write_request(
    request: att::AttExecuteWriteRequest,
    client: &WeakAttClient,
) -> Result<att::Att, EncodeError> {
    match client
        .execute(if request.commit & 1 == 1 {
            TransactionDecision::Execute
        } else {
            TransactionDecision::Cancel
        })
        .await
    {
        Ok(()) => att::AttExecuteWriteResponse {}.try_into(),
        Err((handle, error_code)) => att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::ExecuteWriteRequest,
            handle_in_error: handle.into(),
            error_code,
        }
        .try_into(),
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use tokio_test::block_on;

    use crate::core::uuid::Uuid;
    use crate::gatt::ids::{AttHandle, TransportIndex};
    use crate::gatt::server::att_client::AttClient;
    use crate::gatt::server::att_database::{AttAttribute, AttPermissions};
    use crate::gatt::server::test::test_att_db::new_test_database;
    use crate::packets::att;

    const TCB_IDX: TransportIndex = TransportIndex(1);

    #[test]
    fn test_successful_write() {
        // arrange: db with one writable attribute
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(1),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
            },
            vec![],
        )]);
        let data = vec![1, 2];
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: write to the attribute
        let att_view = att::AttWriteRequest { handle: AttHandle(1).into(), value: data.clone() };
        let resp = block_on(handle_write_request(att_view, &client.downgrade()));

        // assert: that the write succeeded
        assert_eq!(resp, att::AttWriteResponse {}.try_into());
        assert_eq!(block_on(client.read_attribute(AttHandle(1))).unwrap(), data);
    }

    #[test]
    fn test_failed_write() {
        // arrange: db with no writable attributes
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(1),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            vec![],
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        // act: write to the attribute
        let att_view = att::AttWriteRequest { handle: AttHandle(1).into(), value: vec![1, 2] };
        let resp = block_on(handle_write_request(att_view, &client.downgrade()));

        // assert: that the write failed
        assert_eq!(
            resp,
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::WriteRequest,
                handle_in_error: AttHandle(1).into(),
                error_code: att::AttErrorCode::WriteNotPermitted
            }
            .try_into()
        );
    }

    #[test]
    fn test_prepare_write() {
        // arrange: db with one writable attribute
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(1),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
            },
            vec![0; 10],
        )]);
        let data = vec![1, 2];
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: prepare write to the attribute
        let att_view = att::AttPrepareWriteRequest {
            handle: AttHandle(1).into(),
            offset: 2,
            value: data.clone(),
        };
        let resp = block_on(handle_prepare_write_request(att_view.clone(), &client.downgrade()));

        // assert: that the prepare write succeeded and returns the same values
        assert_eq!(
            resp,
            att::AttPrepareWriteResponse {
                handle: att_view.handle,
                offset: att_view.offset,
                value: att_view.value
            }
            .try_into()
        );
        // assert: that the value has not been written yet
        assert_eq!(block_on(client.read_attribute(AttHandle(1))).unwrap(), vec![0; 10]);
    }

    #[test]
    fn test_execute_write() {
        // arrange: db with one writable attribute and a pending prepared write
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(1),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
            },
            vec![0; 10],
        )]);
        let data = vec![1, 2];
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let att_view = att::AttPrepareWriteRequest {
            handle: AttHandle(1).into(),
            offset: 2,
            value: data.clone(),
        };
        block_on(handle_prepare_write_request(att_view.clone(), &client.downgrade())).unwrap();

        // act: execute write
        let execute_view = att::AttExecuteWriteRequest { commit: 1 };
        let resp = block_on(handle_execute_write_request(execute_view, &client.downgrade()));

        // assert: that the execute write succeeded
        assert_eq!(resp, att::AttExecuteWriteResponse {}.try_into());
        // assert: that the value has been written
        let mut expected_value = vec![0; 10];
        expected_value[2..4].copy_from_slice(&data);
        assert_eq!(block_on(client.read_attribute(AttHandle(1))).unwrap(), expected_value);
    }

    #[test]
    fn test_cancel_write() {
        // arrange: db with one writable attribute and a pending prepared write
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(1),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
            },
            vec![0; 10],
        )]);
        let data = vec![1, 2];
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);
        let att_view = att::AttPrepareWriteRequest {
            handle: AttHandle(1).into(),
            offset: 2,
            value: data.clone(),
        };
        block_on(handle_prepare_write_request(att_view.clone(), &client.downgrade())).unwrap();

        // act: cancel write
        let execute_view = att::AttExecuteWriteRequest { commit: 0 };
        let resp = block_on(handle_execute_write_request(execute_view, &client.downgrade()));

        // assert: that the execute write succeeded
        assert_eq!(resp, att::AttExecuteWriteResponse {}.try_into());
        // assert: that the value has not been written
        assert_eq!(block_on(client.read_attribute(AttHandle(1))).unwrap(), vec![0; 10]);
    }
}
