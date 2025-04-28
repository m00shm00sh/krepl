package com.moshy.krepl.internal

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Runnable
import java.util.concurrent.Executor

private object VirtualThreadDispatcher : ExecutorCoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) =
        executor.execute(block)
    override val executor: Executor = Executor(Thread::startVirtualThread)
    override fun close() = error("not applicable")
    override fun toString(): String = "VirtualThreadDispatcher"
}
// JDK 24 handles @Synchronized locks without thread pinning so we can use virtual threads more scalably there
internal val JvmIODispatcher: CoroutineDispatcher
    get() =
        if (Runtime.version().feature() >= 21) {
            VirtualThreadDispatcher
        } else
            Dispatchers.IO

