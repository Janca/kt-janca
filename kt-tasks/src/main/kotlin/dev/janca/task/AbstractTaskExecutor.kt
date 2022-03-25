package dev.janca.task

import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

abstract class AbstractTaskExecutor<in A, B>(private val executorService: ExecutorService) : ITask<A, B>,
    ITaskExecutor<A, B> {

    private val completionService: CompletionService<B> = ExecutorCompletionService(executorService)

    override val isShutdown: Boolean get() = executorService.isShutdown
    override val isTerminated: Boolean get() = executorService.isTerminated

    override fun submit(element: A): Future<B> {
        return completionService.submit {
            this.process(element)
        }
    }

    override fun shutdown(interrupt: Boolean) {
        when {
            interrupt -> executorService.shutdownNow()
            else -> {
                executorService.shutdown()

                try {
                    executorService.awaitTermination(10_000, TimeUnit.MILLISECONDS)
                } finally {
                    if (!executorService.isTerminated) {
                        executorService.shutdownNow()
                    }
                }
            }
        }
    }

    override fun take(): Future<B> = completionService.take()

    override fun poll(): Future<B>? = completionService.poll()

    override fun poll(timeout: Long, unit: TimeUnit): Future<B>? = completionService.poll(timeout, unit)
}

abstract class AbstractFixedThreadPoolTaskExecutor<in A, B>(nThreads: Int, threadFactory: ThreadFactory? = null) :
    AbstractTaskExecutor<A, B>(
        Executors.newFixedThreadPool(
            nThreads,
            threadFactory ?: Executors.defaultThreadFactory()
        )
    )

abstract class AbstractCachedThreadPoolTaskExecutor<in A, B>(threadFactory: ThreadFactory? = null) :
    AbstractTaskExecutor<A, B>(Executors.newCachedThreadPool(threadFactory ?: Executors.defaultThreadFactory()))

abstract class AbstractSingleThreadTaskExecutor<in A, B>(threadFactory: ThreadFactory? = null) :
    AbstractTaskExecutor<A, B>(Executors.newSingleThreadExecutor(threadFactory ?: Executors.defaultThreadFactory()))