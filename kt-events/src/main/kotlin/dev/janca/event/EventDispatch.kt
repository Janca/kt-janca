package dev.janca.event

import dev.janca.util.iterate
import org.slf4j.LoggerFactory
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

open class EventDispatch() : IEventDispatch {
    private constructor(parent: IEventDispatch? = null) : this() {
        this.parent = parent
    }

    companion object {
        val DEFAULT_EVENT_HANDLER_FILTER: (KCallable<*>) -> Boolean =
            { it is KFunction<*> && it.hasAnnotation<Subscriber>() }
    }

    private var parent: IEventDispatch? = null
    private val children = HashSet<IEventDispatch>()

    private val logger = LoggerFactory.getLogger(EventDispatch::class.java)

    private val registrants = HashMap<Any, HashMap<KClass<IEvent>, MutableList<EventListener<IEvent>>>>()
    private val subscriberLock = ReentrantReadWriteLock()

    private val dispatchingThreadLocal = ThreadLocal.withInitial { false }
    private val eventQueueThreadLocal = ThreadLocal.withInitial { java.util.ArrayDeque<QueuedEventProcessor>() }

    open var handlerFilter: (KCallable<*>) -> Boolean = DEFAULT_EVENT_HANDLER_FILTER

    override fun register(registrant: Any): List<EventListener<IEvent>> {
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

        val listeners = handlers.flatMap { it.value }
        logger.debug("Registered {} listener(s) from '{}'.", listeners.size, registrant::class.qualifiedName)

        return listeners
    }

    @Suppress("UNCHECKED_CAST")
    override fun <E : IEvent> register(eventType: KClass<E>, listener: EventListener<E>): EventListener<E> {
        val eventListener = listener as EventListener<IEvent>

        subscriberLock.write {
            val subscribers = registrants.computeIfAbsent(this@EventDispatch) { HashMap() }
            subscribers.computeIfAbsent(eventType as KClass<IEvent>) { ArrayList() }
                .add(eventListener)
        }

        return listener
    }

    override fun deregister(registrant: Any) {
        subscriberLock.write {
            val subscribers = registrants[registrant] ?: return
            subscribers.clear()

            registrants.remove(registrant)
        }
    }

    override fun <E : IEvent> deregister(listener: EventListener<E>) {
        subscriberLock.write {
            registrants.iterate registrantsIterator@{ registrantsSubscribers ->
                val (_, subscribers) = registrantsSubscribers
                subscribers.iterate subscribersIterator@{ typedSubscribers ->
                    val (_, eventHandlers) = typedSubscribers
                    eventHandlers.iterate eventHandlersIterator@{ eventHandler ->
                        if (eventHandler == listener) {
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
        children.forEach {
            it.post(event)
        }
    }

    fun <E : IEvent> once(eventType: KClass<E>, handler: (E) -> Unit) {
        register(eventType, object : EventListener<E> {
            override fun handle(event: E) {
                try {
                    handler(event)
                } finally {
                    deregister(this)
                }
            }
        })
    }

    override fun child(): IEventDispatch {
        val eventDispatch = EventDispatch(this)
        children.add(eventDispatch)
        return eventDispatch
    }

    override fun filteredChild(predicate: (IEvent) -> Boolean): IEventDispatch {
        val eventDispatch = object : EventDispatch(this@EventDispatch) {
            override fun post(event: IEvent) {
                when {
                    predicate(event) -> super.post(event)
                }
            }
        }

        children.add(eventDispatch)
        return eventDispatch
    }

    override fun destroy() {
        children.forEach { it.destroy() }
        subscriberLock.write {
            registrants.iterate { deregister(it.key) }
        }
    }

    protected open fun newSyntheticHandler(owner: Any, handler: KFunction<*>): EventListener<IEvent> {
        return SyntheticEventListener(owner, handler)
    }

    private fun processEventQueue() {
        when {
            dispatchingThreadLocal.get() -> return
            else -> {
                dispatchingThreadLocal.set(true)
                val dequeue = eventQueueThreadLocal.get()

                try {

                    do {
                        dequeue.poll()?.handle() ?: break
                    } while (dequeue.isNotEmpty())

                } finally {

                    dispatchingThreadLocal.remove()
                    eventQueueThreadLocal.remove()

                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findHandlers(registrant: Any): Map<KClass<IEvent>, List<EventListener<IEvent>>> {
        val handlers = HashMap<KClass<IEvent>, MutableList<EventListener<*>>>()
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

            logger.debug("Found subscriber method '${subscriber.signature}'.")
            handlers.computeIfAbsent(eventType as KClass<IEvent>) { ArrayList() }
                .add(newSyntheticHandler(registrant, subscriber))
        }

        return handlers as Map<KClass<IEvent>, List<EventListener<IEvent>>>
    }

    private fun EventListener<*>.enqueue(event: IEvent) {
        eventQueueThreadLocal.get().addLast(QueuedEventProcessor(this, event))
    }

    private val KFunction<*>.signature: String
        get() = "${this.javaMethod?.declaringClass?.simpleName}.${this.name}(${this.valueParameters.joinToString(",") { "${it.name}: ${(it.type.classifier as KClass<*>).simpleName}" }})"

    private fun KClass<*>.hierarchy(): List<KClass<*>> {
        val hierarchy = HashSet<KClass<*>>()

        hierarchy.add(this)
        hierarchy.addAll(allSuperclasses)

        return hierarchy.filter { it.isSubclassOf(IEvent::class) }
    }

    private class SyntheticEventListener(private val owner: Any, handler: KFunction<*>) : EventListener<IEvent> {

        private val handler: KFunction<*>

        init {
            handler.isAccessible = true
            this.handler = handler
        }

        override fun handle(event: IEvent) {
            handler.call(owner, event)
        }
    }

    private class QueuedEventProcessor(private val handler: EventListener<*>, private val event: IEvent) {

        fun handle() {
            try {
                handler.handle(event)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

    }

}