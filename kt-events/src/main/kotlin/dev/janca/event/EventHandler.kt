package dev.janca.event

interface EventHandler<E : IEvent> {

    fun handle(event: E)

    @Suppress("UNCHECKED_CAST")
    fun handle(event: Any) = handle(event as E)

}