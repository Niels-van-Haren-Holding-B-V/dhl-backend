package nl.callido.dhl.dto.sim

data class SimStateSnapshot(
    val config: String,
    val session: SimSessionDto?,
    val compartments: List<CompartmentDto>,
    val activeFailures: List<FailureMode>,
    val eventLog: List<SimLogEntry>,
)
