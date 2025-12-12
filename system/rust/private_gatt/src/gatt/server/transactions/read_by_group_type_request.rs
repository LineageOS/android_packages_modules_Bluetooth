use crate::core::uuid::Uuid;
use crate::gatt::server::att_client::WeakAttClient;
use crate::gatt::server::gatt_database::{
    PRIMARY_SERVICE_DECLARATION_UUID, SECONDARY_SERVICE_DECLARATION_UUID,
};
use crate::packets::att::{self, AttErrorCode};
use pdl_runtime::EncodeError;

use super::helpers::att_filter_by_size_type::{
    filter_read_attributes_by_size_type, AttributeWithValue,
};
use super::helpers::att_grouping::find_group_end;
use super::helpers::att_range_filter::filter_to_range;
use super::helpers::payload_accumulator::PayloadAccumulator;

pub async fn handle_read_by_group_type_request(
    request: att::AttReadByGroupTypeRequest,
    mtu: usize,
    client: &WeakAttClient,
) -> Result<att::Att, EncodeError> {
    // As per spec (5.3 Vol 3F 3.4.4.9)
    // > If an attribute in the set of requested attributes would cause an
    // > ATT_ERROR_RSP PDU then this attribute cannot be included in an
    // > ATT_READ_BY_GROUP_TYPE_RSP PDU and the attributes before this
    // > attribute shall be returned.
    //
    // Thus, we populate this response on failure, but only return it if no prior
    // matches were accumulated.
    let mut failure_response = att::AttErrorResponse {
        opcode_in_error: request.opcode(),
        handle_in_error: request.starting_handle.clone(),
        // the default error code if we just fail to find anything
        error_code: AttErrorCode::AttributeNotFound,
    };

    let Ok(group_type): Result<Uuid, _> = request.attribute_group_type.try_into() else {
        failure_response.error_code = AttErrorCode::InvalidPdu;
        return failure_response.try_into();
    };

    let all_attrs = client.list_attributes();
    let Some(filtered) = filter_to_range(
        request.starting_handle.into(),
        request.ending_handle.into(),
        all_attrs.iter(),
    ) else {
        failure_response.error_code = AttErrorCode::InvalidHandle;
        return failure_response.try_into();
    };

    // As per Core Spec 5.3 Vol 3G 2.5.3 Attribute Grouping, only these UUIDs are
    // valid for the ATT_READ_BY_GROUP_TYPE_REQ PDU (even though other grouping
    // UUIDs do exist)
    if !matches!(group_type, PRIMARY_SERVICE_DECLARATION_UUID | SECONDARY_SERVICE_DECLARATION_UUID)
    {
        failure_response.error_code = AttErrorCode::UnsupportedGroupType;
        return failure_response.try_into();
    }

    // MTU-2 limit comes from Core Spec 5.3 Vol 3F 3.4.4.9
    let mut matches = PayloadAccumulator::new(mtu - 2);

    // MTU-6 limit comes from Core Spec 5.3 Vol 3F 3.4.4.9
    match filter_read_attributes_by_size_type(client, filtered, group_type, mtu - 6).await {
        Ok(attrs) => {
            for AttributeWithValue { attr, value } in attrs {
                if !matches.push(att::AttReadByGroupTypeDataElement {
                    handle: attr.handle.into(),
                    end_group_handle: find_group_end(&all_attrs, &attr)
                        .expect("should never be None, since grouping UUID was validated earlier")
                        .handle
                        .into(),
                    value,
                }) {
                    break;
                }
            }
        }
        Err(err) => {
            failure_response.error_code = err;
            return failure_response.try_into();
        }
    }

    if matches.is_empty() {
        failure_response.try_into()
    } else {
        att::AttReadByGroupTypeResponse { data: matches.into_vec() }.try_into()
    }
}

#[cfg(test)]
mod test {
    use crate::gatt::ids::{AttHandle, TransportIndex};
    use crate::gatt::server::att_client::AttClient;
    use crate::gatt::server::att_database::AttAttribute;
    use crate::gatt::server::gatt_database::{AttPermissions, CHARACTERISTIC_UUID};
    use crate::gatt::server::test::test_att_db::new_test_database;
    use crate::packets::att;

    use super::*;
    const TCB_IDX: TransportIndex = TransportIndex(1);

    #[test]
    fn test_simple_grouping() {
        // arrange: one service with a child attribute, another service with no children
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: PRIMARY_SERVICE_DECLARATION_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: CHARACTERISTIC_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![5, 6],
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: PRIMARY_SERVICE_DECLARATION_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![6, 7],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let att_view = att::AttReadByGroupTypeRequest {
            starting_handle: AttHandle(2).into(),
            ending_handle: AttHandle(6).into(),
            attribute_group_type: PRIMARY_SERVICE_DECLARATION_UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_group_type_request(
            att_view,
            31,
            &client.downgrade(),
        ));

        // assert: we identified both service groups
        assert_eq!(
            response,
            att::AttReadByGroupTypeResponse {
                data: vec![
                    att::AttReadByGroupTypeDataElement {
                        handle: AttHandle(3).into(),
                        end_group_handle: AttHandle(4).into(),
                        value: vec![4, 5],
                    },
                    att::AttReadByGroupTypeDataElement {
                        handle: AttHandle(5).into(),
                        end_group_handle: AttHandle(5).into(),
                        value: vec![6, 7],
                    },
                ]
            }
            .try_into()
        );
    }

    #[test]
    fn test_invalid_group_type() {
        // arrange
        let db = new_test_database(vec![]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: try using an unsupported group type
        let att_view = att::AttReadByGroupTypeRequest {
            starting_handle: AttHandle(2).into(),
            ending_handle: AttHandle(6).into(),
            attribute_group_type: CHARACTERISTIC_UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_group_type_request(
            att_view,
            31,
            &client.downgrade(),
        ));

        // assert: got UNSUPPORTED_GROUP_TYPE
        assert_eq!(
            response,
            att::AttErrorResponse {
                handle_in_error: AttHandle(2).into(),
                opcode_in_error: att::AttOpcode::ReadByGroupTypeRequest,
                error_code: AttErrorCode::UnsupportedGroupType,
            }
            .try_into()
        );
    }

    #[test]
    fn test_range_validation() {
        // arrange: an empty (irrelevant) db
        let db = new_test_database(vec![]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: query with an invalid attribute range
        let att_view = att::AttReadByGroupTypeRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(2).into(),
            attribute_group_type: PRIMARY_SERVICE_DECLARATION_UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_group_type_request(
            att_view,
            31,
            &client.downgrade(),
        ));

        // assert: we return an INVALID_HANDLE error
        assert_eq!(
            response,
            att::AttErrorResponse {
                handle_in_error: AttHandle(3).into(),
                opcode_in_error: att::AttOpcode::ReadByGroupTypeRequest,
                error_code: AttErrorCode::InvalidHandle,
            }
            .try_into()
        )
    }

    #[test]
    fn test_attribute_truncation() {
        // arrange: one service with a value of length 5
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: PRIMARY_SERVICE_DECLARATION_UUID,
                permissions: AttPermissions::READABLE,
            },
            vec![1, 2, 3, 4, 5],
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: read the service value with MTU = 7, so the value is truncated to MTU-6
        // = 1
        let att_view = att::AttReadByGroupTypeRequest {
            starting_handle: AttHandle(2).into(),
            ending_handle: AttHandle(6).into(),
            attribute_group_type: PRIMARY_SERVICE_DECLARATION_UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_group_type_request(
            att_view,
            7,
            &client.downgrade(),
        ));

        // assert: we identified both service groups
        assert_eq!(
            response,
            att::AttReadByGroupTypeResponse {
                data: vec![att::AttReadByGroupTypeDataElement {
                    handle: AttHandle(3).into(),
                    end_group_handle: AttHandle(3).into(),
                    value: vec![1],
                },]
            }
            .try_into()
        );
    }

    #[test]
    fn test_limit_total_size() {
        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: PRIMARY_SERVICE_DECLARATION_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5, 6],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: PRIMARY_SERVICE_DECLARATION_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![5, 6, 7],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: read with MTU = 9, so we can only fit the first attribute (untruncated)
        let att_view = att::AttReadByGroupTypeRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(6).into(),
            attribute_group_type: PRIMARY_SERVICE_DECLARATION_UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_group_type_request(
            att_view,
            9,
            &client.downgrade(),
        ));

        // assert: we return only the first attribute
        assert_eq!(
            response,
            att::AttReadByGroupTypeResponse {
                data: vec![att::AttReadByGroupTypeDataElement {
                    handle: AttHandle(3).into(),
                    end_group_handle: AttHandle(3).into(),
                    value: vec![4, 5, 6],
                },]
            }
            .try_into()
        )
    }

    #[test]
    fn test_group_end_outside_range() {
        // arrange: one service with a child attribute
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: PRIMARY_SERVICE_DECLARATION_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: CHARACTERISTIC_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![5, 6],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: search in an interval that includes the service but not its child
        let att_view = att::AttReadByGroupTypeRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(3).into(),
            attribute_group_type: PRIMARY_SERVICE_DECLARATION_UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_group_type_request(
            att_view,
            31,
            &client.downgrade(),
        ));

        // assert: the end_group_handle is correct, even though it exceeds the query
        // interval
        assert_eq!(
            response,
            att::AttReadByGroupTypeResponse {
                data: vec![att::AttReadByGroupTypeDataElement {
                    handle: AttHandle(3).into(),
                    end_group_handle: AttHandle(4).into(),
                    value: vec![4, 5],
                },]
            }
            .try_into()
        );
    }

    #[test]
    fn test_no_results() {
        // arrange: read out of the bounds where attributes of interest exist
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: PRIMARY_SERVICE_DECLARATION_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5, 6],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: PRIMARY_SERVICE_DECLARATION_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5, 6],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let att_view = att::AttReadByGroupTypeRequest {
            starting_handle: AttHandle(5).into(),
            ending_handle: AttHandle(6).into(),
            attribute_group_type: PRIMARY_SERVICE_DECLARATION_UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_group_type_request(
            att_view,
            31,
            &client.downgrade(),
        ));

        // assert: we return ATTRIBUTE_NOT_FOUND
        assert_eq!(
            response,
            att::AttErrorResponse {
                handle_in_error: AttHandle(5).into(),
                opcode_in_error: att::AttOpcode::ReadByGroupTypeRequest,
                error_code: AttErrorCode::AttributeNotFound,
            }
            .try_into()
        )
    }
}
