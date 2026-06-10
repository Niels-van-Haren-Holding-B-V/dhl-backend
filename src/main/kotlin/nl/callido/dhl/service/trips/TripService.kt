package nl.callido.dhl.service.trips

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.callido.dhl.dto.trips.DimensionsDto
import nl.callido.dhl.dto.trips.ParcelDto
import nl.callido.dhl.dto.trips.StopDto
import nl.callido.dhl.dto.trips.TripDto
import nl.callido.dhl.repository.ParcelRepository
import nl.callido.dhl.repository.StopRepository
import nl.callido.dhl.repository.TripRepository
import org.springframework.stereotype.Service

@Service
class TripService(
    private val trips: TripRepository,
    private val stops: StopRepository,
    private val parcels: ParcelRepository,
) {

    suspend fun trips(): List<TripDto> = withContext(Dispatchers.IO) {
        val allTrips = trips.findAll()
        if (allTrips.isEmpty()) return@withContext emptyList()
        val stopsByTrip = stops.findByTripIdInOrderBySeq(allTrips.map { it.id }).groupBy { it.tripId }
        val allStops = stopsByTrip.values.flatten()
        val parcelsByStop = parcels.findByStopIdIn(allStops.map { it.id }).groupBy { it.stopId }
        allTrips.map { trip ->
            TripDto(
                id = trip.id,
                name = trip.name,
                tripDate = trip.tripDate,
                stops = stopsByTrip[trip.id].orEmpty().map { stop ->
                    StopDto(
                        id = stop.id,
                        seq = stop.seq,
                        address = stop.address,
                        deliveryLocationType = stop.deliveryLocationType,
                        parcels = parcelsByStop[stop.id].orEmpty().map { parcel ->
                            ParcelDto(
                                id = parcel.id,
                                barcode = parcel.barcode,
                                direction = parcel.direction,
                                status = parcel.status,
                                dimensions = DimensionsDto(parcel.lengthCm, parcel.widthCm, parcel.heightCm, parcel.weightG),
                                size = parcel.size,
                            )
                        },
                    )
                },
            )
        }
    }
}
