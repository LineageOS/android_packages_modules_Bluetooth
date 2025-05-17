package android.bluetooth;

/** {@hide} */
@JavaDerive(toString = true)
@Backing(type="int")
enum State {
    OFF = 10,
    TURNING_ON = 11,
    ON = 12,
    TURNING_OFF = 13,
    BLE_TURNING_ON = 14,
    BLE_ON = 15,
    BLE_TURNING_OFF = 16,
}
