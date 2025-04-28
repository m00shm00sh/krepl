# Kotlin Async REPL

Asynchronous shell for interactive text programs not requiring features
like layout or autocomplete.

This provides a framework for
1. reading input
2. invoke an appropriate command
3. writing output

All the user has to do is register a command, with optional alias(es), usage message, help message, and/or semantics.

## Basic usage

```kotlin
suspend fun interactiveShell() {
    val r = Repl()
    r.registerCommand("hello", usage = "hello from=<who>") { st: State ->
        val who = requireNotNull(st.keywords["from"]) { "need name" }
        st.outputChannel.send("$who says hi")
    }
    launch { r.run() }.join()
}
```

## Command details
A command callback takes a `State` argument, which has members
- `positionalOrLines`, containing positional tokens or saved lines (see below),
- `keywords`, containing keyword tokens
- `inputChannel`, containing a non-cancellable receive channel for auxiliary lines,
- `outputChannel`, containing a non-closeable send channel for sending output lines


### Semantics
By default, a command has `semantics = Repl.LineSemantics.NONE`.
It can be registered with `semantics = Repl.LineSemantics.CONSUME` to consume a line-buffer populated by one or more
line-buffer built-ins.
It can be registered with `semantics = Repl.LineSemantics.PEEK` to inspect the line-buffer without consuming it.

If default `semantics` are used, `positionalOrLines` contains parameters not tokenized as keywords.
Otherwise, the `positionalOrLines` parameter is the lines buffer,
not the list of positional tokens passed during invocation.

## Tokens
The line
```
abc "d e" ="f=g h" i="j k" l\"
```
has
- command `"abc"`
- positionals `["d e", "f=g h", "l\""]`
- keywords `{"i":"j k"}`.

After tokenizing quoted tokens with backslash escape, the sequence of tokens is scanned
for tokens containing `'='`. If it exists not at the start of the token, the token is
extracted into a keyword. Otherwise, it is treated as a positional. The first token is always
the command.

## Exceptions
### Fatal exceptions
An exception type can be marked as fatal, meaning that if a child or channel operation throws it or
any of its subclasses,  the run loop will halt and the exception will be rethrown.

`repl.quitOnException<E>()` can be used to register such an exception `E`.

### Verbose exceptions
When `dumpStacktrace` is set to `true`, an exception thrown from a child or channel operation is sent to the output
channel if the channel hasn't been cancelled from the exception. (If it has, only the log will have any information.)

### Filtered exceptions
An exception type can be marked as filtered, meaning that, if specified, a stack trace is not sent to the output channel
if a child or channel throws it or any of its subclasses.

It is an error to have filtered exceptions if `dumpStacktrace` is `false`.

## Builtins
### exit, quit, -q
Quits the interpreter without a message.
### help, -h, \?
Prints the list of available commands, or help for specified command.

If a command is registered with a non-null usage, its value is printed in general help.
If null, the command is printed with a placeholder that depends on line semantics:
- when default, `<no usage message>`
- when `CONSUME` or `PEEK`, `<requires 0+ collected lines>`

If a command is registered with non-null help, its value is printed when `help <command>` is invoked.

In the list of available commands, aliases are printed in ascending order.

### <<, collect, collect-lines, c$
Collects until the delimiter specified as the sole positional argument and
sets the line buffer to the collected lines. Lines remain unchanged on failure.

### +<<, collect-more, collect-more-lines, c+$
Collects until the delimiter specified as the sole positional argument and
appends the collected contents to the line buffer.

### <, collect-from-file, c<
Collects all lines from file specified as the sole positional argument and
sets the line buffer to the collected lines. Lines remain unchanged on failure including
file not found.

### +<, collect-more-from-file, c+<
Collects all lines from file specified as the sole positional argument and
appends the collected contents to the line buffer.

### >, peek, peek-collection-buffer
Dumps contents of line buffer without consuming it.

### !-, clear, clear-collection-buffer
Clears line buffer.

## Instance parameters
A new instance of the interpreter can be constructed with optional:
1. input producer, which sends lines of input to a channel and
   closes the channel when done
2. output consumer, which consumes lines of input received from a channel
   and cancels on error
3. prompt supplier, which provides a status string (non-suspending)

Default input producer reads from stdin and output consumer writes to stdout.
Default prompt supplier provides an empty string. It can be set to null to not print any
prompt.

The input producer and output consumers are suspending coroutines where the receiver is the channel and
the parent context is the context of `run()` itself.
*Do not use coroutineScope to cancel the consume/produce.*
Either cancel the channel or cancel the parent coroutine.

An output consumer is given some special tokens to denote an unterminated line or a status 
check. It is expected to have the form: