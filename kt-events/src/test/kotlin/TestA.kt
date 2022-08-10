import dev.janca.event.EventDispatch
import dev.janca.event.IEvent
import java.util.concurrent.ThreadLocalRandom

data class TestEvent(val value: Int) : IEvent

fun main() {
    val dispatch = EventDispatch()
    val child = dispatch.filteredChild {
        when (it) {
            !is TestEvent -> false
            else -> it.value < 10
        }
    }

    dispatch.register(TestEvent::class) {
        println("Parent: $it")
    }

    child.register(TestEvent::class) {
        println("Child: $it")
    }

    for (i in 0..100) {
        val next = ThreadLocalRandom.current().nextInt(0, 20)
        dispatch.post(TestEvent(next))

        Thread.sleep(1000)
    }

}