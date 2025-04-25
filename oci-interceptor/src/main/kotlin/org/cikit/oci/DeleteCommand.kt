package org.cikit.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option

abstract class DeleteCommand : CliktCommand("delete"), OciCommand {

    override fun help(context: Context): String {
        return context.theme.info("Delete the container with the given id")
    }

    val force by option().flag()
        .help("Delete even if running")

    override val containerId by argument(
        name = "container-id",
        help = "Unique identifier for the container"
    )

}
