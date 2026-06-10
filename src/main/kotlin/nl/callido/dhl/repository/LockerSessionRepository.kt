package nl.callido.dhl.repository

import nl.callido.dhl.domain.LockerSession
import nl.callido.dhl.domain.LockerSessionStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface LockerSessionRepository : JpaRepository<LockerSession, UUID> {
    fun findByStatusAndLastActivityAtBefore(status: LockerSessionStatus, cutoff: Instant): List<LockerSession>
}
