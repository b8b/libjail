package org.cikit.oci.jail

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.coroutines.runBlocking
import org.cikit.libjail.cleanup
import org.cikit.libjail.readJailParameters
import org.cikit.oci.OciLogger
import kotlin.system.exitProcess

class RcJailInterceptor : CliktCommand("intercept-rcjail") {

    init {
        subcommands(CleanupCommand())
    }

    override fun help(context: Context): String {
        return context.theme.info("Helper for /etc/rc.d/jail")
    }

    private val log by option()
        .help("Log file")

    private val logFormat by option()
        .help("Log format")

    private val logLevel by option()
        .help("Log level")

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            throw PrintHelpMessage(
                currentContext,
                error = true,
                statusCode = 1
            )
        }
        currentContext.findOrSetObject {
            OciLogger(
                logFile = log,
                logFormat = logFormat,
                logLevel = logLevel
            )
        }
    }
}

class CleanupCommand : CliktCommand("cleanup") {

    override fun help(context: Context): String {
        return context.theme.info("Cleanup the jail with the given id")
    }

    private val logger by requireObject<OciLogger>()

    private val jail by option("-j",
        help = "Unique identifier for the jail"
    ).required()

    override fun run() {
        runBlocking {
            val jails = readJailParameters()
            val parameters = jails.singleOrNull { p ->
                p.name == jail || p.jid == jail.toIntOrNull()
            }
            parameters?.let { p ->
                logger.info("cleaning up ${p.name}")
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
