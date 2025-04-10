package org.cikit.oci

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.cikit.libjail.readJailParameters
import kotlin.system.exitProcess

class DeleteCommand : CliktCommand("delete") {

    override fun help(context: Context): String {
        return context.theme.info("Delete the container with the given id")
    }

    private val options by requireObject<GlobalOptions>()

    private val force by option().flag()
        .help("Delete even if running")

    private val containerId by argument("container-id",
        help = "Unique identifier for the container"
    )

    override fun run() {
        val interceptorState = options.readInterceptorState(containerId)
        interceptorState?.let { options.ociLogger.restoreState(it) }
        val state = options.readOciJailState(containerId) ?: execOciRuntime()
        val status = state["status"]?.jsonPrimitive?.content ?: "unknown"
        val canDelete = when (status) {
            "stopped", "created" -> true
            "running" -> force
            else -> false
        }
        if (!canDelete) {
            throw PrintMessage(
                "delete: container not in \"stopped\" or \"created\" " +
                        "state (currently $status)",
                1,
                printError = true
            )
        }
        runBlocking {
            val jails = readJailParameters()
            val jail = jails.singleOrNull { p ->
                p.name == containerId || p.jid == containerId.toIntOrNull()
            }
            if (jail != null) {
                val rc = try {
                    cleanup(options.ociLogger, jail)
                } catch (ex: Throwable) {
                    options.ociLogger.error(ex.toString(), ex)
                    1
                }
                if (rc != 0) {
                    throw PrintMessage(
                        "delete failed",
                        1,
                        printError = true
                    )
                }
            }
            options.deleteInterceptorState(containerId)
        }
        execOciRuntime()
    }

    private fun callOciRuntime() = runBlocking {
        callOciRuntime(
            options,
            buildList {
                add("delete")
                if (force) {
                    add("--force")
                }
                add(containerId)
            }
        )
    }

    private fun execOciRuntime(): Nothing {
        val rc = callOciRuntime()
        exitProcess(rc)
    }
}
