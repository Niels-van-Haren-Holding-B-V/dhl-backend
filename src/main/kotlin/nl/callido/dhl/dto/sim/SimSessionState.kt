package nl.callido.dhl.dto.sim

enum class SimSessionState {
    CREATED,                  // init done, QR not yet scanned at the machine
    READY,                    // bound to the machine
    HAND_IN_DOOR_OPEN,
    HAND_IN_AWAITING_CONFIRM, // door closed, parcel inside, not yet confirmed
    HAND_IN_COMPLETED,
    HAND_OUT_DOOR_OPEN,
    HAND_OUT_AWAITING_CONFIRM,
    HAND_OUT_COMPLETED,
    FINISHED,
}
