use crate::gatt::server::att_database::AttDatabase;
use crate::packets::att;
use pdl_runtime::EncodeError;

pub async fn handle_read_request<T: AttDatabase>(
    request: att::AttReadRequest,
    mtu: usize,
    db: &T,
) -> Result<att::Att, EncodeError> {
    let handle = request.attribute_handle.into();

    match db.read_attribute(handle).await {
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

pub async fn handle_read_blob_request<T: AttDatabase>(
    request: att::AttReadBlobRequest,
    mtu: usize,
    db: &T,
) -> Result<att::Att, EncodeError> {
    let handle = request.attribute_handle.into();

    match db.read_attribute(handle).await {
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

#[cfg(test)]
mod test {
    use super::*;

    use crate::core::uuid::Uuid;
    use crate::gatt::ids::AttHandle;
    use crate::gatt::server::att_database::{AttAttribute, AttPermissions};
    use crate::gatt::server::test::test_att_db::TestAttDatabase;
    use crate::packets::att;

    fn make_db_with_handle_and_value(handle: u16, value: Vec<u8>) -> TestAttDatabase {
        TestAttDatabase::new(vec![(
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
        db: &TestAttDatabase,
    ) -> Result<att::Att, EncodeError> {
        let att_view = att::AttReadRequest { attribute_handle: AttHandle(handle).into() };
        tokio_test::block_on(handle_read_request(att_view, mtu, db))
    }

    #[test]
    fn test_simple_read() {
        let db = make_db_with_handle_and_value(3, vec![4, 5]);

        let response = do_read_request_with_handle_and_mtu(3, 31, &db);

        assert_eq!(response, att::AttReadResponse { value: vec![4, 5] }.try_into());
    }

    #[test]
    fn test_truncated_read() {
        let db = make_db_with_handle_and_value(3, vec![4, 5]);

        // act
        let response = do_read_request_with_handle_and_mtu(3, 2, &db);

        // assert
        assert_eq!(response, att::AttReadResponse { value: vec![4] }.try_into());
    }

    #[test]
    fn test_missed_read() {
        let db = make_db_with_handle_and_value(3, vec![4, 5]);

        // act
        let response = do_read_request_with_handle_and_mtu(4, 31, &db);

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

    fn make_db_with_unreadable_handle(handle: u16) -> TestAttDatabase {
        TestAttDatabase::new(vec![(
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

        // act
        let response = do_read_request_with_handle_and_mtu(3, 31, &db);

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
