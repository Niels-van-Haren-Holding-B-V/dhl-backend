package nl.callido.dhl.repository

import nl.callido.dhl.domain.Parcel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ParcelRepository : JpaRepository<Parcel, UUID> {
    fun findByStopIdIn(stopIds: Collection<UUID>): List<Parcel>
    fun findByStopId(stopId: UUID): List<Parcel>
    fun findByBarcode(barcode: String): Parcel?

    /** Demo reset: every parcel back to its seeded starting state. */
    @Modifying
    @Query("update Parcel p set p.status = nl.callido.dhl.domain.ParcelStatus.EXPECTED")
    fun resetAllToExpected()
}
