package app.gyrolet.mpvrx.exoplayer.core.common

annotation class Dispatcher(val niaDispatcher: NextDispatchers)

enum class NextDispatchers {
    Default,
    IO,
}

