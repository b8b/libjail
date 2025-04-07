package org.cikit.libjail.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlin.system.exitProcess

class StartCommand : CliktCommand("start") {

    override fun help(context: Context): String {
        return context.theme.info("Start the container with the given id")
    }

    private val options by requireObject<GlobalOptions>()

    private val containerId by argument(
        "container-id",
        help = "Unique identifier for the container"
    )

    override fun run() {
        exitProcess(EXIT_UNHANDLED)
    }
}
