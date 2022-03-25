package dev.janca.task

import dev.janca.event.EventDispatch
import dev.janca.event.IEventDispatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.valueParameters

class TaskDispatch(parentDispatch: IEventDispatch? = null) {

    private val dispatch: IEventDispatch = object : EventDispatch(parentDispatch) {
        override var handlerFilter: (KCallable<*>) -> Boolean = {
            when {
                it.name != "submit" -> false
                it.valueParameters.size != 1 -> false
                else -> (it.valueParameters[0].type.classifier as KClass<*>).isSubclassOf(ITask.Item::class)
            }
        }
    }

    private val executor = Executors.newCachedThreadPool()
    private val taskExecutors = ConcurrentHashMap<ITaskExecutor<*, *>, Future<*>>()

    fun <I : ITask.Item> post(item: I) = dispatch.post(item)

    fun <I : ITask.Item, R : ITask.Item> register(taskExecutor: ITaskExecutor<I, R>) {
        when {
            taskExecutors.containsKey(taskExecutor) -> throw IllegalArgumentException("TaskExecutor '${taskExecutor::class.simpleName}' is already registered.")
            else -> {
                dispatch.register(taskExecutor)
                val executorFuture = executor.submit {
                    while (!taskExecutor.isShutdown && !taskExecutor.isTerminated) {
                        try {
                            val nextFuture: Future<R> = taskExecutor.take()
                            val next = nextFuture.get()

                            dispatch.post(next)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }

                taskExecutors[taskExecutor] = executorFuture
            }
        }
    }

    fun <I : ITask.Item, R : ITask.Item> deregister(taskExecutor: ITaskExecutor<I, R>) {
        val executorFuture = taskExecutors[taskExecutor] ?: return

        executorFuture.cancel(true)
        taskExecutor.shutdown(true)
    }

    fun shutdown() {
        executor.shutdownNow()
        taskExecutors.forEach { (executor, future) ->
            future.cancel(true)
            executor.shutdown(true)
        }
    }

}