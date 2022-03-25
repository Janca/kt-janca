package dev.janca.event

interface ISourcedEvent<T> : IEvent {

    val source: T

}