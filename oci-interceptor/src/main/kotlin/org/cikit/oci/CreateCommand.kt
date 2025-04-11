package org.cikit.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path

abstract class CreateCommand : CliktCommand("create"), OciCommand {

    override fun help(context: Context): String {
        return context.theme.info(
            "Create a jail instance for the container described by " +
                    "the given bundle directory."
        )
    }

    val bundle by option("-b", "--bundle")
        .help("Path to the OCI runtime bundle directory")
        .path(mustExist = true, canBeDir = true, canBeFile = false)
        .required()

    val consoleSocket by option()
        .help("Path to a socket which will receive the console pty descriptor")
        .path()

    val pidFile by option()
        .help("Path to a file where the container process id will be written")
        .path()

    val preserveFds by option()
        .help("Number of additional file descriptors for the container")
        .long()

    override val containerId by argument(
        name = "container-id",
        help = "Unique identifier for the container"
    )

}
