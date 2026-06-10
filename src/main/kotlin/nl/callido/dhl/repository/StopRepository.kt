package nl.callido.dhl.repository

import nl.callido.dhl.domain.Stop
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StopRepository : JpaRepository<Stop, UUID> {
    fun findByTripIdInOrderBySeq(tripIds: Collection<UUID>): List<Stop>
}
