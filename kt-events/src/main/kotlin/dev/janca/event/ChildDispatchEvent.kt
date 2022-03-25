package dev.janca.event

class ChildDispatchEvent<E : IEvent>(override val source: IEventDispatch, val sourceEvent: E) :  ISourcedEvent<IEventDispatch>