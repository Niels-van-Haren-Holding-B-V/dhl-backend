package nl.callido.dhl.repository

import nl.callido.dhl.domain.Trip
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TripRepository : JpaRepository<Trip, UUID>
