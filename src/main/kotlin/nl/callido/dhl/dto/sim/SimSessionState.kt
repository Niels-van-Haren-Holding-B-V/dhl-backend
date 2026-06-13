package nl.callido.dhl.dto.sim

enum class SimSessionState {
    CREATED,
    READY,
    HAND_IN_DOOR_OPEN,
    HAND_IN_AWAITING_CONFIRM,
    HAND_IN_COMPLETED,
    HAND_OUT_DOOR_OPEN,
    HAND_OUT_AWAITING_CONFIRM,
    HAND_OUT_COMPLETED,
    FINISHED,
}
