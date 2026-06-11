package nl.callido.dhl.service.locker

import nl.callido.dhl.domain.LockerSessionStatus

/**
 * The session exists and is the caller's, but is no longer ACTIVE — it was
 * finished or reaped. Mapped to a 409 with a constant body; the courier app
 * recovers from the status poll, not from this message.
 */
class SessionNotActiveException(status: LockerSessionStatus) : RuntimeException("session is $status")
