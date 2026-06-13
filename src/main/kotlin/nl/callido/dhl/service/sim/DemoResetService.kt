package nl.callido.dhl.service.sim

import nl.callido.dhl.repository.DeliveryRegistrationRepository
import nl.callido.dhl.repository.LockerSessionRepository
import nl.callido.dhl.repository.OutboxRepository
import nl.callido.dhl.repository.ParcelRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
