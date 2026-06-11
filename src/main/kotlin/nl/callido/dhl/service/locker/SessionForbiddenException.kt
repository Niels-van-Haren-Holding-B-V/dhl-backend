package nl.callido.dhl.service.locker

/**
 * The authenticated courier is not the owner of the requested session.
 * Mapped to a 403 with a constant body — no session detail leaks.
 */
class SessionForbiddenException : RuntimeException("session owned by another courier")
