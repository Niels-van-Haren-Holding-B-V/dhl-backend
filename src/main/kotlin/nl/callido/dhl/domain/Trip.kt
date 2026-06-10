package nl.callido.dhl.domain

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "trip")
class Trip(@Id val id: UUID, val name: String, val tripDate: LocalDate)
