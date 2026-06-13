package nl.callido.dhl.service.locker

class SessionForbiddenException : RuntimeException("session owned by another courier")
