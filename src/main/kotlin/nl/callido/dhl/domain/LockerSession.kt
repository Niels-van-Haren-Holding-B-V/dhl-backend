package nl.callido.dhl.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "locker_session")
class LockerSession(
    @Id val id: UUID,
    val stopId: UUID,
    val courierId: String,
    val externalSessionId: String,
    @Enumerated(EnumType.STRING) var status: LockerSessionStatus,
    /** Last sim version we observed — the optimistic-locking handle for sim mutations. */
    var simVersion: Int,
    val createdAt: Instant,
    var lastActivityAt: Instant,
    var finishedAt: Instant?,
)
