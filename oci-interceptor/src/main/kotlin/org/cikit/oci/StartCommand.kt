package org.cikit.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument

abstract class StartCommand : CliktCommand("start"), OciCommand {

    override fun help(context: Context): String {
        return context.theme.info("Start the container with the given id")
    }

    override val containerId by argument(
        name = "container-id",
        help = "Unique identifier for the container"
    )

}
