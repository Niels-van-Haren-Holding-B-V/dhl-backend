package nl.callido.dhl.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "outbox")
class OutboxEvent(
    @Id val id: UUID,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val payload: String,
    val createdAt: Instant,
    var publishedAt: Instant?,
)
