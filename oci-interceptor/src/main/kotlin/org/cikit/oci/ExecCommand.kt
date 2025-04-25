package org.cikit.oci

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

abstract class ExecCommand : CliktCommand("exec"), OciCommand {

    override fun help(context: Context): String {
        return context.theme.info(
            "Execute a command in the container with the given id"
        )
    }

    val process by option()
        .path()
        .help("Path to a file containing the process json")
        .required()

    val consoleSocket by option()
        .help("Path to a socket which will receive the console pty descriptor")
        .path()

    val pidFile by option()
        .help("Path to a file where the container process id will be written")
        .path()

    val tty by option("-t", "--tty")
        .help("Allocate a pty for the exec process")
        .flag()

    val detach by option("-d", "--detach")
        .help("Detach the command and execute in the background")
        .flag()

    val preserveFds by option()
        .help("Number of additional file descriptors for the container")
        .long()

    override val containerId by argument(
        name = "container-id",
        help = "Unique identifier for the container"
    )

}
