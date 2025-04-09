use crate::gatt::server::att_client::WeakAttClient;
use crate::packets::att;
use pdl_runtime::EncodeError;

pub async fn handle_read_request(
    request: att::AttReadRequest,
    mtu: usize,
    client: &WeakAttClient,
) -> Result<att::Att, EncodeError> {
    let handle = request.attribute_handle.into();

    match client.read_attribute(handle).await {
        Ok(mut data) => {
            // as per 5.3 3F 3.4.4.4 ATT_READ_RSP, we truncate to MTU - 1
            data.truncate(mtu - 1);
            att::AttReadResponse { value: data }.try_into()
        }
        Err(error_code) => att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::ReadRequest,
            handle_in_error: handle.into(),
            error_code,
        }
        .try_into(),
    }
}

pub async fn handle_read_blob_request(
    request: att::AttReadBlobRequest,
    mtu: usize,
    client: &WeakAttClient,
) -> Result<att::Att, EncodeError> {
    let handle = request.attribute_handle.into();

    match client.read_attribute(handle).await {
        Ok(data) => {
            let offset = request.offset as usize;
            if offset > data.len() {
                return att::AttErrorResponse {
                    opcode_in_error: att::AttOpcode::ReadBlobRequest,
                    handle_in_error: handle.into(),
                    error_code: att::AttErrorCode::InvalidOffset,
                }
                .try_into();
            }

            att::AttReadBlobResponse {
                value: data[offset..std::cmp::min(offset + mtu - 1, data.len())].into(),
            }
            .try_into()
        }
        Err(error_code) => att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::ReadBlobRequest,
            handle_in_error: handle.into(),
            error_code,
        }
        .try_into(),
    }
}

pub async fn handle_read_multiple_variable_request(
    request: att::AttReadMultipleVariableRequest,
    mtu: usize,
    client: &WeakAttClient,
) -> Result<att::Att, EncodeError> {
    if request.attribute_handles.len() < 2 {
        return att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::ReadMultipleVariableRequest,
            handle_in_error: att::AttHandle { handle: 0 },
            error_code: att::AttErrorCode::InvalidPdu,
        }
        .try_into();
    }
    let mut response = att::AttReadMultipleVariableResponse {
        length_values: Vec::with_capacity(request.attribute_handles.len()),
    };
    let mut space = mtu - 1; // -1 for op code.
    for handle in request.attribute_handles {
        match client.read_attribute(handle.clone().into()).await {
            Ok(data) => {
                if space >= 2 {
                    let amount = std::cmp::min(data.len(), space - 2);
                    let Ok(length) = data.len().try_into() else {
                        // The returned data is longer than 65535 bytes.
                        return att::AttErrorResponse {
                            opcode_in_error: att::AttOpcode::ReadMultipleVariableRequest,
                            handle_in_error: handle,
                            error_code: att::AttErrorCode::UnlikelyError,
                        }
                        .try_into();
                    };
                    response
                        .length_values
                        .push(att::LengthValue { length, value: data[..amount].into() });
                    space -= 2 + amount;
                }
            }
            Err(error_code) => {
                return att::AttErrorResponse {
                    opcode_in_error: att::AttOpcode::ReadMultipleVariableRequest,
                    handle_in_error: handle,
                    error_code,
                }
                .try_into()
            }
        }
    }
    response.try_into()
}

#[cfg(test)]
mod test {
    use super::*;

    use crate::core::shared_box::SharedBox;
    use crate::core::uuid::Uuid;
    use crate::gatt::ids::{AttHandle, TransportIndex};
    use crate::gatt::server::att_client::AttClient;
    use crate::gatt::server::att_database::{AttAttribute, AttPermissions};
    use crate::gatt::server::gatt_database::GattDatabase;
    use crate::gatt::server::test::test_att_db::new_test_database;
    use crate::packets::att;

    const TCB_IDX: TransportIndex = TransportIndex(1);

    fn make_db_with_handle_and_value(handle: u16, value: Vec<u8>) -> SharedBox<GattDatabase> {
        new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(handle),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            value,
        )])
    }

    fn do_read_request_with_handle_and_mtu(
        handle: u16,
        mtu: usize,
        client: &WeakAttClient,
    ) -> Result<att::Att, EncodeError> {
        let att_view = att::AttReadRequest { attribute_handle: AttHandle(handle).into() };
        tokio_test::block_on(handle_read_request(att_view, mtu, client))
    }

    #[test]
    fn test_simple_read() {
        let db = make_db_with_handle_and_value(3, vec![4, 5]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        let response = do_read_request_with_handle_and_mtu(3, 31, &client.downgrade());

        assert_eq!(response, att::AttReadResponse { value: vec![4, 5] }.try_into());
    }

    #[test]
    fn test_truncated_read() {
        let db = make_db_with_handle_and_value(3, vec![4, 5]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let response = do_read_request_with_handle_and_mtu(3, 2, &client.downgrade());

        // assert
        assert_eq!(response, att::AttReadResponse { value: vec![4] }.try_into());
    }

    #[test]
    fn test_missed_read() {
        let db = make_db_with_handle_and_value(3, vec![4, 5]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let response = do_read_request_with_handle_and_mtu(4, 31, &client.downgrade());

        // assert
        assert_eq!(
            response,
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::ReadRequest,
                handle_in_error: AttHandle(4).into(),
                error_code: att::AttErrorCode::InvalidHandle,
            }
            .try_into()
        );
    }

    fn make_db_with_unreadable_handle(handle: u16) -> SharedBox<GattDatabase> {
        new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(handle),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::empty(),
            },
            vec![],
        )])
    }

    #[test]
    fn test_not_readable() {
        let db = make_db_with_unreadable_handle(3);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let response = do_read_request_with_handle_and_mtu(3, 31, &client.downgrade());

        // assert
        assert_eq!(
            response,
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::ReadRequest,
                handle_in_error: AttHandle(3).into(),
                error_code: att::AttErrorCode::ReadNotPermitted,
            }
            .try_into()
        );
    }
}
