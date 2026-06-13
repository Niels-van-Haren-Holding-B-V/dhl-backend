package nl.callido.dhl.service.outbox

import nl.callido.dhl.domain.OutboxEvent
import nl.callido.dhl.repository.OutboxRepository
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

// MUST be called INSIDE the business transaction, so the event exists iff the registration committed.
@Service
class OutboxWriter(private val outbox: OutboxRepository, private val objectMapper: ObjectMapper) {
    fun write(aggregateType: String, aggregateId: String, eventType: String, payload: Any) {
        outbox.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                eventType = eventType,
                payload = objectMapper.writeValueAsString(payload),
                createdAt = Instant.now(),
                publishedAt = null,
            ),
        )
    }
}
