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

#[cfg(test)]
mod test {
    use super::*;

    use tokio_test::block_on;

    use crate::core::uuid::Uuid;
    use crate::gatt::ids::{AttHandle, TransportIndex};
    use crate::gatt::server::att_client::AttClient;
    use crate::gatt::server::att_database::AttAttribute;
    use crate::gatt::server::gatt_database::AttPermissions;
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
}
