package nl.callido.dhl.service.trips

import kotlinx.coroutines.runBlocking
import nl.callido.dhl.client.LockerSimClient
import nl.callido.dhl.client.SimRejectedException
import nl.callido.dhl.common.ParcelSize
import nl.callido.dhl.domain.DeliveryLocationType
import nl.callido.dhl.domain.Parcel
import nl.callido.dhl.domain.ParcelDirection
import nl.callido.dhl.domain.ParcelStatus
import nl.callido.dhl.dto.sim.ReserveRequest
import nl.callido.dhl.dto.trips.ParcelAnnouncement
import nl.callido.dhl.repository.ParcelRepository
import nl.callido.dhl.repository.StopRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
@ConditionalOnBooleanProperty("dhl.backend.enabled")
class ParcelIntakeConsumer(
    private val parcels: ParcelRepository,
    private val stops: StopRepository,
    private val client: LockerSimClient,
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
        val size = ParcelSize.forDimensions(announcement.lengthCm, announcement.widthCm, announcement.heightCm)
        if (size == null) {
            log.warn("parcel-intake: {} does not fit ANY compartment size, rejecting", announcement.barcode)
            return
        }
        val stopId = announcement.stopId
            ?: stops.findAll().firstOrNull { it.deliveryLocationType == DeliveryLocationType.LOCKER }?.id
        if (stopId == null) {
            log.warn("parcel-intake: no LOCKER stop to attach {} to, skipping", announcement.barcode)
            return
        }
        // Kafka listener thread (not the event loop), so the runBlocking bridge is fine here.
        val reserved = try {
            runBlocking { client.simReserve(ReserveRequest(announcement.barcode, size)) }
        } catch (e: SimRejectedException) {
            log.warn("parcel-intake: machine rejected reservation for {} ({}): {}", announcement.barcode, size, e.code)
            return
        } catch (e: Exception) {
            log.warn("parcel-intake: machine unreachable, {} not planned: {}", announcement.barcode, e.message)
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
        log.info(
            "parcel-intake: {} ({}) ingested onto stop {}, compartment {} reserved",
            announcement.barcode,
            size,
            stopId,
            reserved.label,
        )
    }

    companion object {
        const val TOPIC = "parcel-intake"
    }
}
