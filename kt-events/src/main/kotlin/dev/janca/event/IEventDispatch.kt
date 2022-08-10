package dev.janca.event

import kotlin.reflect.KClass

interface IEventDispatch {
    fun register(registrant: Any): List<EventListener<IEvent>>
    fun <E : IEvent> register(eventType: KClass<E>, listener: EventListener<E>): EventListener<E>
    fun <E : IEvent> register(eventType: KClass<E>, listener: (E) -> Unit): EventListener<E> {
        return register(eventType, object : EventListener<E> {
            override fun handle(event: E) = listener.invoke(event)
        })
    }

    fun deregister(registrant: Any)
    fun <E : IEvent> deregister(listener: EventListener<E>)

    fun post(event: IEvent)
}