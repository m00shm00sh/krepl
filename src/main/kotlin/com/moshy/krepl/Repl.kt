package com.moshy.krepl

import com.moshy.krepl.internal.NoncloseableOutputSendChannel
import com.moshy.krepl.internal.HeartbeatOutputSendChannel
import com.moshy.krepl.internal.fileReader
import com.moshy.krepl.internal.lazyString
import com.moshy.krepl.internal.lineConsumer
import com.moshy.krepl.internal.lineProducer
import com.moshy.krepl.internal.logger
import com.moshy.krepl.internal.makeState
import com.moshy.krepl.internal.tokenizeLine
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.util.concurrent.Semaphore
import kotlin.collections.set

/**
 * @param inputProducer suspending line producer
 * @param outputConsumer suspending line consumer
 * @param prompt prompt supplier; set to null to not print any prompt
 */
class Repl(
    private val inputProducer: InputProducer,
    private val outputConsumer: OutputConsumer,
    private val prompt: PromptSupplier? = { "" },
    private val allowOverwriteCommand: Boolean = false,
) {
    /**
     * @param inputStream input stream to launch a default line producer for
     * @param outputStream output stream to launch a default line consumer for
     * @param prompt prompt supplier; set to null to not print any prompt
     */
    constructor(
        inputStream: InputStream = System.`in`,
        outputStream: OutputStream = System.out,
        prompt: PromptSupplier? = { "" }
    ): this(
        lineProducer(inputStream.reader()),
        lineConsumer(outputStream.writer()),
        prompt
    )

    // block concurrent modification
    private val runSema = Semaphore(1)

    private inline fun <R> withRunLock(block: () -> R): R {
        if (!runSema.tryAcquire())
            throw IllegalStateException("active run lock")
        try {
            return block()
        } finally {
            runSema.release()
        }
    }

    /** Get calling context so that child coroutines have the desired parent. */
    private suspend inline fun scope(): CoroutineScope = CoroutineScope(currentCoroutineContext())

    private var collectedLines: List<String>? = null

    // Whether to dump stack trace when encountering an error.
    private var dumpStacktrace: Boolean = false
    fun enableDumpingStacktrace() { dumpStacktrace = true }
    fun disableDumpingStacktrace() { dumpStacktrace = false }
    // Classes in this set do not generate a stack trace.
    private val stacktraceExclusionFilter: MutableSet<KClass<out Throwable>> = mutableSetOf()
    // Classes in this set cause the repl to quit.
    private val stacktraceFatalFilter: MutableSet<KClass<out Throwable>> = mutableSetOf()

    /** Registers exception class [E] to cause the Repl to quit. */
    inline fun <reified E: Throwable> quitOnException() =
        quitOnException(E::class)
    fun quitOnException(kc: KClass<out Throwable>) {
        checkExceptionClass(kc, stacktraceFatalFilter)
        logger.debug("fatal-exception {}", kc)
        stacktraceFatalFilter.add(kc)
    }
    /** Registers exception class [E] to not print a stack trace. */
    inline fun <reified E: Throwable> filterFromStacktrace() =
        filterFromStacktrace(E::class)
    fun filterFromStacktrace(kc: KClass<out Throwable>) {
        checkExceptionClass(kc, stacktraceExclusionFilter)
        logger.debug("filter-stack-trace {}", kc)
        stacktraceExclusionFilter.add(kc)
    }

    private fun checkExceptionClass(kc: KClass<out Throwable>, existing: Set<KClass<out Throwable>>) =
        when (kc) {
            CancellationException::class, EOFException::class ->
                throw IllegalArgumentException("$kc has special handling")
            else -> {
                for (existingKc in existing)
                    if (kc.isSubclassOf(existingKc)) {
                        val viaBase = if (kc != existingKc) " via $existingKc" else ""
                        throw IllegalArgumentException("$kc already specified$viaBase")
                    }
            }
        }

    private val builtins: Map<String, CommandEntry> = buildMap {
        val cExit = CommandEntry("exit",
                usage = "exit",
                help = "Exits the interpreter"
            ) { throw Quit() }
        for (c in listOf(cExit.name, "quit"))
            this[c] = cExit
        val cHelp = CommandEntry("help",
                usage = "help [command]",
                help = "Print the list of available commands, or help for specified command"
            ) { (pos, _, _, out) -> help(pos, out) }
        for (c in listOf(cHelp.name, "?"))
            this[c] = cHelp
        val cCollect = CommandEntry("collect",
                usage = "<< delimiter",
                help = "Collects lines until delimiter sequence, then saves the lines" +
                        " for the next command with line-consume semantics"
            ) { (pos, _, `in`, out) ->
                val delim = pos.requireOne { "expected one delimiter" }
                delimCollector(append = false, delim, `in`, out)
            }
        for (c in listOf(cCollect.name, "collect-lines", "<<"))
            this[c] = cCollect
        val cCollectMore = CommandEntry("collect-more",
                usage = "+<< delimiter",
                help = "Collects more lines until delimiter sequence is printer, then appends the lines" +
                        " for the next command with line-consume semantics"
            ) { (pos, _, `in`, out) ->
                val delim = pos.requireOne { "expected one delimiter" }
                delimCollector(append = true, delim, `in`, out)
            }
        for (c in listOf(cCollectMore.name, "collect-more-lines", "+<<"))
            this[c] = cCollectMore
        val cCollectFromFile = CommandEntry("collect-from-file",
                usage = "< filename",
                help = "Collects lines from a file, then saves the lines" +
                        " for the next command with line-consume semantics"
            ) { (pos, _, _, out) ->
                val fileName = pos.requireOne { "expected one filename" }
                fileCollector(append = false, fileName, out)
            }
        for (c in listOf(cCollectFromFile.name, "<"))
            this[c] = cCollectFromFile
        val cCollectMoreFromFile = CommandEntry("collect-more-from-file",
                usage = "+< filename",
                help = "Collects more lines from a file, then appends the lines" +
                        " for the next command with line-consume semantics"
            ) { (pos, _, _, out) ->
                val fileName = pos.requireOne { "expected one filename" }
                fileCollector(append = true, fileName, out)
            }
        for (c in listOf(cCollectMoreFromFile.name, "+<"))
            this[c] = cCollectMoreFromFile
        val cPeekBuf = CommandEntry("peek",
                usage = "peek",
                help = "Dumps contents of collection buffer without consuming it",
                semantics = LineSemantics.PEEK
            ) { (lines, _, _, out) ->
                lines.forEach { out.send(it.asLine()) }
            }   
        for (c in listOf(cPeekBuf.name, "peek-collection-buffer", "?<"))
            this[c] = cPeekBuf
        val cClearBuf = CommandEntry("clear",
                usage = "clear",
                help = "Clears collection buffer",
                semantics = LineSemantics.CONSUME
            ) { }
        for (c in listOf(cClearBuf.name, "clear-collection-buffer", "!<"))
            this[c] = cClearBuf
        if (logger.isTraceEnabled) {
            for ((k, v) in this.entries)
                logger.trace("builtin {} {}", quote(k), quote(v.name))
        }
    }

    val builtinCommands: Set<String>
        get() = builtins.keys

    private val commands: MutableMap<String, CommandEntry> = mutableMapOf()

    val registeredCommands: Set<String>
        get() = commands.keys
    
    /** Register a command.
     * 
     * Usage:
     * ```
     *      repl[command] {
     *          handler = ...
     *          ...
     *      }
     * ```
     * @see EntryBuilder
     */
    operator fun get(name: String): EntryInvoker =
        name.lowercase().let { Entry(it, commands[it]) }

    /**
     * Register an alias.
     *
     * Usage:
     * ```
     *      repl[alias] = repl[command]
     * ```
     * Note: [name] cannot be a builtin
     */
    operator fun set(name: String, value: EntryInvoker) {
        require(value is Entry) {
            "unexpected entry type"
        }
        requireNotNull(value.old) {
            "command ${value.name} not registered (aliasing a builtin is not supported)"
        }
        val nameLower = name.lowercase()
        if (!allowOverwriteCommand) {
            val owner = commands[nameLower]
            require(owner == null) {
                "command ${quote(owner!!.name)} has claimed alias ${quote(nameLower)}"
            }
        }
        commands[nameLower] = value.old
    }

    /** Unregister command or alias. */
    fun remove(name: String): Boolean =
        name.lowercase().let {
            require (it !in builtinCommands) {
                "refusing to remove builtin"
            }
            commands.remove(it) != null
        }

    /** Unregister all non-builtin commands or aliases. */
    fun clear() {
        commands.clear()
    }

    interface EntryInvoker {
        operator fun invoke(block: EntryBuilder.() -> Unit): EntryInvoker
    }
    internal inner class Entry(val name: String, val old: CommandEntry? = null) : EntryInvoker {
        override operator fun invoke(block: EntryBuilder.() -> Unit): Entry {
            val builder = EntryBuilder().apply(block)
            if (!allowOverwriteCommand) {
                val existing = commands[name]
                require(existing == null) {
                    "another command or alias has claimed ${quote(name)}"
                }
            }
            requireNotNull(builder.handler) {
                "unset handler"
            }
            val entry = builder.build(name)
            commands[name] = entry
            return Entry(name, entry)
        }
    }


    // TODO: do we need `val refCount = atomic(0)` to upgrade orphan aliases to commands?
    internal class CommandEntry internal constructor(
        val name: String,
        val help: String? = null,
        val usage: String? = null,
        val semantics: LineSemantics = LineSemantics.NONE,
        val handler: (suspend Repl.(State) -> Unit),
    )

    /**
     * @property handler command handler
     * @property help detail help message
     * @property usage short usage method (include command name here)
     * @property semantics line buffer semantics
     * @see [LineSemantics]
     * @see [State]
     */
    class EntryBuilder internal constructor(
        var handler: (suspend Repl.(State) -> Unit)? = null,
        var help: String? = null,
        var usage: String? = null,
        var semantics: LineSemantics = LineSemantics.NONE,
    ) {
        internal fun build(name: String) =
            CommandEntry(
                handler = handler!!,
                name = name,
                help = help,
                usage = usage,
                semantics = semantics,
        )
    }

    enum class LineSemantics {
        NONE, CONSUME, PEEK
    }

    /** Start the REPL.
     * @throws Throwable if the exception that quit the loop was unintentional
     */
    suspend fun run() = withRunLock {
        checkForProblematicState()
        val scope = scope()
        val childScope = scope + SupervisorJob() + CoroutineName("Exec")
        val inputChannel: Channel<String> = Channel()
        val outputChannel: Channel<Output> = Channel()
        val outputChannelThrowing = HeartbeatOutputSendChannel(outputChannel)

        logger.debug("run: begin")
        val inputJob = scope.launch { inputChannel.inputProducer() }
        val outputJob = scope.launch { outputChannel.outputConsumer() }

        suspend fun sendPrompt() {
            prompt?.let {
                outputChannelThrowing.send((it.invoke() + " $ ").asNonLine())
            }
        }
        fun needLines() =
            collectedLines ?: throw ReplInvalidArgument("no collected lines")
        fun consumeLines() =
            needLines().let {
                collectedLines = null
                return@let it
            }
        fun peekLines() = needLines()

        logger.trace("run: input worker: running")
        logger.trace("run: output worker: running")

        var canceller: Throwable? = null
        var failCmd = ""
        while (canceller == null) {
            // keeps track of channel vs command execution exception
            var childException: Throwable? = null
            try {
                sendPrompt()
                val line = inputChannel.receive().trim()
                if (line.isEmpty())
                    continue
                val toks = line.tokenizeLine()
                val cmd = builtins[toks.cmd] ?: commands[toks.cmd]
                    ?: throw ReplInvalidArgument("unrecognized command: ${toks.cmd}")
                suspend fun invokeHandler(s: State) = (this@Repl).(cmd.handler)(s)
                logger.trace("run: cmd={} semantics={}", toks.cmd, cmd.semantics)
                val cmdState = makeState(toks, inputChannel, outputChannelThrowing)
                logger.trace("run: cmd={} pos={} kw={}", toks.cmd, toks.pos, toks.kw)
                childScope.launch {
                    try {
                        when (cmd.semantics) {
                            LineSemantics.NONE -> invokeHandler(cmdState)
                            LineSemantics.CONSUME -> {
                                invokeHandler(cmdState.copy(positionalOrLines = consumeLines()))
                            }
                            LineSemantics.PEEK -> {
                                invokeHandler(cmdState.copy(positionalOrLines = peekLines()))
                            }
                        }
                    } catch (e: Throwable) {
                        childException = e
                        failCmd = toks.cmd
                    }
                }.join()
                childException?.let { throw it }
            } catch (e: Throwable) {
                val cancellation = checkException(e, childException === e, failCmd)
                canceller = cancellation.first
                try {
                    for (line in cancellation.second)
                        outputChannelThrowing.send(line.asLine())
                } catch (_: CancellationException) {
                    // if the cancellation was in the output consumer, we can't do anything output-wise
                    logger.trace("aborting writing exception details to closed output channel")
                }
            }
            @Suppress("USELESS_IS_CHECK") // false diagnostic
            when (canceller) {
                null -> {}
                is Quit -> {
                    logger.debug("run: normal quit")
                    outputChannel.close()
                    inputChannel.cancel(canceller)
                }
                else -> {
                    logger.debug("run: fatal-exception cleanup")
                    val asCancellation = Quit("$canceller").apply { addSuppressed(canceller) }
                    inputChannel.cancel(asCancellation)
                    outputChannel.close(asCancellation)
                }
            }
        }
        inputJob.join()
        outputJob.join()
        if (canceller !is Quit)
            throw canceller
    }

    private fun checkForProblematicState() {
        require(dumpStacktrace || stacktraceExclusionFilter.isEmpty()) {
            "stack trace filter must be empty if stacktrace dump is disabled"
        }
    }

    private fun checkException(receivedException: Throwable, isChildException: Boolean, commandName: String)
    : Pair<Throwable?, List<String>> {
        var retException: Throwable? = null
        var retLines: List<String> = emptyList()
        when {
            /* the first two exception classes are private to Repl so we don't have to check that it
             * wasn't thrown from a failing child
             */
            receivedException is ReplInvalidArgument -> {
                logger.trace("run: invalid line")
                retLines = listOf("(E) " + checkNotNull(receivedException.message))
            }

            receivedException is Quit -> {
                logger.trace("run: received quit")
                retException = receivedException
            }

            receivedException is ClosedReceiveChannelException && !isChildException -> {
                logger.trace("run: input channel closed")
                retException = Quit()
            }

            // input supplier cancelled with unexpected EOF
            receivedException is EOFException && !isChildException -> {
                logger.trace("run: (input?) channel EOF without normal close()")
                retException = receivedException
            }

            // output suppl
            receivedException is CancellationException && !isChildException -> {
                logger.debug("run: other cancellation {}", lazyString { receivedException.toString() })
                retLines = buildList {
                    add("(E) Received cancellation: $receivedException")
                }
                retException = receivedException
            }
            else -> { // catch (e: Throwable)
                val receivedExceptionStr = receivedException.toString()
                logger.debug(
                    "run: {}exception: {}",
                    "child ".takeIf { isChildException } ?: "",
                    receivedExceptionStr
                )
                retLines = buildList {
                    val qCmd = quote(commandName)
                    add("($qCmd:E) $receivedExceptionStr")
                    if (isChildException) {
                        if (dumpStacktrace) {
                            if (stacktraceExclusionFilter.any { it.isInstance(receivedException) }) {
                                logger.trace("exception in exclusion filter; omitting stack trace")
                            } else {
                                receivedException.stackTraceToString().lineSequence().drop(1).forEach { l ->
                                    add("($qCmd:E) $l")
                                }
                            }
                        }
                        if (stacktraceFatalFilter.any { it.isInstance(receivedException) }) {
                            logger.trace("exception marked fatal; quitting")
                            retException = receivedException
                        }
                    } else { // unexpected exception from channel operation
                        retException = receivedException
                    }
                }
            }
        }
        return retException to retLines
    }

    private suspend fun help(pos: List<String>, out: OutputSendChannel) {
        fun generateUsage(name: String, isBuiltin: Boolean, c: CommandEntry): String {
            val qn = quote(name)
            val isB = if (isBuiltin) "(builtin) " else ""
            return when {
                c.name != name -> "\t$isB$qn alias for: ${quote(c.name)}"
                c.semantics == LineSemantics.NONE -> "\t$isB$qn <no usage message>"
                else -> "\t$isB$qn <requires 0+ collected lines>"
            }
        }

        when (pos.size) {
            0 -> {
                out.send("Commands:".asLine())
                for ((name, cmd) in builtins) {
                    when {
                        cmd.name != name || cmd.usage.isNullOrEmpty() -> generateUsage(name, true, cmd)
                        else -> "\t(builtin) ${cmd.usage}"
                    }.let { out.send(it.asLine()) }
                }
                for ((name, cmd) in commands) {
                    when {
                        cmd.name != name || cmd.usage.isNullOrEmpty() -> generateUsage(name, false, cmd)
                        else -> "\t${cmd.usage}"
                    }.let { out.send(it.asLine()) }
                }
            }
            1 -> {
                val name = pos[0].lowercase()
                val (cmd, isBuiltin) = builtins[name]?.let { it to true }
                    ?: commands[name]?.let { it to false }
                    ?: throw IllegalArgumentException("no match for command ${quote(name)}")
                cmd.help ?: throw IllegalArgumentException("no help message for command ${quote(name)}")
                when {
                    cmd.usage == null -> generateUsage(name, isBuiltin, cmd)
                    else -> "\t${cmd.usage}"
                }.let { out.send(it.substring(1).asLine()) }
                for (line in cmd.help.lineSequence())
                    out.send(line.asLine())
            }
            else -> throw IllegalArgumentException("too many arguments")
        }
    }

    private suspend fun delimCollector(
        append: Boolean,
        delimiter: String,
        inputCh: InputReceiveChannel,
        outputCh: OutputSendChannel
    ) {
        val lines = buildList {
            for (line in inputCh) {
                if (line == delimiter)
                    break
                add(line)
            }
        }

        if (append && !collectedLines.isNullOrEmpty()) {
            collectedLines = collectedLines?.plus(lines)
            outputCh.send("${lines.size} added (${collectedLines?.size} total)".asLine())
        } else {
            collectedLines = lines
            outputCh.send("${lines.size} collected".asLine())
        }
    }

    // internal for testing but could be an arbitrary file reader supplier in the future
    internal var fileReader: (String) -> Reader = ::fileReader

    private suspend fun fileCollector(append: Boolean, fileName: String, outputCh: OutputSendChannel) {
        val scope = scope()
        var caught: Throwable? = null
        val lines = buildList {
            fileReader(fileName).use {
                val lineCh: Channel<String> = Channel()
                val producerSupplier = lineProducer(it)
                val producer = scope.launch { lineCh.producerSupplier() }
                val consumer = scope.launch(CoroutineName("File-Reader")) {
                    try {
                        for (line in lineCh)
                            add(line)
                    } catch (e: IOException) {
                        caught = e
                    }
                }
                consumer.join()
                producer.join()
            }
            caught?.let { throw it }
        }
        if (append && !collectedLines.isNullOrEmpty()) {
            collectedLines = collectedLines?.plus(lines)
            outputCh.send("${lines.size} added (${collectedLines?.size} total)".asLine())
        } else {
            collectedLines = lines
            outputCh.send("${lines.size} collected".asLine())
        }
    }

    /** @see invoke */
    companion object {

    }
}

/* Exception to invalid input thrown by Repl. We subclass IAE so we can catch it and therefore not
 * produce stack traces.
 */
private class ReplInvalidArgument(message: String): IllegalArgumentException(message)

private class Quit(s: String): CancellationException(s) {
    constructor (): this("Quit")
}

private inline fun List<String>.requireOne(lazyMessage: () -> String) =
    takeIf { size == 1 }?.first() ?: throw IllegalArgumentException(lazyMessage())

private fun quote(s: String) = when (s.indexOf(' ')) {
    -1 -> s
    else -> "\"" + s.replace("\"", "\\\"") + "\""
}

private val logger by lazy { logger("Repl") }
