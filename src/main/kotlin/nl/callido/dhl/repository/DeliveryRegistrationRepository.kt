package nl.callido.dhl.repository

import nl.callido.dhl.domain.DeliveryRegistration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface DeliveryRegistrationRepository : JpaRepository<DeliveryRegistration, UUID> {

    // NULL-safe on sessionId: a derived query renders `session_id = NULL` (matches nothing),
    // silently breaking idempotency for the sessionless registerDelivery path.
    @Query(
        "select r from DeliveryRegistration r " +
            "where r.sessionId is not distinct from :sessionId and r.barcode = :barcode",
    )
    fun findBySessionIdAndBarcode(sessionId: UUID?, barcode: String): DeliveryRegistration?
}
