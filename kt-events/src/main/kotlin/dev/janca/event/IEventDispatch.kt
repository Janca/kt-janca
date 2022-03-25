package dev.janca.event

import kotlin.reflect.KClass

interface IEventDispatch {
    fun register(registrant: Any): List<EventHandler<IEvent>>
    fun <E : IEvent> register(eventType: KClass<E>, handler: EventHandler<E>): EventHandler<E>

    fun deregister(registrant: Any)
    fun <E : IEvent> deregister(handler: EventHandler<E>)

    fun post(event: IEvent)
}