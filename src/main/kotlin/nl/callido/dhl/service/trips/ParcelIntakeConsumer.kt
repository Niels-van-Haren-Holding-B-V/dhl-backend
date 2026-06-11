package nl.callido.dhl.service.trips

import nl.callido.dhl.domain.DeliveryLocationType
import nl.callido.dhl.domain.Parcel
import nl.callido.dhl.domain.ParcelDirection
import nl.callido.dhl.domain.ParcelStatus
import nl.callido.dhl.dto.trips.ParcelAnnouncement
import nl.callido.dhl.repository.ParcelRepository
import nl.callido.dhl.repository.StopRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Kafka ingestion from upstream planning systems: announcements on
 * `parcel-intake` become EXPECTED hand-in parcels on a stop. Idempotent by
 * barcode (replays and duplicate announcements are no-ops); a malformed
 * message is logged and skipped, never blocks the partition.
 */
@Component
@ConditionalOnBooleanProperty("dhl.backend.enabled")
class ParcelIntakeConsumer(
    private val parcels: ParcelRepository,
    private val stops: StopRepository,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [TOPIC], groupId = "dhl-backend")
    fun onAnnouncement(message: String) {
        val announcement = try {
            objectMapper.readValue(message, ParcelAnnouncement::class.java)
        } catch (e: Exception) {
            log.warn("parcel-intake: skipping malformed message: {}", e.message)
            return
        }
        if (parcels.findByBarcode(announcement.barcode) != null) {
            log.info("parcel-intake: {} already known, skipping", announcement.barcode)
            return
        }
        val stopId = announcement.stopId
            ?: stops.findAll().firstOrNull { it.deliveryLocationType == DeliveryLocationType.LOCKER }?.id
        if (stopId == null) {
            log.warn("parcel-intake: no LOCKER stop to attach {} to, skipping", announcement.barcode)
            return
        }
        parcels.save(
            Parcel(
                id = UUID.randomUUID(),
                stopId = stopId,
                barcode = announcement.barcode,
                direction = ParcelDirection.HAND_IN,
                status = ParcelStatus.EXPECTED,
                lengthCm = announcement.lengthCm,
                widthCm = announcement.widthCm,
                heightCm = announcement.heightCm,
                weightG = announcement.weightG,
            ),
        )
        log.info("parcel-intake: {} ingested onto stop {}", announcement.barcode, stopId)
    }

    companion object {
        const val TOPIC = "parcel-intake"
    }
}
