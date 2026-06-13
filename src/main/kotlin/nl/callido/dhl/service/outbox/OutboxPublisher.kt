package nl.callido.dhl.service.outbox

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import nl.callido.dhl.repository.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

// Sequential by design (per-aggregate ordering); a failed send breaks the batch so rows
// stay unpublished and are retried next tick — at-least-once, never lost.
@Component
@ConditionalOnBooleanProperty("dhl.backend.enabled")
class OutboxPublisher(private val outbox: OutboxRepository, private val kafka: KafkaTemplate<String, String>) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 2000)
    fun publishPending(): Unit = runBlocking {
        for (event in outbox.findTop100ByPublishedAtIsNullOrderByCreatedAt()) {
            try {
                kafka.send(TOPIC, event.aggregateId, event.payload).await()
            } catch (e: Exception) {
                log.warn("outbox publish failed for event {} ({}), retrying next tick", event.id, event.eventType, e)
                break
            }
            event.publishedAt = Instant.now()
            outbox.save(event)
        }
    }

    companion object {
        const val TOPIC = "delivery-events"
    }
}
