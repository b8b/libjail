package org.cikit.libjail.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import kotlin.system.exitProcess

class ExecCommand : CliktCommand("exec") {

    override fun help(context: Context): String {
        return context.theme.info("Execute a command in the container with the given id")
    }

    private val options by requireObject<GlobalOptions>()

    private val process by option()
        .path()
        .help("Path to a file containing the process json")
        .required()

    private val consoleSocket by option()
        .help("Path to a socket which will receive the console pty descriptor")
        .path()

    private val pidFile by option()
        .help("Path to a file where the container process id will be written")
        .path()

    private val tty by option("-t", "--tty")
        .help("Allocate a pty for the exec process")
        .flag()

    private val detach by option("-d", "--detach")
        .help("Detach the command and execute in the background")
        .flag()

    private val containerId by argument("container-id",
        help = "Unique identifier for the container"
    )

    private val preserveFds by option()
        .help("Number of additional file descriptors for the container")
        .long()

    override fun run() {
        exitProcess(EXIT_UNHANDLED)
    }
}
