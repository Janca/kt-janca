package dev.janca.event

import kotlin.reflect.KClass

interface IEventDispatch {
    fun register(registrant: Any): List<EventHandler<IEvent>>
    fun <E : IEvent> register(eventType: KClass<E>, handler: EventHandler<E>): EventHandler<E>
    fun <E : IEvent> register(eventType: KClass<E>, handler: (E) -> Unit): EventHandler<E> {
        return register(eventType, object : EventHandler<E> {
            override fun handle(event: E) = handler.invoke(event)
        })
    }

    fun deregister(registrant: Any)
    fun <E : IEvent> deregister(handler: EventHandler<E>)

    fun post(event: IEvent)
}