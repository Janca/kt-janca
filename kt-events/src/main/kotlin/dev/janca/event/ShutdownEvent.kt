package dev.janca.event

class ShutdownEvent(override val source: Any, val message: String = "", val exitCode: Int = 0) : IGlobalEvent, ISourcedEvent<Any>