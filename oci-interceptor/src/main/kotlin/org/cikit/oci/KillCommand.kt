package org.cikit.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long

abstract class KillCommand : CliktCommand("kill"), OciCommand {

    override fun help(context: Context): String {
        return context.theme.info("Send a signal to a container")
    }

    val all by option("-a", "--all")
        .help("Send the signal to all processes in the container")
        .flag()

    val pid by option("-p", "--pid")
        .help("Send the signal to the given process")
        .long()

    override val containerId by argument(
        name = "container-id",
        help = "Unique identifier for the container"
    )

    val signal by argument(
        name = "signal",
        help = "Signal to send, defaults to TERM"
    )
        .long()
        .optional()

}
