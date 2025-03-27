//! This library provides access to Linux uhid.

use bt_topshim::btif::{DisplayAddress, RawAddress};
use log::{debug, error};
use std::fs::File;
use uhid_virt::{Bus, CreateParams, UHIDDevice};

const VID_DEFAULT: u32 = 0x0000;
const PID_DEFAULT: u32 = 0x0000;

// Report descriptor for a standard mouse
const RDESC: [u8; 34] = [
    0x05, 0x01, // USAGE_PAGE (Generic Desktop)
    0x09, 0x02, // USAGE (Mouse)
    0xa1, 0x01, // COLLECTION (Application)
    0x09, 0x01, //   USAGE (Pointer)
    0xa1, 0x00, //   COLLECTION (Physical)
    0x05, 0x09, //     USAGE_PAGE (Button)
    0x19, 0x01, //     USAGE_MINIMUM (Button 1)
    0x29, 0x01, //     USAGE_MAXIMUM (Button 1)
    0x15, 0x00, //     LOGICAL_MINIMUM (0)
    0x25, 0x01, //     LOGICAL_MAXIMUM (1)
    0x95, 0x01, //     REPORT_COUNT (1)
    0x75, 0x01, //     REPORT_SIZE (1)
    0x81, 0x02, //     INPUT (Data,Var,Abs)
    0x95, 0x01, //     REPORT_COUNT (1)
    0x75, 0x07, //     REPORT_SIZE (7)
    0x81, 0x03, //     INPUT (Cnst,Var,Abs)
    0xc0, //   END_COLLECTION
    0xc0, // END_COLLECTION
];

#[derive(Default)]
pub struct UHid {
    /// Open UHID objects.
    devices: Vec<UHIDDevice<File>>,
}

impl Drop for UHid {
    fn drop(&mut self) {
        self.clear();
    }
}

impl UHid {
    /// Create a new UHid struct that holds a vector of uhid objects.
    pub fn new() -> Self {
        Default::default()
    }

    /// Initialize a uhid device with kernel.
    pub fn create(
        &mut self,
        name: String,
        phys: RawAddress,
        uniq: RawAddress,
    ) -> Result<(), String> {
        debug!(
            "Create a UHID {} with phys: {}, uniq: {}",
            name,
            DisplayAddress(&phys),
            DisplayAddress(&uniq)
        );
        let rd_data = RDESC.to_vec();
        let create_params = CreateParams {
            name,
            phys: phys.to_string().to_lowercase(),
            uniq: uniq.to_string().to_lowercase(),
            bus: Bus::BLUETOOTH,
            vendor: VID_DEFAULT,
            product: PID_DEFAULT,
            version: 0,
            country: 0,
            rd_data,
        };
        match UHIDDevice::create(create_params) {
            Ok(d) => self.devices.push(d),
            Err(e) => return Err(e.to_string()),
        }
        Ok(())
    }

    /// Destroy open UHID devices and clear the storage.
    pub fn clear(&mut self) {
        for device in self.devices.iter_mut() {
            match device.destroy() {
                Err(e) => error!("Fail to destroy uhid {}", e),
                Ok(_) => (),
            }
        }
        self.devices.clear();
    }

    /// Return if the UHID vector is empty.
    pub fn is_empty(&self) -> bool {
        self.devices.is_empty()
    }
}
