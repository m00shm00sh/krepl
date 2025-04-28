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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.plus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader

class Repl private constructor(
    private val inputProducer: InputProducer,
    private val outputConsumer: OutputConsumer,
    private val prompt: PromptSupplier?
) {
    // block concurrent modification
    private val runLock = Mutex()

    private suspend inline fun <R> withRunLock(block: () -> R): R =
        runLock.withLock {
            block()
        }

    /** Get calling context so that child coroutines have the desired parent. */
    private suspend inline fun scope(): CoroutineScope = CoroutineScope(currentCoroutineContext())

    private var _atExit: (suspend (OutputSendChannel) -> Unit)? = null
    /** Set at-exit handler. Does not get invoked if a fatal exception was received. */
    suspend fun atExit(func: suspend (OutputSendChannel) -> Unit) = withRunLock {
        require(_atExit == null) {
            "at-exit handler already set"
        }
        logger.debug("set at-exit")
        _atExit = func
    }

    private var collectedLines: List<String>? = null

    private val commands: MutableMap<String, Command> = mutableMapOf()
    private val aliases: MutableMap<String, String> = mutableMapOf()

    /** Whether to dump stack trace when encountering an error. */
    var dumpStacktrace: Boolean = false
    // Classes in this set do not generate a stack trace.
    private val stacktraceExclusionFilter: MutableSet<KClass<out Throwable>> = mutableSetOf()
    // Classes in this set cause the repl to quit.
    private val stacktraceFatalFilter: MutableSet<KClass<out Throwable>> = mutableSetOf()

    /** Registers exception class [E] to cause the Repl to quit. */
    suspend inline fun <reified E: Throwable> quitOnException() =
        quitOnException(E::class)
    suspend fun quitOnException(kc: KClass<out Throwable>) = withRunLock {
        checkExceptionClass(kc, stacktraceFatalFilter)
        logger.debug("fatal-exception {}", kc)
        stacktraceFatalFilter.add(kc)
    }
    /** Registers exception class [E] to not print a stack trace. */
    suspend inline fun <reified E: Throwable> filterFromStacktrace() =
        filterFromStacktrace(E::class)
    suspend fun filterFromStacktrace(kc: KClass<out Throwable>) = withRunLock {
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

    private data class Command(
        val aliases: Set<String>,
        val help: String?,
        val usage: String?,
        val function: suspend (State) -> Unit,
        val semantics: LineSemantics,
    )

    /** Register a command with an optional number of aliases.
     *
     * Commands are mapped to lowercase.
     * @param help detail help message
     * @param usage short usage method (include command name here)
     * @param semantics line buffer semantics
     * @param function callback
     * @see [LineSemantics]
     * @see [State]
     */
    suspend fun registerCommand(
        name: String,
        vararg aliases: String,
        help: String? = null,
        usage: String? = null,
        semantics: LineSemantics = LineSemantics.NONE,
        function: suspend (State) -> Unit
    ) = withRunLock {
        val nameLower = name.lowercase()
        val aliasesLower = aliases.map(String::lowercase)
        require(nameLower !in commands) {
            "another command has claimed ${quote(nameLower)}"
        }
        for (a in aliasesLower) {
            val owner = this.aliases[a]
            require(owner == null) {
                "command ${quote(owner!!)} has claimed alias ${quote(a)}"
            }
        }
        this.aliases[nameLower]?.let {
            require(false) {
                "command ${quote(it)} has claimed ${quote(nameLower)}"
            }
        }

        val command = Command(
            aliases = aliasesLower.toSet(),
            help = help,
            usage = usage,
            function = function,
            semantics = semantics
        )
        commands[nameLower] = command
        for (a in aliasesLower) {
            this.aliases[a] = nameLower
        }
        if (aliasesLower.isNotEmpty())
            logger.trace("Registered command {} with alias(es) {}", nameLower, aliasesLower)
        else
            logger.trace("Registered command {}", nameLower)
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
                val cmd = commands[toks.cmd] ?: commands[aliases[toks.cmd]]
                    ?: throw ReplInvalidArgument("unrecognized command: ${toks.cmd}")
                logger.trace("run: cmd={} semantics={}", toks.cmd, cmd.semantics)
                val cmdState = makeState(toks, inputChannel, outputChannelThrowing)
                logger.trace("run: cmd={} pos={} kw={}", toks.cmd, toks.pos, toks.kw)
                childScope.launch {
                    try {
                        when (cmd.semantics) {
                            LineSemantics.NONE -> cmd.function(cmdState)
                            LineSemantics.CONSUME -> {
                                cmd.function(cmdState.copy(positionalOrLines = consumeLines()))
                            }
                            LineSemantics.PEEK -> {
                                cmd.function(cmdState.copy(positionalOrLines = peekLines()))
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
                    _atExit?.let {
                        logger.trace("run: triggering atexit")
                        it.invoke(NoncloseableOutputSendChannel(outputChannel))
                    }
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
        fun generateUsage(name: String, c: Command) =
            when (c.semantics) {
                LineSemantics.NONE -> "\t${quote(name)} <no usage message>"
                else -> "\t${quote(name)} <requires 0+ collected lines>"
            }

        when (pos.size) {
            0 -> {
                out.send("Commands:".asLine())
                for ((name, c) in commands) {
                    when {
                        c.usage.isNullOrEmpty() -> generateUsage(name, c)
                        else -> "\t${c.usage}"
                    }.let { out.send(it.asLine()) }
                }
                if (aliases.isNotEmpty()) {
                    out.send("Aliases:".asLine())
                    for ((name, c) in commands) {
                        if (c.aliases.isEmpty())
                            continue
                        out.send((
                            "\t${quote(name)}: " +
                                    c.aliases.sorted().joinToString(" ", transform = ::quote)
                        ).asLine())
                    }
                }
            }
            1 -> {
                val name = pos[0].lowercase()
                val cmd = commands[name]
                    ?: commands[aliases[name]]
                    ?: throw IllegalArgumentException("no match for command ${quote(name)}")
                cmd.help ?: throw IllegalArgumentException("no help message for command ${quote(name)}")
                when {
                    cmd.usage == null -> generateUsage(name, cmd)
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
        /**
         * @param inputStream input stream to launch a default line producer for
         * @param outputStream output stream to launch a default line consumer for
         * @param prompt prompt supplier; set to null to not print any prompt
         */
        suspend operator fun invoke(
            inputStream: InputStream = System.`in`,
            outputStream: OutputStream = System.out,
            prompt: PromptSupplier? = { "" }
        ) =
            invoke(
                lineProducer(inputStream.reader()),
                lineConsumer(outputStream.writer()),
                prompt
            )


        /**
         * @param inputProducer suspending line produce
         * @param outputConsumer suspending line consumer
         * @param prompt prompt supplier; set to null to not print any prompt
         */
        suspend operator fun invoke(
            inputProducer: InputProducer,
            outputConsumer: OutputConsumer,
            prompt: PromptSupplier? = { "" }
        ) =
            Repl(inputProducer, outputConsumer, prompt).apply {
                registerCommand(
                    "exit", "quit", "-q",
                    usage = "exit",
                    help = "Exits the interpreter"
                ) { _ -> throw Quit() }
                registerCommand(
                    "help", "-h", "?",
                    usage = "help [command]",
                    help = "Print the list of available commands, or help for specified command"
                ) { (pos, _, _, out) -> help(pos, out) }
                registerCommand(
                    "collect", "collect-lines", "c$", "<<",
                    usage = "<< delimiter",
                    help = "Collects lines until delimiter sequence, then saves the lines" +
                            " for the next command with line-consume semantics"
                ) { (pos, _, `in`, out) ->
                    val delim = pos.requireOne { "expected one delimiter" }
                    delimCollector(append = false, delim, `in`, out)
                }
                registerCommand(
                    "collect-more", "collect-more-lines", "c+$", "+<<",
                    usage = "+<< delimiter",
                    help = "Collects more lines until delimiter sequence is printer, then appends the lines" +
                            " for the next command with line-consume semantics"
                ) { (pos, _, `in`, out) ->
                    val delim = pos.requireOne { "expected one delimiter" }
                    delimCollector(append = true, delim, `in`, out)
                }
                registerCommand(
                    "collect-from-file", "c<", "<",
                    usage = "< filename",
                    help = "Collects lines from a file, then saves the lines" +
                            " for the next command with line-consume semantics"
                ) { (pos, _, _, out) ->
                    val fileName = pos.requireOne { "expected one filename" }
                    fileCollector(append = false, fileName, out)
                }
                registerCommand(
                    "collect-more-from-file", "c+<", "+<",
                    usage = "+< filename",
                    help = "Collects more lines from a file, then appends the lines" +
                            " for the next command with line-consume semantics"
                ) { (pos, _, _, out) ->
                    val fileName = pos.requireOne { "expected one filename" }
                    fileCollector(append = true, fileName, out)
                }
                registerCommand(
                    "peek", "peek-collection-buffer", "c>", ">",
                    usage = ">",
                    help = "Dumps contents of collection buffer without consuming it",
                    semantics = LineSemantics.PEEK
                ) { (lines, _, _, out) ->
                    lines.forEach { out.send(it.asLine()) }
                }
                registerCommand(
                    "clear-collection-buffer", "clear", "!-",
                    usage = "clear",
                    help = "Clears collection buffer",
                    semantics = LineSemantics.CONSUME
                ) { _ -> }
            }
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
