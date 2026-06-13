package nl.callido.dhl.service.locker

import nl.callido.dhl.domain.LockerSessionStatus

class SessionNotActiveException(status: LockerSessionStatus) : RuntimeException("session is $status")
