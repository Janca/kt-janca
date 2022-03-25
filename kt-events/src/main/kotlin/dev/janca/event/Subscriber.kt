package dev.janca.event

@Retention
@Target(AnnotationTarget.FUNCTION)
annotation class Subscriber(val propagate: Boolean = true)
