package nl.callido.dhl.service.sim

import nl.callido.dhl.repository.DeliveryRegistrationRepository
import nl.callido.dhl.repository.LockerSessionRepository
import nl.callido.dhl.repository.OutboxRepository
import nl.callido.dhl.repository.ParcelRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Demo-only: puts the seeded data back in its starting position, the same
 * thing infra's demo-reset Job does between demo runs — wired to the reset
 * button on the parcel-machine page so one click resets the WHOLE demo
 * (the sim resets separately via the locker API).
 */
@Service
@ConditionalOnBooleanProperty("dhl.sim-passthrough.enabled")
class DemoResetService(
    private val registrations: DeliveryRegistrationRepository,
    private val outbox: OutboxRepository,
    private val sessions: LockerSessionRepository,
    private val parcels: ParcelRepository,
) {
    @Transactional
    fun resetDemoData() {
        registrations.deleteAllInBatch()
        outbox.deleteAllInBatch()
        sessions.deleteAllInBatch()
        parcels.deleteAnnounced()
        parcels.resetAllToExpected()
    }
}
