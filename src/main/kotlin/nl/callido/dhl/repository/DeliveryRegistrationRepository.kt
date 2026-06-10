package nl.callido.dhl.repository

import nl.callido.dhl.domain.DeliveryRegistration
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DeliveryRegistrationRepository : JpaRepository<DeliveryRegistration, UUID> {
    fun findBySessionIdAndBarcode(sessionId: UUID?, barcode: String): DeliveryRegistration?
}
