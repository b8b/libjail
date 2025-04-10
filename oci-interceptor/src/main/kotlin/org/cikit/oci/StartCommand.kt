package org.cikit.oci

import com.github.ajalt.clikt.core.*
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
        val rc = try {
            callOciRuntime(options, "start", containerId)
        } catch (ex: Throwable) {
            options.ociLogger.error(ex.toString(), ex)
            1
        }
        exitProcess(rc)
    }
}
