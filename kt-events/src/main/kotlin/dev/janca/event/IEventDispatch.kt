package dev.janca.event

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.reflect.KClass

interface IEventDispatch {
    fun register(registrant: Any): List<EventListener<IEvent>>
    fun <E : IEvent> register(eventType: KClass<E>, listener: EventListener<E>): EventListener<E>

    fun deregister(registrant: Any)
    fun <E : IEvent> deregister(listener: EventListener<E>)

    fun post(event: IEvent)
    fun postLater(executor: ExecutorService, event: IEvent): Future<Unit> = executor.submit<Unit> { post(event) }
}