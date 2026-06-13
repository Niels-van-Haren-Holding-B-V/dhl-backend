package nl.callido.dhl.service.trips

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.callido.dhl.dto.trips.TripDto
import nl.callido.dhl.mapper.trips.ParcelMapper
import nl.callido.dhl.mapper.trips.StopMapper
import nl.callido.dhl.mapper.trips.TripMapper
import nl.callido.dhl.repository.ParcelRepository
import nl.callido.dhl.repository.StopRepository
import nl.callido.dhl.repository.TripRepository
import org.springframework.stereotype.Service

@Service
class TripService(private val trips: TripRepository, private val stops: StopRepository, private val parcels: ParcelRepository) {

    suspend fun trips(): List<TripDto> = withContext(Dispatchers.IO) {
        val allTrips = trips.findAll()
        if (allTrips.isEmpty()) return@withContext emptyList()
        val stopsByTrip = stops.findByTripIdInOrderBySeq(allTrips.map { it.id }).groupBy { it.tripId }
        val allStops = stopsByTrip.values.flatten()
        val parcelsByStop = parcels.findByStopIdIn(allStops.map { it.id }).groupBy { it.stopId }
        allTrips.map { trip ->
            val stopDtos = stopsByTrip[trip.id].orEmpty().map { stop ->
                val parcelDtos = parcelsByStop[stop.id].orEmpty().map(ParcelMapper::map)
                StopMapper.map(stop, parcelDtos)
            }
            TripMapper.map(trip, stopDtos)
        }
    }
}
