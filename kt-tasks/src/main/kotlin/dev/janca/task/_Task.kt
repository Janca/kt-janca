package dev.janca.task

import dev.janca.event.IEvent
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

interface ITaskExecutor<in A, B> : ITask<A, B> {

    val isShutdown: Boolean
    val isTerminated: Boolean

    fun shutdown(interrupt: Boolean)

    fun submit(element: A): Future<B>

    fun take(): Future<B>

    fun poll(): Future<B>?

    fun poll(timeout: Long, unit: TimeUnit): Future<B>?

}

@FunctionalInterface
fun interface ITask<in A, out B> : (A) -> B {
    interface Item : IEvent

    fun process(element: A): B
    override fun invoke(element: A): B = process(element)
}