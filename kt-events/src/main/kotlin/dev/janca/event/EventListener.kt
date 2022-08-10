package dev.janca.event

@FunctionalInterface
interface EventListener<E : IEvent> {

    fun handle(event: E)

    @Suppress("UNCHECKED_CAST")
    fun handle(event: Any) = handle(event as E)

}