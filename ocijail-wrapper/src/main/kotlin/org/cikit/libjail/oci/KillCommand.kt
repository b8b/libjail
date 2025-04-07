package org.cikit.libjail.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import kotlin.system.exitProcess

class KillCommand : CliktCommand("kill") {

    override fun help(context: Context): String {
        return context.theme.info("Send a signal to a container")
    }

    private val options by requireObject<GlobalOptions>()

    private val containerId by argument("container-id",
        help = "Unique identifier for the container"
    )

    private val signal by argument("signal",
        help = "Signal to send, defaults to TERM"
    )
        .long()
        .optional()

    private val all by option("-a", "--all")
        .help("Send the signal to all processes in the container")
        .flag()

    private val pid by option("-p", "--pid")
        .help("Send the signal to the given process")
        .long()

    override fun run() {
        exitProcess(EXIT_UNHANDLED)
    }
}
