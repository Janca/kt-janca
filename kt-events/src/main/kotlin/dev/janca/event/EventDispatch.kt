package dev.janca.event

import dev.janca.util.iterate
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaMethod

open class EventDispatch(private val parent: IEventDispatch? = null) : IEventDispatch {
    companion object {
        val DEFAULT_EVENT_HANDLER_FILTER: (KCallable<*>) -> Boolean = { it is KFunction<*> && it.hasAnnotation<Subscriber>() }
    }

    private val registrants = HashMap<Any, HashMap<KClass<IEvent>, MutableList<EventHandler<IEvent>>>>()
    private val subscriberLock = ReentrantReadWriteLock()

    private val dispatchingThreadLocal = ThreadLocal.withInitial { false }
    private val eventQueueThreadLocal = ThreadLocal.withInitial { java.util.ArrayDeque<QueuedEventProcessor>() }

    open var handlerFilter: (KCallable<*>) -> Boolean = DEFAULT_EVENT_HANDLER_FILTER

    override fun register(registrant: Any): List<EventHandler<IEvent>> {
        val handlers = findHandlers(registrant)
        if (handlers.isEmpty()) {
            throw IllegalArgumentException("Zero subscriber methods found in registrant '${registrant::class.qualifiedName}'.")
        }

        subscriberLock.write {
            val subscribers = registrants.computeIfAbsent(registrant) { HashMap() }
            handlers.forEach {
                val (eventType, eventHandlers) = it
                subscribers.computeIfAbsent(eventType) { ArrayList() }
                    .addAll(eventHandlers)
            }
        }

        return handlers.flatMap { it.value }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <E : IEvent> register(eventType: KClass<E>, handler: EventHandler<E>): EventHandler<E> {
        val eventHandler = handler as EventHandler<IEvent>

        subscriberLock.write {
            val subscribers = registrants.computeIfAbsent(this@EventDispatch) { HashMap() }
            subscribers.computeIfAbsent(eventType as KClass<IEvent>) { ArrayList() }
                .add(eventHandler)
        }

        return handler
    }

    override fun deregister(registrant: Any) {
        subscriberLock.write {
            val subscribers = registrants[registrant] ?: return
            subscribers.clear()

            registrants.remove(registrant)
        }
    }

    override fun <E : IEvent> deregister(handler: EventHandler<E>) {
        subscriberLock.write {
            registrants.iterate registrantsIterator@{ registrantsSubscribers ->
                val (_, subscribers) = registrantsSubscribers
                subscribers.iterate subscribersIterator@{ typedSubscribers ->
                    val (_, eventHandlers) = typedSubscribers
                    eventHandlers.iterate eventHandlersIterator@{ eventHandler ->
                        if (eventHandler == handler) {
                            this@eventHandlersIterator.remove()
                        }
                    }

                    if (eventHandlers.isEmpty()) {
                        this@subscribersIterator.remove()
                    }
                }

                if (subscribers.isEmpty()) {
                    this@registrantsIterator.remove()
                }
            }
        }
    }

    override fun post(event: IEvent) {
        val eventType = event::class
        val typeHierarchy = eventType.hierarchy()

        var dispatched = false
        subscriberLock.read {
            registrants.forEach { (_, subscribers) ->
                typeHierarchy.forEach hierarchy@{
                    val handlers = subscribers[it]
                    if (handlers.isNullOrEmpty()) {
                        return@hierarchy
                    }

                    dispatched = true
                    handlers.forEach { handler -> handler.enqueue(event) }
                }
            }
        }

        if (!dispatched && event !is DeadEvent<*>) {
            post(DeadEvent(this@EventDispatch, event))
        }

        processEventQueue()
        parent?.let {
            if (event !is IGlobalEvent) {
                it.post(ChildDispatchEvent(this@EventDispatch, event))
            }
        }
    }

    fun <E : IEvent> on(eventType: KClass<E>, handler: (E) -> Unit) =
        register(eventType, object : EventHandler<E> {
            override fun handle(event: E) {
                handler(event)
            }
        })

    fun <E : IEvent> once(eventType: KClass<E>, handler: (E) -> Unit) {
        register(eventType, object : EventHandler<E> {
            override fun handle(event: E) {
                try {
                    handler(event)
                } finally {
                    deregister(this)
                }
            }
        })
    }

    protected open fun newSyntheticHandler(owner: Any, handler: KFunction<*>): EventHandler<IEvent> {
        return SyntheticEventHandler(owner, handler)
    }

    private fun processEventQueue() {
        when {
            dispatchingThreadLocal.get() -> return
            else -> {

                dispatchingThreadLocal.set(true)

                val dequeue = eventQueueThreadLocal.get()
                var processor: QueuedEventProcessor

                try {

                    do {
                        processor = dequeue.poll() ?: break
                        processor.handle()
                    } while (dequeue.isNotEmpty())

                } finally {

                    dispatchingThreadLocal.remove()
                    eventQueueThreadLocal.remove()

                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findHandlers(registrant: Any): Map<KClass<IEvent>, List<EventHandler<IEvent>>> {
        val handlers = HashMap<KClass<IEvent>, MutableList<EventHandler<*>>>()
        val subscriberFunctions = registrant::class.members.filter(handlerFilter)
            .map { it as KFunction<*> }

        for (subscriber in subscriberFunctions) {
            val parameters = subscriber.valueParameters
            if (parameters.size != 1) {
                throw IllegalArgumentException("Invalid number of parameters on subscriber method. $subscriber")
            }

            val eventType = parameters.first().type.classifier as KClass<*>
            if (!eventType.isSubclassOf(IEvent::class)) {
                throw IllegalArgumentException("Invalid parameter type on subscriber method '${subscriber.signature}'. Expected single parameter of subtype '${IEvent::class.qualifiedName}'.")
            }

            handlers.computeIfAbsent(eventType as KClass<IEvent>) { ArrayList() }
                .add(newSyntheticHandler(registrant, subscriber))
        }

        return handlers as Map<KClass<IEvent>, List<EventHandler<IEvent>>>
    }

    private fun EventHandler<*>.enqueue(event: IEvent) {
        eventQueueThreadLocal.get().addLast(QueuedEventProcessor(this, event))
    }

    private val KFunction<*>.signature: String
        get() = "${this.javaMethod?.declaringClass?.canonicalName}.${this.name}(${this.valueParameters.joinToString(",") { "${it.name}: ${(it.type.classifier as KClass<*>).simpleName}" }})"

    private fun KClass<*>.hierarchy(): List<KClass<*>> {
        val hierarchy = HashSet<KClass<*>>()

        hierarchy.add(this)
        hierarchy.addAll(allSuperclasses)

        return hierarchy.filter { it.isSubclassOf(IEvent::class) }
    }

    private class SyntheticEventHandler(private val owner: Any, handler: KFunction<*>) : EventHandler<IEvent> {

        private val handler: KFunction<*>

        init {
            handler.isAccessible = true
            this.handler = handler
        }

        override fun handle(event: IEvent) {
            handler.call(owner, event)
        }
    }

    private class QueuedEventProcessor(private val handler: EventHandler<*>, private val event: IEvent) {

        fun handle() {
            try {
                handler.handle(event)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

    }

}