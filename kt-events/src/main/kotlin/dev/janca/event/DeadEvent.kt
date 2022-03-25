package dev.janca.event

class DeadEvent<E : IEvent>(override val source: Any, val event: E) :ISourcedEvent<Any>