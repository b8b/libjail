package org.cikit.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option

open class OciRuntimeCommand(
    name: String,
    protected val create: CreateCommand = Create(),
    protected val start: StartCommand = Start(),
    protected val delete: DeleteCommand = Delete(),
    protected val exec: ExecCommand = Exec(),
    protected val kill: KillCommand = Kill(),
    protected val state: StateCommand = State(),
    protected val list: ListCommand = List(),
) : CliktCommand(name) {

    init {
        subcommands(create, start, delete, exec, kill, state, list)
    }

    override val invokeWithoutSubcommand = true

    private val log by option()
        .help("Log file")

    private val logFormat by option()
        .help("Log format")

    private val logLevel by option()
        .help("Log level")

    open val version by option()
        .help("Print runtime version")
        .flag()

    val logger by lazy {
        OciLogger(
            logFile = log,
            logFormat = logFormat,
            logLevel = logLevel
        )
    }

    override fun run() {
        if (version) {
            printVersion()
        }
    }

    open fun printVersion() {
        TODO("Not yet implemented")
    }

    private class Create : CreateCommand() {
        override fun run() {
            TODO("Not yet implemented")
        }
    }

    private class Delete : DeleteCommand() {
        override fun run() {
            TODO("Not yet implemented")
        }
    }

    private class Exec : ExecCommand() {
        override fun run() {
            TODO("Not yet implemented")
        }
    }

    private class Kill : KillCommand() {
        override fun run() {
            TODO("Not yet implemented")
        }
    }

    private class List : ListCommand() {
        override fun run() {
            TODO("Not yet implemented")
        }
    }

    private class Start : StartCommand() {
        override fun run() {
            TODO("Not yet implemented")
        }
    }

    private class State : StateCommand() {
        override fun run() {
            TODO("Not yet implemented")
        }
    }
}
