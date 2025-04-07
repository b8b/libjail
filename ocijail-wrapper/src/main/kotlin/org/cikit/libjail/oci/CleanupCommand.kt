package org.cikit.libjail.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import org.cikit.libjail.readJailParameters
import org.cikit.libjail.setLogLevel
import kotlin.system.exitProcess

class CleanupCommand : CliktCommand("cleanup") {

    override fun help(context: Context): String {
        return context.theme.info("Cleanup the jail with the given id")
    }

    private val jail by option("-j",
        help = "Unique identifier for the jail"
    ).required()

    private val logLevel by option().int()

    override fun run() {
        setLogLevel(logLevel ?: 0)
        runBlocking {
            val jails = readJailParameters()
            val parameters = jails.singleOrNull { p ->
                p.name == jail || p.jid == jail.toIntOrNull()
            }
            parameters?.let { p ->
                try {
                    val rc = cleanup(p)
                    exitProcess(rc)
                } catch (ex: Throwable) {
                    throw PrintMessage(
                        "cleanup failed: ${ex.message}",
                        1,
                        printError = true
                    )
                }
            } ?: throw PrintMessage(
                "jail \"$jail\" not found",
                1,
                printError = true
            )
        }
    }
}
