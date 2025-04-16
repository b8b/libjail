package org.cikit.oci.jail

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import org.cikit.oci.OciLogger

class JPkgCommand : CliktCommand("jpkg") {

    override val invokeWithoutSubcommand = true
    override val treatUnknownOptionsAsArgs = true

    override fun help(context: Context): String {
        return context.theme.info("Helper for pkg -j")
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
