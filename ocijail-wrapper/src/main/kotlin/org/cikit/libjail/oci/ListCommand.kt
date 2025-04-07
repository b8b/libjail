package org.cikit.libjail.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import kotlin.system.exitProcess

class ListCommand : CliktCommand("list") {

    override fun help(context: Context): String {
        return context.theme.info("List containers")
    }

    private val options by requireObject<GlobalOptions>()

    private val quiet by option("-q", "--quiet")
        .help("show only IDs")
        .flag()

    private val format by option("-f", "--format")
        .help("output format: either table or json (default: table)")

    override fun run() {
        exitProcess(EXIT_UNHANDLED)
    }
}
