package nl.callido.dhl.repository

import nl.callido.dhl.domain.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OutboxRepository : JpaRepository<OutboxEvent, UUID> {
    fun findTop100ByPublishedAtIsNullOrderByCreatedAt(): List<OutboxEvent>
}
