package dev.janca.util

fun <K, V> MutableMap<K, V>.iterate(block: MutableIterator<MutableMap.MutableEntry<K, V>>.(MutableMap.MutableEntry<K, V>) -> Unit) {
    val iterator: MutableIterator<MutableMap.MutableEntry<K, V>> = iterator()
    iterator.iterate(block)
}

fun <T> MutableIterable<T>.iterate(block: MutableIterator<T>.(T) -> Unit) {
    val iterator = iterator()
    iterator.iterate(block)
}

fun <T> MutableIterator<T>.iterate(block: MutableIterator<T>.(T) -> Unit) {
    while (hasNext()) {
        val next = next()
        block(this, next)
    }
}