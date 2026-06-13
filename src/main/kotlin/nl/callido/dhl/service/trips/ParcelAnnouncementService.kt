package nl.callido.dhl.service.trips

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import nl.callido.dhl.client.LockerSimClient
import nl.callido.dhl.client.SimRejectedException
import nl.callido.dhl.common.ParcelSize
import nl.callido.dhl.dto.sim.ReserveRequest
import nl.callido.dhl.dto.trips.ParcelAnnouncement
import nl.callido.dhl.repository.ParcelRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
@ConditionalOnBooleanProperty("dhl.sim-passthrough.enabled")
class ParcelAnnouncementService(
    private val client: LockerSimClient,
    private val kafka: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val parcels: ParcelRepository,
) {

    suspend fun announce(announcement: ParcelAnnouncement): ParcelAnnouncement {
        val size = ParcelSize.forDimensions(announcement.lengthCm, announcement.widthCm, announcement.heightCm)
            ?: throw SimRejectedException("NO_FITTING_SIZE", "no compartment size fits these dimensions")
        if (withContext(Dispatchers.IO) { parcels.findByBarcode(announcement.barcode) } != null) {
            throw SimRejectedException("DUPLICATE_BARCODE", "parcel ${announcement.barcode} already exists")
        }
        client.simReserve(ReserveRequest(announcement.barcode, size))
        withContext(Dispatchers.IO) {
            kafka.send(ParcelIntakeConsumer.TOPIC, announcement.barcode, objectMapper.writeValueAsString(announcement)).await()
        }
        return announcement
    }
}
