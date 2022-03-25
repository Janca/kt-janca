package dev.janca.util

sealed interface Fork<out L, out R> {
    interface Left<out L> : Fork<L, Nothing> {
        val value: L
    }

    interface Right<out R> : Fork<Nothing, R> {
        val value: R
    }
}

fun <L, R> Fork<L, R>.getOrDefault(default: R): R {
    return traverse(
        left = { default },
        right = { it }
    )
}

inline fun <L, R> Fork<L, R>.getOrElse(factory: () -> R): R {
    return traverse(
        left = { factory() },
        right = { it }
    )
}

inline fun <L, R, P> Fork<L, R>.traverse(left: (L) -> P, right: (R) -> P): P {
    return when (this) {
        is Fork.Left -> left(value)
        is Fork.Right -> right(value)
    }
}

inline fun <L, R, S> Fork<L, R>.map(transform: (R) -> S): Fork<L, S> {
    return when (this) {
        is Fork.Left -> this
        is Fork.Right -> transform(value).right()
    }
}

inline fun <L, R, S> Fork<L, R>.flatMap(transform: (R) -> Fork<L, S>): Fork<L, S> {
    return when (this) {
        is Fork.Left -> this
        is Fork.Right -> transform(value)
    }
}

inline fun <L, M, R> Fork<L, R>.mapError(transform: (L) -> M): Fork<M, R> {
    return when (this) {
        is Fork.Left -> transform(value).left()
        is Fork.Right -> this
    }
}

fun <L> L.left(): Fork<L, Nothing> {
    return object : Fork.Left<L> {
        override val value: L get() = this@left
    }
}

fun <R> R.right(): Fork<Nothing, R> {
    return object : Fork.Right<R> {
        override val value: R get() = this@right
    }
}

fun <R> tryFork(block: () -> R): Fork<Exception, R> {
    return try {
        block().right()
    } catch (exception: Exception) {
        exception.left()
    }
}