package nl.callido.dhl.repository

import nl.callido.dhl.domain.Parcel
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ParcelRepository : JpaRepository<Parcel, UUID> {
    fun findByStopIdIn(stopIds: Collection<UUID>): List<Parcel>
    fun findByStopId(stopId: UUID): List<Parcel>
    fun findByBarcode(barcode: String): Parcel?
}
