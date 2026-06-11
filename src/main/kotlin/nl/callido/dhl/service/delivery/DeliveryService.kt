package nl.callido.dhl.service.delivery

import nl.callido.dhl.domain.DeliveryRegistration
import nl.callido.dhl.domain.ParcelStatus
import nl.callido.dhl.dto.delivery.DeliveryEventPayload
import nl.callido.dhl.dto.delivery.RegisterDeliveryResponse
import nl.callido.dhl.repository.DeliveryRegistrationRepository
import nl.callido.dhl.repository.ParcelRepository
import nl.callido.dhl.service.outbox.OutboxWriter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The registerDelivery path — the locker flow funnels into the same logic
 * that doorstep deliveries use.
 */
@Service
class DeliveryService(
    private val registrations: DeliveryRegistrationRepository,
    private val parcels: ParcelRepository,
    private val outboxWriter: OutboxWriter,
) {

    /**
     * Idempotent on (sessionId, barcode) — INCLUDING the sessionless path
     * (sessionId = null): the lookup is NULL-safe (`is not distinct from`)
     * and the schema backstops it with UNIQUE NULLS NOT DISTINCT. A
     * duplicate registration — double tap, reconciled retry, replayed
     * request, repeated doorstep call — must not produce a second outbox
     * row. Registration and outbox row commit in the SAME transaction.
     */
    @Transactional
    fun register(barcode: String, status: ParcelStatus, sessionId: UUID? = null): RegisterDeliveryResponse {
        registrations.findBySessionIdAndBarcode(sessionId, barcode)?.let {
            return RegisterDeliveryResponse(barcode, it.status, duplicate = true)
        }
        val now = Instant.now()
        registrations.save(DeliveryRegistration(UUID.randomUUID(), sessionId, barcode, status, now))
        val parcel = parcels.findByBarcode(barcode)?.also {
            it.status = status
            parcels.save(it)
        }
        outboxWriter.write(
            aggregateType = "parcel",
            aggregateId = barcode,
            eventType = "DELIVERY_REGISTERED",
            payload = DeliveryEventPayload(barcode, status, sessionId, parcel?.stopId, now),
        )
        return RegisterDeliveryResponse(barcode, status, duplicate = false)
    }
}
