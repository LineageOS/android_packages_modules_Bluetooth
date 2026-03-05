use crate::bindings::root as bindings;
use crate::btif::{ptr_to_vec, BluetoothInterface, BtStatus, RawAddress, Uuid};
use crate::topstack::get_dispatchers;

use num_derive::{FromPrimitive, ToPrimitive};
use num_traits::cast::{FromPrimitive, ToPrimitive};

use std::fmt::{Debug, Display, Formatter, Result};
use std::sync::{Arc, Mutex};

use std::ffi::CString;

use topshim_macros::{cb_variant, gen_cxx_extern_trivial, log_args};

pub type BtGattValue = bindings::btgatt_value_t;

#[gen_cxx_extern_trivial]
pub type BtGattReadParams = bindings::btgatt_read_params_t;

#[gen_cxx_extern_trivial]
pub type BtGattNotifyParams = bindings::btgatt_notify_params_t;

#[gen_cxx_extern_trivial]
pub type BtGattDbElement = bindings::btgatt_db_element_t;

#[gen_cxx_extern_trivial]
pub type BtGattOffloadResult = bindings::btgatt_offload_result_t;

#[gen_cxx_extern_trivial]
pub type BtGattResponse = bindings::btgatt_response_t;

#[cxx::bridge(namespace = bluetooth::topshim::rust)]
pub mod ffi {
    unsafe extern "C++" {
        include!("bluetooth/types/address.h");
        #[namespace = ""]
        type RawAddress = crate::btif::RawAddress;
        #[namespace = "bluetooth"]
        type Uuid = crate::btif::Uuid;

        type BtIntf = crate::btif::ffi::BtIntf;
    }

    #[derive(Debug, Clone)]
    pub struct RustAdvertisingTrackInfo {
        monitor_handle: u8,
        scanner_id: u8,
        filter_index: u8,
        advertiser_state: u8,
        advertiser_info_present: u8,
        advertiser_address: RawAddress,
        advertiser_address_type: u8,
        tx_power: u8,
        rssi: i8,
        timestamp: u16,
        adv_packet_len: u8,
        adv_packet: Vec<u8>,
        scan_response_len: u8,
        scan_response: Vec<u8>,
    }

    // Defined in C++ and needs a translation in shim.
    #[derive(Debug, Clone)]
    pub struct RustApcfCommand {
        type_: u8,
        address: RawAddress,
        addr_type: u8,
        uuid: Uuid,
        uuid_mask: Uuid,
        name: Vec<u8>,
        company: u16,
        company_mask: u16,
        ad_type: u8,
        org_id: u8,
        tds_flags: u8,
        tds_flags_mask: u8,
        meta_data_type: u8,
        meta_data: Vec<u8>,
        data: Vec<u8>,
        data_mask: Vec<u8>,
        irk: [u8; 16],
    }

    // Defined in C++ and needs a translation in shim.
    #[derive(Debug, Clone)]
    pub struct RustMsftAdvMonitorPattern {
        pub ad_type: u8,
        pub start_byte: u8,
        pub pattern: Vec<u8>,
    }

    #[derive(Debug, Clone)]
    pub struct RustMsftAdvMonitorAddress {
        pub addr_type: u8,
        pub bd_addr: RawAddress,
    }

    // Defined in C++ and needs a translation in shim.
    #[derive(Debug, Clone)]
    pub struct RustMsftAdvMonitor {
        pub rssi_high_threshold: u8,
        pub rssi_low_threshold: u8,
        pub rssi_low_timeout: u8,
        pub rssi_sampling_period: u8,
        pub condition_type: u8,
        pub patterns: Vec<RustMsftAdvMonitorPattern>,
        pub addr_info: RustMsftAdvMonitorAddress,
    }

    unsafe extern "C++" {
        include!("topshim/gatt/gatt_shim.h");

        #[namespace = ""]
        #[cxx_name = "btgatt_db_element_t"]
        type BtGattDbElement = super::BtGattDbElement;

        #[namespace = ""]
        #[cxx_name = "btgatt_offload_result_t"]
        type BtGattOffloadResult = super::BtGattOffloadResult;

        #[namespace = ""]
        #[cxx_name = "btgatt_response_t"]
        type BtGattResponse = super::BtGattResponse;

        #[namespace = ""]
        #[cxx_name = "btgatt_notify_params_t"]
        type BtGattNotifyParams = super::BtGattNotifyParams;

        #[namespace = ""]
        #[cxx_name = "btgatt_read_params_t"]
        type BtGattReadParams = super::BtGattReadParams;

        type GattClientIntf;

        fn GetGattClientProfile(btif: &BtIntf) -> UniquePtr<GattClientIntf>;

        fn register_client(
            self: &GattClientIntf,
            uuid: Uuid,
            name: Vec<u8>,
            eatt_support: bool,
        ) -> u32;
        fn unregister_client(self: &GattClientIntf, client_if: i32) -> u32;
        #[allow(clippy::too_many_arguments)]
        fn connect(
            self: &GattClientIntf,
            client_if: i32,
            bd_addr: RawAddress,
            addr_type: u8,
            is_direct: bool,
            transport: i32,
            opportunistic: bool,
            preferred_mtu: i32,
            prefer_relax_mode: bool,
            auto_mtu_enabled: bool,
        ) -> u32;
        fn disconnect(
            self: &GattClientIntf,
            client_if: i32,
            bd_addr: RawAddress,
            conn_id: i32,
        ) -> u32;
        fn refresh(self: &GattClientIntf, client_if: i32, bd_addr: RawAddress) -> u32;

        // cxxbridge hasn't yet support Option, thus we split the btif |search_service| API into 2.
        fn search_service(self: &GattClientIntf, conn_id: i32, filter_uuid: Uuid) -> u32;
        fn search_service_all(self: &GattClientIntf, conn_id: i32) -> u32;

        fn btif_gattc_discover_service_by_uuid(self: &GattClientIntf, conn_id: i32, uuid: Uuid);
        fn read_characteristic(
            self: &GattClientIntf,
            conn_id: i32,
            handle: u16,
            auth_req: i32,
        ) -> u32;
        fn read_using_characteristic_uuid(
            self: &GattClientIntf,
            conn_id: i32,
            uuid: Uuid,
            s_handle: u16,
            e_handle: u16,
            auth_req: i32,
        ) -> u32;
        fn write_characteristic(
            self: &GattClientIntf,
            conn_id: i32,
            handle: u16,
            write_type: i32,
            auth_req: i32,
            value: Vec<u8>,
            length: usize,
        ) -> u32;
        fn read_descriptor(self: &GattClientIntf, conn_id: i32, handle: u16, auth_req: i32) -> u32;
        fn write_descriptor(
            self: &GattClientIntf,
            conn_id: i32,
            handle: u16,
            auth_req: i32,
            value: Vec<u8>,
            length: usize,
        ) -> u32;
        fn execute_write(self: &GattClientIntf, conn_id: i32, execute: i32) -> u32;
        fn register_for_notification(
            self: &GattClientIntf,
            client_if: i32,
            bd_addr: RawAddress,
            handle: u16,
        ) -> u32;
        fn deregister_for_notification(
            self: &GattClientIntf,
            client_if: i32,
            bd_addr: RawAddress,
            handle: u16,
        ) -> u32;
        fn read_remote_rssi(self: &GattClientIntf, client_if: i32, bd_addr: RawAddress) -> u32;
        fn get_device_type(self: &GattClientIntf, bd_addr: RawAddress) -> i32;
        fn configure_mtu(self: &GattClientIntf, conn_id: i32, mtu: i32) -> u32;
        #[allow(clippy::too_many_arguments)]
        fn conn_parameter_update(
            self: &GattClientIntf,
            bd_addr: RawAddress,
            min_interval: i32,
            max_interval: i32,
            latency: i32,
            timeout: i32,
            min_ce_len: u16,
            max_ce_len: u16,
        ) -> u32;
        fn set_preferred_phy(
            self: &GattClientIntf,
            bd_addr: RawAddress,
            tx_phy: u8,
            rx_phy: u8,
            phy_options: u16,
        ) -> u32;
        fn read_phy(self: &GattClientIntf, client_if: i32, bt_addr: RawAddress) -> u32;
        fn subrate_request(
            self: &GattClientIntf,
            bd_addr: RawAddress,
            subrate_min: i32,
            subrate_max: i32,
            max_latency: i32,
            cont_num: i32,
            timeout: i32,
        ) -> u32;
        fn subrate_mode_request(
            self: &GattClientIntf,
            client_if: i32,
            bd_addr: RawAddress,
            subrate_mode: u8,
        ) -> u32;
        fn offload_characteristics(
            self: &GattClientIntf,
            conn_id: i32,
            service: BtGattDbElement,
            elements_count: usize,
            endpoint_id: u64,
            hub_id: u64,
            result: BtGattOffloadResult,
        ) -> u32;

        type GattServerIntf;

        fn GetGattServerProfile(btif: &BtIntf) -> UniquePtr<GattServerIntf>;

        fn register_server(self: &GattServerIntf, uuid: Uuid, eatt_support: bool) -> u32;
        fn unregister_server(self: &GattServerIntf, server_if: i32) -> u32;
        fn connect(
            self: &GattServerIntf,
            server_if: i32,
            bd_addr: RawAddress,
            addr_type: u8,
            is_direct: bool,
            transport: i32,
        ) -> u32;
        fn disconnect(
            self: &GattServerIntf,
            server_if: i32,
            bd_addr: RawAddress,
            conn_id: i32,
        ) -> u32;
        fn add_service(
            self: &GattServerIntf,
            server_if: i32,
            service: &[BtGattDbElement],
            service_count: usize,
        ) -> u32;
        fn delete_service(self: &GattServerIntf, server_if: i32, service_handle: i32) -> u32;
        fn send_indication(
            self: &GattServerIntf,
            server_if: i32,
            attribute_handle: i32,
            conn_id: i32,
            confirm: i32,
            value: Vec<u8>,
            length: usize,
        ) -> u32;
        fn send_response(
            self: &GattServerIntf,
            conn_id: i32,
            trans_id: i32,
            status: i32,
            response: BtGattResponse,
        ) -> u32;
        fn set_preferred_phy(
            self: &GattServerIntf,
            bd_addr: RawAddress,
            tx_phy: u8,
            rx_phy: u8,
            phy_options: u16,
        ) -> u32;
        fn read_phy(self: &GattServerIntf, server_if: i32, bt_addr: RawAddress) -> u32;
        fn offload_characteristics(
            self: &GattServerIntf,
            conn_id: i32,
            service: BtGattDbElement,
            elements_count: usize,
            endpoint_id: u64,
            hub_id: u64,
            result: BtGattOffloadResult,
        ) -> u32;
        fn unoffload_characteristics(self: &GattServerIntf, conn_id: i32, session_id: i32) -> u32;

        type GattIntf;

        fn GetGattProfile(btif: &BtIntf) -> UniquePtr<GattIntf>;

        fn init(self: &GattIntf) -> u32;
        fn cleanup(self: &GattIntf);

        fn GetBleAdvertiserIntf(self: &GattIntf) -> UniquePtr<BleAdvertiserIntf>;
        fn GetBleScannerIntf(self: &GattIntf) -> UniquePtr<BleScannerIntf>;
    }

    extern "Rust" {
        // Generated by cb_variant! below.
        fn gc_register_client_cb(status: i32, client_if: i32, app_uuid: Uuid);
        fn gc_open_cb(conn_id: i32, status: i32, client_if: i32, transport: i32, bda: RawAddress);
        fn gc_close_cb(conn_id: i32, status: i32, client_if: i32, transport: i32, bda: RawAddress);
        fn gc_register_for_notification_cb(conn_id: i32, registered: i32, status: i32, handle: u16);
        fn gc_notify_cb(conn_id: i32, p_data: BtGattNotifyParams);
        fn gc_read_characteristic_cb(conn_id: i32, status: i32, p_data: BtGattReadParams);
        fn gc_write_characteristic_cb(conn_id: i32, status: i32, handle: u16, value: &[u8]);
        fn gc_read_descriptor_cb(conn_id: i32, status: i32, p_data: BtGattReadParams);
        fn gc_write_descriptor_cb(conn_id: i32, status: i32, handle: u16, value: &[u8]);
        fn gc_execute_write_cb(conn_id: i32, status: i32);
        fn gc_read_remote_rssi_cb(client_if: i32, bda: RawAddress, rssi: i32, status: i32);
        fn gc_configure_mtu_cb(conn_id: i32, status: i32, mtu: i32);
        fn gc_congestion_cb(conn_id: i32, congested: bool);
        fn gc_get_gatt_db_cb(conn_id: i32, db: &[BtGattDbElement]);
        fn gc_phy_updated_cb(conn_id: i32, tx_phy: u8, rx_phy: u8, status: u8);
        fn gc_conn_updated_cb(conn_id: i32, interval: u16, latency: u16, timeout: u16, status: u8);
        fn gc_service_changed_cb(conn_id: i32);

        fn read_phy_callback(client_if: i32, addr: RawAddress, tx_phy: u8, rx_phy: u8, status: u8);

        fn gs_register_server_cb(status: i32, server_if: i32, app_uuid: Uuid);
        fn gs_connection_cb(
            conn_id: i32,
            server_if: i32,
            transport: i32,
            connected: i32,
            bda: RawAddress,
        );
        fn gs_service_added_cb(status: i32, server_if: i32, service: &[BtGattDbElement]);
        fn gs_service_deleted_cb(status: i32, server_if: i32, srvc_handle: i32);
        fn gs_request_read_characteristic_cb(
            conn_id: i32,
            trans_id: i32,
            bda: RawAddress,
            attr_handle: i32,
            offset: i32,
            is_long: bool,
        );
        fn gs_request_read_descriptor_cb(
            conn_id: i32,
            trans_id: i32,
            bda: RawAddress,
            attr_handle: i32,
            offset: i32,
            is_long: bool,
        );
        #[allow(clippy::too_many_arguments)]
        fn gs_request_write_characteristic_cb(
            conn_id: i32,
            trans_id: i32,
            bda: RawAddress,
            attr_handle: i32,
            offset: i32,
            need_rsp: bool,
            is_prep: bool,
            value: &[u8],
        );
        #[allow(clippy::too_many_arguments)]
        fn gs_request_write_descriptor_cb(
            conn_id: i32,
            trans_id: i32,
            bda: RawAddress,
            attr_handle: i32,
            offset: i32,
            need_rsp: bool,
            is_prep: bool,
            value: &[u8],
        );
        fn gs_request_exec_write_cb(conn_id: i32, trans_id: i32, bda: RawAddress, exec_write: i32);
        fn gs_response_confirmation_cb(status: i32, handle: i32);
        fn gs_indication_sent_cb(conn_id: i32, status: i32);
        fn gs_congestion_cb(conn_id: i32, congested: bool);
        fn gs_mtu_changed_cb(conn_id: i32, mtu: i32);
        fn gs_phy_updated_cb(conn_id: i32, tx_phy: u8, rx_phy: u8, status: u8);
        fn gs_conn_updated_cb(conn_id: i32, interval: u16, latency: u16, timeout: u16, status: u8);
        fn gs_subrate_chg_cb(
            conn_id: i32,
            subrate_factor: u16,
            latency: u16,
            cont_num: u16,
            timeout: u16,
            subrate_mode: u8,
            status: u8,
        );

        fn server_read_phy_callback(
            server_if: i32,
            addr: RawAddress,
            tx_phy: u8,
            rx_phy: u8,
            status: u8,
        );
    }

    unsafe extern "C++" {
        include!("topshim/gatt/gatt_ble_scanner_shim.h");

        type BleScannerIntf;

        #[namespace = ""]
        #[cxx_name = "btgatt_filt_param_setup_t"]
        type GattFilterParam = super::GattFilterParam;

        fn RegisterScanner(self: Pin<&mut BleScannerIntf>, uuid: Uuid);
        fn Unregister(self: Pin<&mut BleScannerIntf>, scanner_id: u8);
        fn Scan(self: Pin<&mut BleScannerIntf>, start: bool);
        fn ScanFilterParamSetup(
            self: Pin<&mut BleScannerIntf>,
            scanner_id: u8,
            action: u8,
            filter_index: u8,
            filt_param: GattFilterParam,
        );
        fn ScanFilterAdd(
            self: Pin<&mut BleScannerIntf>,
            filter_index: u8,
            filters: Vec<RustApcfCommand>,
        );
        fn ScanFilterClear(self: Pin<&mut BleScannerIntf>, filter_index: u8);
        fn ScanFilterEnable(self: Pin<&mut BleScannerIntf>, enable: bool);
        fn IsMsftSupported(self: Pin<&mut BleScannerIntf>) -> bool;
        fn MsftAdvMonitorAdd(self: Pin<&mut BleScannerIntf>, monitor: &RustMsftAdvMonitor);
        fn MsftAdvMonitorRemove(self: Pin<&mut BleScannerIntf>, monitor_handle: u8);
        fn MsftAdvMonitorEnable(self: Pin<&mut BleScannerIntf>, enable: bool);
        #[allow(clippy::too_many_arguments)]
        fn SetScanParameters(
            self: Pin<&mut BleScannerIntf>,
            scan_type: u8,
            scanner_id_1m: u8,
            scan_interval_1m: u16,
            scan_window_1m: u16,
            scanner_id_coded: u8,
            scan_interval_coded: u16,
            scan_window_coded: u16,
            scan_phy: u8,
        );

        fn BatchScanConfigStorage(
            self: Pin<&mut BleScannerIntf>,
            scanner_id: u8,
            batch_scan_full_max: i32,
            batch_scan_trunc_max: i32,
            batch_scan_notify_threshold: i32,
        );
        fn BatchScanEnable(
            self: Pin<&mut BleScannerIntf>,
            scan_mode: i32,
            scan_interval: u16,
            scan_window: u16,
            addr_type: i32,
            discard_rule: i32,
        );
        fn BatchScanDisable(self: Pin<&mut BleScannerIntf>);
        fn BatchScanReadReports(self: Pin<&mut BleScannerIntf>, scanner_id: u8, scan_mode: i32);

        fn StartSync(
            self: Pin<&mut BleScannerIntf>,
            sid: u8,
            address: RawAddress,
            address_type: u8,
            skip: u16,
            timeout: u16,
        );
        fn StopSync(self: Pin<&mut BleScannerIntf>, handle: u16);
        fn CancelCreateSync(self: Pin<&mut BleScannerIntf>, sid: u8, address: RawAddress);
        fn TransferSync(
            self: Pin<&mut BleScannerIntf>,
            address: RawAddress,
            service_data: u16,
            sync_handle: u16,
        );
        fn TransferSetInfo(
            self: Pin<&mut BleScannerIntf>,
            address: RawAddress,
            service_data: u16,
            adv_handle: u8,
        );
        fn SyncTxParameters(
            self: Pin<&mut BleScannerIntf>,
            address: RawAddress,
            mode: u8,
            skip: u16,
            timeout: u16,
        );

        /// Registers a C++ |ScanningCallbacks| implementation with the BleScanner.
        /// The shim implementation will call all the callbacks defined via |cb_variant!|.
        fn RegisterCallbacks(self: Pin<&mut BleScannerIntf>);
    }

    extern "Rust" {
        // All callbacks below are generated by cb_variant! and will be called
        // by the ScanningCallbacks handler in shim.
        unsafe fn gdscan_on_scanner_registered(uuid: *const i8, scannerId: u8, status: u8);
        unsafe fn gdscan_on_set_scanner_parameter_complete(scannerId: u8, status: u8);
        #[allow(clippy::too_many_arguments)]
        unsafe fn gdscan_on_scan_result(
            event_type: u16,
            addr_type: u8,
            addr: *const RawAddress,
            primary_phy: u8,
            secondary_phy: u8,
            advertising_sid: u8,
            tx_power: i8,
            rssi: i8,
            periodic_adv_int: u16,
            adv_data_ptr: *const u8,
            adv_data_len: usize,
        );
        unsafe fn gdscan_on_track_adv_found_lost(adv_track_info: RustAdvertisingTrackInfo);
        unsafe fn gdscan_on_batch_scan_reports(
            client_if: i32,
            status: i32,
            report_format: i32,
            num_records: i32,
            data_ptr: *const u8,
            data_len: usize,
        );
        unsafe fn gdscan_on_batch_scan_threshold_crossed(client_if: i32);

        // Static cb_variant! callbacks using base::Callback
        unsafe fn gdscan_status_callback(scanner_id: u8, btm_status: u8);
        unsafe fn gdscan_enable_callback(action: u8, btm_status: u8);
        unsafe fn gdscan_filter_param_setup_callback(
            scanner_id: u8,
            available_space: u8,
            action: u8,
            btm_status: u8,
        );
        unsafe fn gdscan_filter_config_callback(
            filter_index: u8,
            filter_type: u8,
            available_space: u8,
            action: u8,
            btm_status: u8,
        );
        unsafe fn gdscan_msft_adv_monitor_add_callback(monitor_handle: u8, status: u8);
        unsafe fn gdscan_msft_adv_monitor_remove_callback(status: u8);
        unsafe fn gdscan_msft_adv_monitor_enable_callback(status: u8);
        unsafe fn gdscan_start_sync_callback(
            status: u8,
            sync_handle: u16,
            advertising_sid: u8,
            addr_type: u8,
            address: *const RawAddress,
            phy: u8,
            interval: u16,
        );
        unsafe fn gdscan_sync_report_callback(
            sync_handle: u16,
            tx_power: i8,
            rssi: i8,
            status: u8,
            data: *const u8,
            len: usize,
        );
        unsafe fn gdscan_sync_lost_callback(sync_handle: u16);
        unsafe fn gdscan_sync_transfer_callback(status: u8, address: *const RawAddress);
        unsafe fn gdscan_biginfo_report_callback(sync_handle: u16, encrypted: bool);
    }

    unsafe extern "C++" {
        include!("topshim/gatt/gatt_ble_advertiser_shim.h");

        type BleAdvertiserIntf;

        #[namespace = ""]
        type AdvertiseParameters = super::AdvertiseParameters;
        #[namespace = ""]
        type PeriodicAdvertisingParameters = super::PeriodicAdvertisingParameters;

        fn RegisterAdvertiser(self: Pin<&mut BleAdvertiserIntf>);
        fn Unregister(self: Pin<&mut BleAdvertiserIntf>, adv_id: u8);

        fn GetOwnAddress(self: Pin<&mut BleAdvertiserIntf>, adv_id: u8);
        fn SetParameters(
            self: Pin<&mut BleAdvertiserIntf>,
            adv_id: u8,
            params: AdvertiseParameters,
        );
        fn SetData(
            self: Pin<&mut BleAdvertiserIntf>,
            adv_id: u8,
            set_scan_rsp: bool,
            data: Vec<u8>,
        );
        fn Enable(
            self: Pin<&mut BleAdvertiserIntf>,
            adv_id: u8,
            enable: bool,
            duration: u16,
            max_ext_adv_events: u8,
        );
        fn StartAdvertising(
            self: Pin<&mut BleAdvertiserIntf>,
            adv_id: u8,
            params: AdvertiseParameters,
            advertise_data: Vec<u8>,
            scan_response_data: Vec<u8>,
            timeout_in_sec: i32,
        );
        #[allow(clippy::too_many_arguments)]
        fn StartAdvertisingSet(
            self: Pin<&mut BleAdvertiserIntf>,
            reg_id: i32,
            params: AdvertiseParameters,
            advertise_data: Vec<u8>,
            scan_response_data: Vec<u8>,
            periodic_params: PeriodicAdvertisingParameters,
            periodic_data: Vec<u8>,
            duration: u16,
            max_ext_adv_events: u8,
        );
        fn SetPeriodicAdvertisingParameters(
            self: Pin<&mut BleAdvertiserIntf>,
            adv_id: u8,
            params: PeriodicAdvertisingParameters,
        );
        fn SetPeriodicAdvertisingData(self: Pin<&mut BleAdvertiserIntf>, adv_id: u8, data: Vec<u8>);
        fn SetPeriodicAdvertisingEnable(
            self: Pin<&mut BleAdvertiserIntf>,
            adv_id: u8,
            enable: bool,
            include_adi: bool,
        );

        /// Registers a C++ |AdvertisingCallbacks| implementation with the BleAdvertiser.
        /// The shim implementation will call all the callbacks defined via |cb_variant!|.
        fn RegisterCallbacks(self: Pin<&mut BleAdvertiserIntf>);
    }

    extern "Rust" {
        // All callbacks below are generated by cb_variant!.
        unsafe fn gdadv_on_advertising_set_started(
            reg_id: i32,
            adv_id: u8,
            tx_power: i8,
            status: u8,
        );
        unsafe fn gdadv_on_advertising_enabled(adv_id: u8, enabled: bool, status: u8);
        unsafe fn gdadv_on_advertising_data_set(adv_id: u8, status: u8);
        unsafe fn gdadv_on_scan_response_data_set(adv_id: u8, status: u8);
        unsafe fn gdadv_on_advertising_parameters_updated(adv_id: u8, tx_power: i8, status: u8);
        unsafe fn gdadv_on_periodic_advertising_parameters_updated(adv_id: u8, status: u8);
        unsafe fn gdadv_on_periodic_advertising_data_set(adv_id: u8, status: u8);
        unsafe fn gdadv_on_periodic_advertising_enabled(adv_id: u8, enabled: bool, status: u8);
        unsafe fn gdadv_on_own_address_read(adv_id: u8, addr_type: u8, address: *const RawAddress);

        // In-band callbacks also generated with cb_variant!.
        unsafe fn gdadv_idstatus_callback(adv_id: u8, status: u8);
        unsafe fn gdadv_idtxpowerstatus_callback(adv_id: u8, tx_power: i8, status: u8);
        unsafe fn gdadv_parameters_callback(adv_id: u8, status: u8, tx_power: i8);
        unsafe fn gdadv_getaddress_callback(adv_id: u8, addr_type: u8, address: *const RawAddress);
    }
}

// Non-trivial types, conversion in .cc is necessary.
pub type AdvertisingTrackInfo = ffi::RustAdvertisingTrackInfo;
pub type ApcfCommand = ffi::RustApcfCommand;
pub type MsftAdvMonitor = ffi::RustMsftAdvMonitor;
pub type MsftAdvMonitorPattern = ffi::RustMsftAdvMonitorPattern;
pub type MsftAdvMonitorAddress = ffi::RustMsftAdvMonitorAddress;

#[gen_cxx_extern_trivial]
pub type GattFilterParam = bindings::btgatt_filt_param_setup_t;

#[gen_cxx_extern_trivial]
pub type AdvertiseParameters = bindings::AdvertiseParameters;
#[gen_cxx_extern_trivial]
pub type PeriodicAdvertisingParameters = bindings::PeriodicAdvertisingParameters;

#[derive(Clone, Copy, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum GattStatus {
    Success = 0x00,
    InvalidHandle = 0x01,
    ReadNotPermit = 0x02,
    WriteNotPermit = 0x03,
    InvalidPdu = 0x04,
    InsufAuthentication = 0x05,
    ReqNotSupported = 0x06,
    InvalidOffset = 0x07,
    InsufAuthorization = 0x08,
    PrepareQFull = 0x09,
    NotFound = 0x0a,
    NotLong = 0x0b,
    InsufKeySize = 0x0c,
    InvalidAttrLen = 0x0d,
    ErrUnlikely = 0x0e,
    InsufEncryption = 0x0f,
    UnsupportGrpType = 0x10,
    InsufResource = 0x11,
    DatabaseOutOfSync = 0x12,
    ValueNotAllowed = 0x13,
    IllegalParameter = 0x87,
    TooShort = 0x7f,
    NoResources = 0x80,
    InternalError = 0x81,
    WrongState = 0x82,
    DbFull = 0x83,
    Busy = 0x84,
    Error = 0x85,
    CmdStarted = 0x86,
    Pending = 0x88,
    AuthFail = 0x89,
    More = 0x8a,
    InvalidCfg = 0x8b,
    ServiceStarted = 0x8c,
    EncryptedNoMitm = 0x8d,
    NotEncrypted = 0x8e,
    Congested = 0x8f,
    DupReg = 0x90,      /* 0x90 */
    AlreadyOpen = 0x91, /* 0x91 */
    Cancel = 0x92,      /* 0x92 */
    /* = 0xE0 ~ 0xFC reserved for future use */

    /* Client Characteristic Configuration Descriptor Improperly Configured */
    CccCfgErr = 0xFD,
    /* Procedure Already in progress */
    PrcInProgress = 0xFE,
    /* Attribute value out of range */
    OutOfRange = 0xFF,
}

impl From<u8> for GattStatus {
    fn from(item: u8) -> Self {
        match GattStatus::from_u8(item) {
            Some(s) => s,
            None => GattStatus::InternalError,
        }
    }
}

impl From<i32> for GattStatus {
    fn from(item: i32) -> Self {
        if item > 0xff {
            GattStatus::OutOfRange
        } else if let Some(s) = GattStatus::from_i32(item) {
            s
        } else {
            GattStatus::InternalError
        }
    }
}

impl Display for GattStatus {
    fn fmt(&self, f: &mut Formatter) -> Result {
        write!(f, "{}", self.to_u32().unwrap_or(0))
    }
}

#[derive(Debug, FromPrimitive, ToPrimitive, Clone, Copy, Default, PartialEq)]
#[repr(u32)]
/// LE Discoverable modes.
pub enum LeDiscMode {
    #[default]
    Invalid = 0,
    NonDiscoverable,
    LimitedDiscoverable,
    GeneralDiscoverable,
}

impl From<u32> for LeDiscMode {
    fn from(num: u32) -> Self {
        LeDiscMode::from_u32(num).unwrap_or(LeDiscMode::Invalid)
    }
}

impl From<LeDiscMode> for u32 {
    fn from(val: LeDiscMode) -> Self {
        val.to_u32().unwrap_or(0)
    }
}

#[derive(Debug, FromPrimitive, ToPrimitive, Clone, Copy, Default)]
#[repr(u8)]
/// Represents LE PHY.
pub enum LePhy {
    #[default]
    Invalid = 0,
    Phy1m = 1,
    Phy2m = 2,
    PhyCoded = 3,
}

impl From<LePhy> for i32 {
    fn from(item: LePhy) -> Self {
        item.to_i32().unwrap_or(0)
    }
}

impl From<LePhy> for u8 {
    fn from(item: LePhy) -> Self {
        item.to_u8().unwrap_or(0)
    }
}

#[derive(Clone, Copy, Debug, FromPrimitive, ToPrimitive, PartialEq, PartialOrd)]
#[repr(u32)]
pub enum AdvertisingStatus {
    Success = 0x0,
    DataTooLarge = 0x1,
    TooManyAdvertisers = 0x2,
    AlreadyStarted = 0x3,
    InternalError = 0x4,
    FeatureUnsupported = 0x5,
}

impl From<u8> for AdvertisingStatus {
    fn from(item: u8) -> Self {
        match AdvertisingStatus::from_u8(item) {
            Some(s) => s,
            None => AdvertisingStatus::InternalError,
        }
    }
}

#[derive(Debug)]
pub enum GattClientCallbacks {
    RegisterClient(GattStatus, i32, Uuid),
    Connect(i32, GattStatus, i32, i32, RawAddress),
    Disconnect(i32, GattStatus, i32, i32, RawAddress),
    RegisterForNotification(i32, i32, GattStatus, u16),
    Notify(i32, Box<BtGattNotifyParams>),
    ReadCharacteristic(i32, GattStatus, Box<BtGattReadParams>),
    WriteCharacteristic(i32, GattStatus, u16, Vec<u8>),
    ReadDescriptor(i32, GattStatus, Box<BtGattReadParams>),
    WriteDescriptor(i32, GattStatus, u16, Vec<u8>),
    ExecuteWrite(i32, GattStatus),
    ReadRemoteRssi(i32, RawAddress, i32, GattStatus),
    ConfigureMtu(i32, GattStatus, i32),
    Congestion(i32, bool),
    GetGattDb(i32, Vec<BtGattDbElement>),
    PhyUpdated(i32, u8, u8, GattStatus),
    ConnUpdated(i32, u16, u16, u16, GattStatus),
    ServiceChanged(i32),
    ReadPhy(i32, RawAddress, u8, u8, GattStatus),
}

#[derive(Debug)]
pub enum GattServerCallbacks {
    RegisterServer(GattStatus, i32, Uuid),
    Connection(i32, i32, i32, i32, RawAddress),
    ServiceAdded(GattStatus, i32, Vec<BtGattDbElement>),
    ServiceDeleted(GattStatus, i32, i32),
    RequestReadCharacteristic(i32, i32, RawAddress, i32, i32, bool),
    RequestReadDescriptor(i32, i32, RawAddress, i32, i32, bool),
    RequestWriteCharacteristic(i32, i32, RawAddress, i32, i32, bool, bool, Vec<u8>),
    RequestWriteDescriptor(i32, i32, RawAddress, i32, i32, bool, bool, Vec<u8>),
    RequestExecWrite(i32, i32, RawAddress, i32),
    ResponseConfirmation(i32, i32),
    IndicationSent(i32, GattStatus),
    Congestion(i32, bool),
    MtuChanged(i32, i32),
    PhyUpdated(i32, u8, u8, GattStatus),
    ConnUpdated(i32, u16, u16, u16, GattStatus),
    ReadPhy(i32, RawAddress, u8, u8, GattStatus),
    SubrateChanged(i32, u16, u16, u16, u16, u8, GattStatus),
}

pub struct GattClientCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(GattClientCallbacks) + Send>,
}

impl Debug for GattClientCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "GattClientCallbacksDispatcher {{}}")
    }
}

pub struct GattServerCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(GattServerCallbacks) + Send>,
}

impl Debug for GattServerCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "GattServerCallbacksDispatcher {{}}")
    }
}

type GattClientCb = Arc<Mutex<GattClientCallbacksDispatcher>>;
type GattServerCb = Arc<Mutex<GattServerCallbacksDispatcher>>;

cb_variant!(
    GattClientCb,
    gc_register_client_cb -> GattClientCallbacks::RegisterClient,
    i32 -> GattStatus, i32, Uuid
);

cb_variant!(
    GattClientCb,
    gc_open_cb -> GattClientCallbacks::Connect,
    i32, i32 -> GattStatus, i32, i32, RawAddress
);

cb_variant!(
    GattClientCb,
    gc_close_cb -> GattClientCallbacks::Disconnect,
    i32, i32 -> GattStatus, i32, i32, RawAddress
);

cb_variant!(
    GattClientCb,
    gc_register_for_notification_cb -> GattClientCallbacks::RegisterForNotification,
    i32, i32, i32 -> GattStatus, u16
);

cb_variant!(
    GattClientCb,
    gc_notify_cb -> GattClientCallbacks::Notify,
    i32, BtGattNotifyParams -> Box::<BtGattNotifyParams>
);

cb_variant!(
    GattClientCb,
    gc_read_characteristic_cb -> GattClientCallbacks::ReadCharacteristic,
    i32, i32 -> GattStatus, BtGattReadParams -> Box::<BtGattReadParams>
);

cb_variant!(
    GattClientCb,
    gc_write_characteristic_cb -> GattClientCallbacks::WriteCharacteristic,
    i32, i32 -> GattStatus, u16, &[u8] -> Vec::<u8>);

cb_variant!(
    GattClientCb,
    gc_read_descriptor_cb -> GattClientCallbacks::ReadDescriptor,
    i32, i32 -> GattStatus, BtGattReadParams -> Box::<BtGattReadParams>
);

cb_variant!(
    GattClientCb,
    gc_write_descriptor_cb -> GattClientCallbacks::WriteDescriptor,
    i32, i32 -> GattStatus, u16, &[u8] -> Vec::<u8>
);

cb_variant!(
    GattClientCb,
    gc_execute_write_cb -> GattClientCallbacks::ExecuteWrite,
    i32, i32 -> GattStatus
);

cb_variant!(
    GattClientCb,
    gc_read_remote_rssi_cb -> GattClientCallbacks::ReadRemoteRssi,
    i32, RawAddress, i32, i32 -> GattStatus
);

cb_variant!(
    GattClientCb,
    gc_configure_mtu_cb -> GattClientCallbacks::ConfigureMtu,
    i32, i32 -> GattStatus, i32
);

cb_variant!(
    GattClientCb,
    gc_congestion_cb -> GattClientCallbacks::Congestion,
    i32, bool
);

cb_variant!(
    GattClientCb,
    gc_get_gatt_db_cb -> GattClientCallbacks::GetGattDb,
    i32, &[BtGattDbElement] -> Vec::<BtGattDbElement>
);

cb_variant!(
    GattClientCb,
    gc_phy_updated_cb -> GattClientCallbacks::PhyUpdated,
    i32, u8, u8, u8 -> GattStatus
);

cb_variant!(
    GattClientCb,
    gc_conn_updated_cb -> GattClientCallbacks::ConnUpdated,
    i32, u16, u16, u16, u8 -> GattStatus
);

cb_variant!(
    GattClientCb,
    gc_service_changed_cb -> GattClientCallbacks::ServiceChanged,
    i32
);

cb_variant!(
    GattClientCb,
    read_phy_callback -> GattClientCallbacks::ReadPhy,
    i32, RawAddress, u8, u8, u8 -> GattStatus);

cb_variant!(
    GattServerCb,
    gs_register_server_cb -> GattServerCallbacks::RegisterServer,
    i32 -> GattStatus, i32, Uuid
);

cb_variant!(
    GattServerCb,
    gs_connection_cb -> GattServerCallbacks::Connection,
    i32, i32, i32, i32, RawAddress
);

cb_variant!(
    GattServerCb,
    gs_service_added_cb -> GattServerCallbacks::ServiceAdded,
    i32 -> GattStatus, i32, &[BtGattDbElement] -> Vec::<BtGattDbElement>
);

cb_variant!(
    GattServerCb,
    gs_service_deleted_cb -> GattServerCallbacks::ServiceDeleted,
    i32 -> GattStatus, i32, i32
);

cb_variant!(
    GattServerCb,
    gs_request_read_characteristic_cb -> GattServerCallbacks::RequestReadCharacteristic,
    i32, i32, RawAddress, i32, i32, bool
);

cb_variant!(
    GattServerCb,
    gs_request_read_descriptor_cb -> GattServerCallbacks::RequestReadDescriptor,
    i32, i32, RawAddress, i32, i32, bool
);

cb_variant!(
    GattServerCb,
    gs_request_write_characteristic_cb -> GattServerCallbacks::RequestWriteCharacteristic,
    i32, i32, RawAddress, i32, i32, bool, bool, &[u8] -> Vec::<u8>
);

cb_variant!(
    GattServerCb,
    gs_request_write_descriptor_cb -> GattServerCallbacks::RequestWriteDescriptor,
    i32, i32, RawAddress, i32, i32, bool, bool, &[u8] -> Vec::<u8>
);

cb_variant!(
    GattServerCb,
    gs_request_exec_write_cb -> GattServerCallbacks::RequestExecWrite,
    i32, i32, RawAddress, i32
);

cb_variant!(
    GattServerCb,
    gs_response_confirmation_cb -> GattServerCallbacks::ResponseConfirmation,
    i32, i32
);

cb_variant!(
    GattServerCb,
    gs_indication_sent_cb -> GattServerCallbacks::IndicationSent,
    i32, i32 -> GattStatus
);

cb_variant!(
    GattServerCb,
    gs_congestion_cb -> GattServerCallbacks::Congestion,
    i32, bool
);

cb_variant!(
    GattServerCb,
    gs_mtu_changed_cb -> GattServerCallbacks::MtuChanged,
    i32, i32
);

cb_variant!(
    GattServerCb,
    gs_phy_updated_cb -> GattServerCallbacks::PhyUpdated,
    i32, u8, u8, u8 -> GattStatus
);

cb_variant!(
    GattServerCb,
    gs_conn_updated_cb -> GattServerCallbacks::ConnUpdated,
    i32, u16, u16, u16, u8 -> GattStatus
);

cb_variant!(
    GattServerCb,
    server_read_phy_callback -> GattServerCallbacks::ReadPhy,
    i32, RawAddress, u8, u8, u8 -> GattStatus);

cb_variant!(
    GattServerCb,
    gs_subrate_chg_cb -> GattServerCallbacks::SubrateChanged,
    i32, u16, u16, u16, u16, u8, u8 -> GattStatus
);

/// Scanning callbacks used by the GD implementation of BleScannerInterface.
/// These callbacks should be registered using |RegisterCallbacks| on
/// `BleScannerInterface`.
#[derive(Debug)]
pub enum GattScannerCallbacks {
    OnScannerRegistered(Uuid, u8, GattStatus),
    OnSetScannerParameterComplete(u8, u8),
    OnScanResult(u16, u8, RawAddress, u8, u8, u8, i8, i8, u16, Vec<u8>),
    OnTrackAdvFoundLost(AdvertisingTrackInfo),
    OnBatchScanReports(i32, i32, i32, i32, Vec<u8>),
    OnBatchScanThresholdCrossed(i32),
}

pub struct GattScannerCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(GattScannerCallbacks) + Send>,
}

impl Debug for GattScannerCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "GattScannerCallbacksDispatcher {{}}")
    }
}

type GDScannerCb = Arc<Mutex<GattScannerCallbacksDispatcher>>;

cb_variant!(
    GDScannerCb,
    gdscan_on_scanner_registered -> GattScannerCallbacks::OnScannerRegistered,
    *const i8, u8, u8 -> GattStatus, {
        let _0 = unsafe { *(_0 as *const Uuid) };
    }
);

cb_variant!(
    GDScannerCb,
    gdscan_on_set_scanner_parameter_complete -> GattScannerCallbacks::OnSetScannerParameterComplete,
    u8, u8
);

cb_variant!(
    GDScannerCb,
    gdscan_on_scan_result -> GattScannerCallbacks::OnScanResult,
    u16, u8, *const RawAddress, u8, u8, u8, i8, i8, u16, *const u8, usize -> _, {
        let _2 = unsafe { *_2 };

        // Convert the vec! at the end. Since this cb is being called via cxx
        // ffi, we do the vector separation at the cxx layer. The usize is consumed during
        // conversion.
        let _9 : Vec<u8> = ptr_to_vec(_9, _10);
    }
);

cb_variant!(
    GDScannerCb,
    gdscan_on_track_adv_found_lost -> GattScannerCallbacks::OnTrackAdvFoundLost,
    AdvertisingTrackInfo);

cb_variant!(
    GDScannerCb,
    gdscan_on_batch_scan_reports -> GattScannerCallbacks::OnBatchScanReports,
    i32, i32, i32, i32, *const u8, usize -> _, {
        // Write the vector to the output and consume the usize in the input.
        let _4 : Vec<u8> = ptr_to_vec(_4, _5);
    }
);

cb_variant!(GDScannerCb, gdscan_on_batch_scan_threshold_crossed -> GattScannerCallbacks::OnBatchScanThresholdCrossed, i32);

/// In-band callbacks from the various |BleScannerInterface| methods. Rather than
/// store closures for each registered callback, we instead bind and return an
/// identifier for the callback instead (such as scanner id or Uuid).
#[derive(Debug)]
pub enum GattScannerInbandCallbacks {
    /// Params: Scanner Id, BTM Status
    StatusCallback(u8, u8),

    /// Params: Action (enable/disable), BTM Status
    EnableCallback(u8, u8),

    /// Params: Scanner Id, Available Space, Action Type, BTM Status
    FilterParamSetupCallback(u8, u8, u8, u8),

    /// Params: Filter Index, Filter Type, Available Space, Action, BTM Status
    FilterConfigCallback(u8, u8, u8, u8, u8),

    /// Params: Monitor Handle, Status
    MsftAdvMonitorAddCallback(u8, u8),

    /// Params: Status
    MsftAdvMonitorRemoveCallback(u8),

    /// Params: Status
    MsftAdvMonitorEnableCallback(u8),

    /// Params: Status, Sync Handle, Advertising Sid, Address Type, Address, Phy, Interval
    StartSyncCallback(u8, u16, u8, u8, RawAddress, u8, u16),

    /// Params: Sync Handle, Tx Power, RSSI, Status, Data
    SyncReportCallback(u16, i8, i8, u8, Vec<u8>),

    /// Params: Sync Handle
    SyncLostCallback(u16),

    /// Params: Status, Address
    SyncTransferCallback(u8, RawAddress),

    /// Params: Sync Handle, Encrypted
    BigInfoReportCallback(u16, bool),
}

pub struct GattScannerInbandCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(GattScannerInbandCallbacks) + Send>,
}

impl Debug for GattScannerInbandCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "GattScannerInbandCallbacksDispatcher {{}}")
    }
}

type GDScannerInbandCb = Arc<Mutex<GattScannerInbandCallbacksDispatcher>>;

cb_variant!(GDScannerInbandCb, gdscan_status_callback -> GattScannerInbandCallbacks::StatusCallback, u8, u8);
cb_variant!(GDScannerInbandCb, gdscan_enable_callback -> GattScannerInbandCallbacks::EnableCallback, u8, u8);
cb_variant!(GDScannerInbandCb,
    gdscan_filter_param_setup_callback -> GattScannerInbandCallbacks::FilterParamSetupCallback,
    u8, u8, u8, u8);
cb_variant!(GDScannerInbandCb,
    gdscan_filter_config_callback -> GattScannerInbandCallbacks::FilterConfigCallback,
    u8, u8, u8, u8, u8);
cb_variant!(GDScannerInbandCb,
    gdscan_msft_adv_monitor_add_callback -> GattScannerInbandCallbacks::MsftAdvMonitorAddCallback,
    u8, u8);
cb_variant!(GDScannerInbandCb,
    gdscan_msft_adv_monitor_remove_callback -> GattScannerInbandCallbacks::MsftAdvMonitorRemoveCallback,
    u8);
cb_variant!(GDScannerInbandCb,
    gdscan_msft_adv_monitor_enable_callback -> GattScannerInbandCallbacks::MsftAdvMonitorEnableCallback,
    u8);
cb_variant!(GDScannerInbandCb,
gdscan_start_sync_callback -> GattScannerInbandCallbacks::StartSyncCallback,
u8, u16, u8, u8, *const RawAddress, u8, u16, {
    let _4 = unsafe { *_4 };
});
cb_variant!(GDScannerInbandCb,
gdscan_sync_report_callback -> GattScannerInbandCallbacks::SyncReportCallback,
u16, i8, i8, u8, *const u8, usize -> _, {
    let _4 = ptr_to_vec(_4, _5 as usize);
});
cb_variant!(GDScannerInbandCb, gdscan_sync_lost_callback -> GattScannerInbandCallbacks::SyncLostCallback, u16);
cb_variant!(GDScannerInbandCb, gdscan_sync_transfer_callback -> GattScannerInbandCallbacks::SyncTransferCallback,
u8, *const RawAddress, {
    let _1 = unsafe { *_1 };
});
cb_variant!(GDScannerInbandCb, gdscan_biginfo_report_callback -> GattScannerInbandCallbacks::BigInfoReportCallback, u16, bool);

/// Advertising callbacks used by the GD implementation of BleAdvertiserInterface.
/// These callbacks should be registered using |RegisterCallbacks| on
/// `BleAdvertiser`.
#[derive(Debug)]
pub enum GattAdvCallbacks {
    /// Params: Reg Id, Advertiser Id, Tx Power, Status
    OnAdvertisingSetStarted(i32, u8, i8, AdvertisingStatus),

    /// Params: Advertiser Id, Enabled, Status
    OnAdvertisingEnabled(u8, bool, AdvertisingStatus),

    /// Params: Advertiser Id, Status
    OnAdvertisingDataSet(u8, AdvertisingStatus),

    /// Params: Advertiser Id, Status
    OnScanResponseDataSet(u8, AdvertisingStatus),

    /// Params: Advertiser Id, Tx Power, Status
    OnAdvertisingParametersUpdated(u8, i8, AdvertisingStatus),

    /// Params: Advertiser Id, Status
    OnPeriodicAdvertisingParametersUpdated(u8, AdvertisingStatus),

    /// Params: Advertiser Id, Status
    OnPeriodicAdvertisingDataSet(u8, AdvertisingStatus),

    /// Params: Advertiser Id, Enabled, Status
    OnPeriodicAdvertisingEnabled(u8, bool, AdvertisingStatus),

    /// Params: Advertiser Id, Address Type, Address
    OnOwnAddressRead(u8, u8, RawAddress),
}

pub struct GattAdvCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(GattAdvCallbacks) + Send>,
}

impl Debug for GattAdvCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "GattAdvCallbacksDispatcher {{}}")
    }
}

type GDAdvCb = Arc<Mutex<GattAdvCallbacksDispatcher>>;

cb_variant!(GDAdvCb,
    gdadv_on_advertising_set_started -> GattAdvCallbacks::OnAdvertisingSetStarted,
    i32, u8, i8, u8 -> AdvertisingStatus);
cb_variant!(GDAdvCb,
    gdadv_on_advertising_enabled -> GattAdvCallbacks::OnAdvertisingEnabled,
    u8, bool, u8 -> AdvertisingStatus);
cb_variant!(GDAdvCb,
    gdadv_on_advertising_data_set -> GattAdvCallbacks::OnAdvertisingDataSet,
    u8, u8 -> AdvertisingStatus);
cb_variant!(GDAdvCb,
    gdadv_on_scan_response_data_set -> GattAdvCallbacks::OnScanResponseDataSet,
    u8, u8 -> AdvertisingStatus);
cb_variant!(GDAdvCb,
    gdadv_on_advertising_parameters_updated -> GattAdvCallbacks::OnAdvertisingParametersUpdated,
    u8, i8, u8 -> AdvertisingStatus);
cb_variant!(GDAdvCb,
    gdadv_on_periodic_advertising_parameters_updated -> GattAdvCallbacks::OnPeriodicAdvertisingParametersUpdated,
    u8, u8 -> AdvertisingStatus);
cb_variant!(GDAdvCb,
    gdadv_on_periodic_advertising_data_set -> GattAdvCallbacks::OnPeriodicAdvertisingDataSet,
    u8, u8 -> AdvertisingStatus);
cb_variant!(GDAdvCb,
    gdadv_on_periodic_advertising_enabled -> GattAdvCallbacks::OnPeriodicAdvertisingEnabled,
    u8, bool, u8 -> AdvertisingStatus);
cb_variant!(GDAdvCb,
gdadv_on_own_address_read -> GattAdvCallbacks::OnOwnAddressRead, u8, u8,
*const RawAddress, {
    let _2 = unsafe { *_2 };
});

#[derive(Debug)]
pub enum GattAdvInbandCallbacks {
    /// Params: Advertiser Id, Status
    /// StatusCallback isn't implemented because we always want advertiser id.
    IdStatusCallback(u8, u8),

    /// Params: Advertiser Id, Tx Power, Status
    IdTxPowerStatusCallback(u8, i8, u8),

    /// Params: Advertiser Id, Status, Tx Power
    ParametersCallback(u8, u8, i8),

    /// Params: Advertiser Id, Addr Type, Address
    GetAddressCallback(u8, u8, RawAddress),
}

pub struct GattAdvInbandCallbacksDispatcher {
    pub dispatch: Box<dyn Fn(GattAdvInbandCallbacks) + Send>,
}

impl Debug for GattAdvInbandCallbacksDispatcher {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result {
        write!(f, "GattAdvInbandCallbacksDispatcher {{}}")
    }
}

type GDAdvInbandCb = Arc<Mutex<GattAdvInbandCallbacksDispatcher>>;

cb_variant!(GDAdvInbandCb, gdadv_idstatus_callback -> GattAdvInbandCallbacks::IdStatusCallback, u8, u8);
cb_variant!(GDAdvInbandCb, gdadv_idtxpowerstatus_callback -> GattAdvInbandCallbacks::IdTxPowerStatusCallback, u8, i8, u8);
cb_variant!(GDAdvInbandCb, gdadv_parameters_callback -> GattAdvInbandCallbacks::ParametersCallback, u8, u8, i8);
cb_variant!(GDAdvInbandCb, gdadv_getaddress_callback -> GattAdvInbandCallbacks::GetAddressCallback,
u8, u8, *const RawAddress, {
    let _2 = unsafe { *_2 };
});

// Pointers unsafe due to ownership but this is a static pointer so Send is ok
unsafe impl Send for Gatt {}
unsafe impl Send for GattClient {}
unsafe impl Send for GattClientCallbacks {}
unsafe impl Send for GattServer {}
unsafe impl Send for BleScanner {}
unsafe impl Send for BleAdvertiser {}

pub struct GattClient {
    internal: cxx::UniquePtr<ffi::GattClientIntf>,
}

impl GattClient {
    #[log_args]
    pub fn register_client(&self, uuid: Uuid, eatt_support: bool) -> BtStatus {
        let cname = CString::new("rust_client").expect("CString::new failed");
        self.internal.register_client(uuid, cname.into(), eatt_support).into()
    }

    #[log_args]
    pub fn unregister_client(&self, client_if: i32) -> BtStatus {
        self.internal.unregister_client(client_if).into()
    }

    #[log_args]
    #[allow(clippy::too_many_arguments)]
    pub fn connect(
        &self,
        client_if: i32,
        addr: RawAddress,
        addr_type: u8,
        is_direct: bool,
        transport: i32,
        opportunistic: bool,
        preferred_mtu: i32,
        prefer_relax_mode: bool,
        auto_mtu_enabled: bool,
    ) -> BtStatus {
        self.internal
            .connect(
                client_if,
                addr,
                addr_type,
                is_direct,
                transport,
                opportunistic,
                preferred_mtu,
                prefer_relax_mode,
                auto_mtu_enabled,
            )
            .into()
    }

    #[log_args]
    pub fn disconnect(&self, client_if: i32, addr: RawAddress, conn_id: i32) -> BtStatus {
        self.internal.disconnect(client_if, addr, conn_id).into()
    }

    #[log_args]
    pub fn refresh(&self, client_if: i32, addr: RawAddress) -> BtStatus {
        self.internal.refresh(client_if, addr).into()
    }

    #[log_args]
    pub fn search_service(&self, conn_id: i32, filter_uuid: Option<Uuid>) -> BtStatus {
        if let Some(filter_uuid) = filter_uuid {
            self.internal.search_service(conn_id, filter_uuid).into()
        } else {
            self.internal.search_service_all(conn_id).into()
        }
    }

    #[log_args]
    pub fn btif_gattc_discover_service_by_uuid(&self, conn_id: i32, uuid: Uuid) {
        self.internal.btif_gattc_discover_service_by_uuid(conn_id, uuid)
    }

    #[log_args]
    pub fn read_characteristic(&self, conn_id: i32, handle: u16, auth_req: i32) -> BtStatus {
        self.internal.read_characteristic(conn_id, handle, auth_req).into()
    }

    #[log_args]
    pub fn read_using_characteristic_uuid(
        &self,
        conn_id: i32,
        uuid: Uuid,
        s_handle: u16,
        e_handle: u16,
        auth_req: i32,
    ) -> BtStatus {
        self.internal
            .read_using_characteristic_uuid(conn_id, uuid, s_handle, e_handle, auth_req)
            .into()
    }

    #[log_args]
    pub fn write_characteristic(
        &self,
        conn_id: i32,
        handle: u16,
        write_type: i32,
        auth_req: i32,
        value: &[u8],
    ) -> BtStatus {
        self.internal
            .write_characteristic(
                conn_id,
                handle,
                write_type,
                auth_req,
                value.to_vec(),
                value.len(),
            )
            .into()
    }

    #[log_args]
    pub fn read_descriptor(&self, conn_id: i32, handle: u16, auth_req: i32) -> BtStatus {
        self.internal.read_descriptor(conn_id, handle, auth_req).into()
    }

    #[log_args]
    pub fn write_descriptor(
        &self,
        conn_id: i32,
        handle: u16,
        auth_req: i32,
        value: &[u8],
    ) -> BtStatus {
        self.internal
            .write_descriptor(conn_id, handle, auth_req, value.to_vec(), value.len())
            .into()
    }

    #[log_args]
    pub fn execute_write(&self, conn_id: i32, execute: i32) -> BtStatus {
        self.internal.execute_write(conn_id, execute).into()
    }

    #[log_args]
    pub fn register_for_notification(
        &self,
        client_if: i32,
        addr: RawAddress,
        handle: u16,
    ) -> BtStatus {
        self.internal.register_for_notification(client_if, addr, handle).into()
    }

    #[log_args]
    pub fn deregister_for_notification(
        &self,
        client_if: i32,
        addr: RawAddress,
        handle: u16,
    ) -> BtStatus {
        self.internal.deregister_for_notification(client_if, addr, handle).into()
    }

    #[log_args]
    pub fn read_remote_rssi(&self, client_if: i32, addr: RawAddress) -> BtStatus {
        self.internal.read_remote_rssi(client_if, addr).into()
    }

    #[log_args]
    pub fn get_device_type(&self, addr: RawAddress) -> i32 {
        self.internal.get_device_type(addr)
    }

    #[log_args]
    pub fn configure_mtu(&self, conn_id: i32, mtu: i32) -> BtStatus {
        self.internal.configure_mtu(conn_id, mtu).into()
    }

    #[log_args]
    #[allow(clippy::too_many_arguments)]
    pub fn conn_parameter_update(
        &self,
        addr: RawAddress,
        min_interval: i32,
        max_interval: i32,
        latency: i32,
        timeout: i32,
        min_ce_len: u16,
        max_ce_len: u16,
    ) -> BtStatus {
        self.internal
            .conn_parameter_update(
                addr,
                min_interval,
                max_interval,
                latency,
                timeout,
                min_ce_len,
                max_ce_len,
            )
            .into()
    }

    #[log_args]
    pub fn set_preferred_phy(
        &self,
        addr: RawAddress,
        tx_phy: u8,
        rx_phy: u8,
        phy_options: u16,
    ) -> BtStatus {
        self.internal.set_preferred_phy(addr, tx_phy, rx_phy, phy_options).into()
    }

    #[log_args]
    pub fn read_phy(&mut self, client_if: i32, addr: RawAddress) -> BtStatus {
        self.internal.read_phy(client_if, addr).into()
    }
}

pub struct GattServer {
    internal: cxx::UniquePtr<ffi::GattServerIntf>,
}

impl GattServer {
    #[log_args]
    pub fn register_server(&self, uuid: Uuid, eatt_support: bool) -> BtStatus {
        self.internal.register_server(uuid, eatt_support).into()
    }

    #[log_args]
    pub fn unregister_server(&self, server_if: i32) -> BtStatus {
        self.internal.unregister_server(server_if).into()
    }

    #[log_args]
    pub fn connect(
        &self,
        server_if: i32,
        addr: RawAddress,
        addr_type: u8,
        is_direct: bool,
        transport: i32,
    ) -> BtStatus {
        self.internal.connect(server_if, addr, addr_type, is_direct, transport).into()
    }

    #[log_args]
    pub fn disconnect(&self, server_if: i32, addr: RawAddress, conn_id: i32) -> BtStatus {
        self.internal.disconnect(server_if, addr, conn_id).into()
    }

    #[log_args]
    pub fn add_service(&self, server_if: i32, service: &[BtGattDbElement]) -> BtStatus {
        self.internal.add_service(server_if, service, service.len()).into()
    }

    #[log_args]
    pub fn delete_service(&self, server_if: i32, service_handle: i32) -> BtStatus {
        self.internal.delete_service(server_if, service_handle).into()
    }

    #[log_args]
    pub fn send_indication(
        &self,
        server_if: i32,
        attribute_handle: i32,
        conn_id: i32,
        confirm: i32,
        value: &[u8],
    ) -> BtStatus {
        self.internal
            .send_indication(
                server_if,
                attribute_handle,
                conn_id,
                confirm,
                value.to_vec(),
                value.len(),
            )
            .into()
    }

    /// Send a GATT response to a request.
    ///
    /// # Safety
    ///
    /// The caller must ensure that all contents and sub-contents of the
    /// BtGattResponse object are initialized.
    ///
    /// Access to a union field is unsafe, because said fields may be
    /// uninitialized and cause undefined behavior.
    pub unsafe fn send_response(
        &self,
        conn_id: i32,
        trans_id: i32,
        status: i32,
        response: BtGattResponse,
    ) -> BtStatus {
        // SAFETY: `handle` and `btgatt_value_t` support all byte sequences as valid values, but
        // said sequences must be preset to avoid undefined behavior.
        unsafe {
            // TODO(b/383549885) Devise a method to print bound wrapper type BtGattResponse
            log::debug!(
                "topshim out: send_response: \"{}\", \"{}\", \"{}\", \"BtGattResponse {{ handle: {}, \
                attr_value: {{ value: {:?}, handle: {}, offset: {}, len: {}, auth_req: {} }} }}\"",
                conn_id,
                trans_id,
                status,
                response.handle,
                response.attr_value.value,
                response.attr_value.handle,
                response.attr_value.offset,
                response.attr_value.len,
                response.attr_value.auth_req
            );
        }
        self.internal.send_response(conn_id, trans_id, status, response).into()
    }

    #[log_args]
    pub fn set_preferred_phy(
        &self,
        addr: RawAddress,
        tx_phy: u8,
        rx_phy: u8,
        phy_options: u16,
    ) -> BtStatus {
        self.internal.set_preferred_phy(addr, tx_phy, rx_phy, phy_options).into()
    }

    #[log_args]
    pub fn read_phy(&mut self, server_if: i32, addr: RawAddress) -> BtStatus {
        self.internal.read_phy(server_if, addr).into()
    }
}

pub struct BleScanner {
    internal: cxx::UniquePtr<ffi::BleScannerIntf>,
}

impl BleScanner {
    // TODO(b/383549885) Devise a method to print bound type BleScannerIntf
    pub(crate) fn new(internal: cxx::UniquePtr<ffi::BleScannerIntf>) -> Self {
        BleScanner { internal }
    }

    #[log_args]
    pub fn register_scanner(&mut self, app_uuid: Uuid) {
        self.internal.pin_mut().RegisterScanner(app_uuid);
    }

    #[log_args]
    pub fn unregister(&mut self, scanner_id: u8) {
        self.internal.pin_mut().Unregister(scanner_id);
    }

    // TODO(b/233124021): topshim should expose scan(enable) instead of start_scan and stop_scan.
    #[log_args]
    pub fn start_scan(&mut self) {
        self.internal.pin_mut().Scan(true);
    }

    #[log_args]
    pub fn stop_scan(&mut self) {
        self.internal.pin_mut().Scan(false);
    }

    #[log_args]
    pub fn scan_filter_setup(
        &mut self,
        scanner_id: u8,
        action: u8,
        filter_index: u8,
        param: GattFilterParam,
    ) {
        self.internal.pin_mut().ScanFilterParamSetup(scanner_id, action, filter_index, param);
    }

    #[log_args]
    pub fn scan_filter_add(&mut self, filter_index: u8, filters: Vec<ApcfCommand>) {
        self.internal.pin_mut().ScanFilterAdd(filter_index, filters);
    }

    #[log_args]
    pub fn scan_filter_clear(&mut self, filter_index: u8) {
        self.internal.pin_mut().ScanFilterClear(filter_index);
    }

    #[log_args]
    pub fn scan_filter_enable(&mut self) {
        self.internal.pin_mut().ScanFilterEnable(true);
    }

    #[log_args]
    pub fn scan_filter_disable(&mut self) {
        self.internal.pin_mut().ScanFilterEnable(false);
    }

    #[log_args]
    pub fn is_msft_supported(&mut self) -> bool {
        self.internal.pin_mut().IsMsftSupported()
    }

    #[log_args]
    pub fn msft_adv_monitor_add(&mut self, monitor: &MsftAdvMonitor) {
        self.internal.pin_mut().MsftAdvMonitorAdd(monitor);
    }

    #[log_args]
    pub fn msft_adv_monitor_remove(&mut self, monitor_handle: u8) {
        self.internal.pin_mut().MsftAdvMonitorRemove(monitor_handle);
    }

    #[log_args]
    pub fn msft_adv_monitor_enable(&mut self, enable: bool) {
        self.internal.pin_mut().MsftAdvMonitorEnable(enable);
    }

    #[log_args]
    #[allow(clippy::too_many_arguments)]
    pub fn set_scan_parameters(
        &mut self,
        scan_type: u8,
        scanner_id_1m: u8,
        scan_interval_1m: u16,
        scan_window_1m: u16,
        scanner_id_coded: u8,
        scan_interval_coded: u16,
        scan_window_coded: u16,
        scan_phy: u8,
    ) {
        self.internal.pin_mut().SetScanParameters(
            scan_type,
            scanner_id_1m,
            scan_interval_1m,
            scan_window_1m,
            scanner_id_coded,
            scan_interval_coded,
            scan_window_coded,
            scan_phy,
        );
    }

    #[log_args]
    pub fn batch_scan_config_storage(
        &mut self,
        scanner_id: u8,
        full_max: i32,
        trunc_max: i32,
        notify_threshold: i32,
    ) {
        self.internal.pin_mut().BatchScanConfigStorage(
            scanner_id,
            full_max,
            trunc_max,
            notify_threshold,
        );
    }

    #[log_args]
    pub fn batch_scan_enable(
        &mut self,
        scan_mode: i32,
        scan_interval: u16,
        scan_window: u16,
        addr_type: i32,
        discard_rule: i32,
    ) {
        self.internal.pin_mut().BatchScanEnable(
            scan_mode,
            scan_interval,
            scan_window,
            addr_type,
            discard_rule,
        );
    }

    #[log_args]
    pub fn batch_scan_disable(&mut self) {
        self.internal.pin_mut().BatchScanDisable();
    }

    #[log_args]
    pub fn batch_scan_read_reports(&mut self, scanner_id: u8, scan_mode: i32) {
        self.internal.pin_mut().BatchScanReadReports(scanner_id, scan_mode);
    }

    #[log_args]
    pub fn start_sync(
        &mut self,
        sid: u8,
        addr: RawAddress,
        addr_type: u8,
        skip: u16,
        timeout: u16,
    ) {
        self.internal.pin_mut().StartSync(sid, addr, addr_type, skip, timeout);
    }

    #[log_args]
    pub fn stop_sync(&mut self, handle: u16) {
        self.internal.pin_mut().StopSync(handle);
    }

    #[log_args]
    pub fn cancel_create_sync(&mut self, sid: u8, addr: RawAddress) {
        self.internal.pin_mut().CancelCreateSync(sid, addr);
    }

    #[log_args]
    pub fn transfer_sync(&mut self, addr: RawAddress, service_data: u16, sync_handle: u16) {
        self.internal.pin_mut().TransferSync(addr, service_data, sync_handle);
    }

    #[log_args]
    pub fn transfer_set_info(&mut self, addr: RawAddress, service_data: u16, adv_handle: u8) {
        self.internal.pin_mut().TransferSetInfo(addr, service_data, adv_handle);
    }

    #[log_args]
    pub fn sync_tx_parameters(&mut self, addr: RawAddress, mode: u8, skip: u16, timeout: u16) {
        self.internal.pin_mut().SyncTxParameters(addr, mode, skip, timeout);
    }
}

pub struct BleAdvertiser {
    internal: cxx::UniquePtr<ffi::BleAdvertiserIntf>,
}

impl BleAdvertiser {
    // TODO(b/383549885) Devise a method to print bound type BleAdvertiserIntf
    pub(crate) fn new(internal: cxx::UniquePtr<ffi::BleAdvertiserIntf>) -> Self {
        BleAdvertiser { internal }
    }

    #[log_args]
    pub fn register_advertiser(&mut self) {
        self.internal.pin_mut().RegisterAdvertiser();
    }

    #[log_args]
    pub fn unregister(&mut self, adv_id: u8) {
        self.internal.pin_mut().Unregister(adv_id);
    }

    #[log_args]
    pub fn get_own_address(&mut self, adv_id: u8) {
        self.internal.pin_mut().GetOwnAddress(adv_id);
    }

    #[log_args]
    pub fn set_parameters(&mut self, adv_id: u8, params: AdvertiseParameters) {
        self.internal.pin_mut().SetParameters(adv_id, params);
    }
    #[log_args]
    pub fn set_data(&mut self, adv_id: u8, set_scan_rsp: bool, data: Vec<u8>) {
        self.internal.pin_mut().SetData(adv_id, set_scan_rsp, data);
    }
    #[log_args]
    pub fn enable(&mut self, adv_id: u8, enable: bool, duration: u16, max_ext_adv_events: u8) {
        self.internal.pin_mut().Enable(adv_id, enable, duration, max_ext_adv_events);
    }
    #[log_args]
    pub fn start_advertising(
        &mut self,
        adv_id: u8,
        params: AdvertiseParameters,
        advertise_data: Vec<u8>,
        scan_response_data: Vec<u8>,
        timeout_in_sec: i32,
    ) {
        self.internal.pin_mut().StartAdvertising(
            adv_id,
            params,
            advertise_data,
            scan_response_data,
            timeout_in_sec,
        );
    }
    #[log_args]
    #[allow(clippy::too_many_arguments)]
    pub fn start_advertising_set(
        &mut self,
        reg_id: i32,
        params: AdvertiseParameters,
        advertise_data: Vec<u8>,
        scan_response_data: Vec<u8>,
        periodic_params: PeriodicAdvertisingParameters,
        periodic_data: Vec<u8>,
        duration: u16,
        max_ext_adv_events: u8,
    ) {
        self.internal.pin_mut().StartAdvertisingSet(
            reg_id,
            params,
            advertise_data,
            scan_response_data,
            periodic_params,
            periodic_data,
            duration,
            max_ext_adv_events,
        );
    }
    #[log_args]
    pub fn set_periodic_advertising_parameters(
        &mut self,
        adv_id: u8,
        params: PeriodicAdvertisingParameters,
    ) {
        self.internal.pin_mut().SetPeriodicAdvertisingParameters(adv_id, params);
    }
    #[log_args]
    pub fn set_periodic_advertising_data(&mut self, adv_id: u8, data: Vec<u8>) {
        self.internal.pin_mut().SetPeriodicAdvertisingData(adv_id, data);
    }
    #[log_args]
    pub fn set_periodic_advertising_enable(&mut self, adv_id: u8, enable: bool, include_adi: bool) {
        self.internal.pin_mut().SetPeriodicAdvertisingEnable(adv_id, enable, include_adi);
    }
}

pub struct Gatt {
    internal: cxx::UniquePtr<ffi::GattIntf>,
    is_init: bool,

    pub client: GattClient,
    pub server: GattServer,
    pub scanner: BleScanner,
    pub advertiser: BleAdvertiser,
}

impl Gatt {
    #[log_args]
    pub fn new(intf: &BluetoothInterface) -> Gatt {
        let gatt_intf = ffi::GetGattProfile(intf.as_btif());
        let gatt_client_intf = ffi::GetGattClientProfile(intf.as_btif());
        let gatt_server_intf = ffi::GetGattServerProfile(intf.as_btif());
        let gatt_scanner_intf = gatt_intf.GetBleScannerIntf();
        let gatt_advertiser_intf = gatt_intf.GetBleAdvertiserIntf();

        Gatt {
            internal: gatt_intf,
            is_init: false,
            client: GattClient { internal: gatt_client_intf },
            server: GattServer { internal: gatt_server_intf },
            scanner: BleScanner::new(gatt_scanner_intf),
            advertiser: BleAdvertiser::new(gatt_advertiser_intf),
        }
    }

    #[log_args]
    pub fn is_initialized(&self) -> bool {
        self.is_init
    }

    #[log_args]
    pub fn initialize(
        &mut self,
        gatt_client_callbacks_dispatcher: GattClientCallbacksDispatcher,
        gatt_server_callbacks_dispatcher: GattServerCallbacksDispatcher,
        gatt_scanner_callbacks_dispatcher: GattScannerCallbacksDispatcher,
        gatt_scanner_inband_callbacks_dispatcher: GattScannerInbandCallbacksDispatcher,
        gatt_adv_inband_callbacks_dispatcher: GattAdvInbandCallbacksDispatcher,
        gatt_adv_callbacks_dispatcher: GattAdvCallbacksDispatcher,
    ) -> bool {
        // Register dispatcher
        if get_dispatchers()
            .lock()
            .unwrap()
            .set::<GattClientCb>(Arc::new(Mutex::new(gatt_client_callbacks_dispatcher)))
        {
            panic!("Tried to set dispatcher for GattClientCallbacks but it already existed");
        }

        if get_dispatchers()
            .lock()
            .unwrap()
            .set::<GattServerCb>(Arc::new(Mutex::new(gatt_server_callbacks_dispatcher)))
        {
            panic!("Tried to set dispatcher for GattServerCallbacks but it already existed");
        }

        if get_dispatchers()
            .lock()
            .unwrap()
            .set::<GDScannerCb>(Arc::new(Mutex::new(gatt_scanner_callbacks_dispatcher)))
        {
            panic!("Tried to set dispatcher for GattScannerCallbacks but it already existed");
        }

        if get_dispatchers().lock().unwrap().set::<GDScannerInbandCb>(Arc::new(Mutex::new(
            gatt_scanner_inband_callbacks_dispatcher,
        ))) {
            panic!("Tried to set dispatcher for GattScannerInbandCallbacks but it already existed");
        }

        if get_dispatchers()
            .lock()
            .unwrap()
            .set::<GDAdvInbandCb>(Arc::new(Mutex::new(gatt_adv_inband_callbacks_dispatcher)))
        {
            panic!("Tried to set dispatcher for GattAdvInbandCallbacks but it already existed");
        }

        if get_dispatchers()
            .lock()
            .unwrap()
            .set::<GDAdvCb>(Arc::new(Mutex::new(gatt_adv_callbacks_dispatcher)))
        {
            panic!("Tried to set dispatcher for GattAdvCallbacks but it already existed");
        }

        let init: BtStatus = self.internal.init().into();
        self.is_init = init == BtStatus::Success;

        // Register callbacks for gatt scanner and advertiser
        self.scanner.internal.pin_mut().RegisterCallbacks();
        self.advertiser.internal.pin_mut().RegisterCallbacks();

        self.is_init
    }
}
