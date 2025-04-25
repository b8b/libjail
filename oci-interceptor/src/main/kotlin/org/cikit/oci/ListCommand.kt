package org.cikit.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option

abstract class ListCommand : CliktCommand("list") {

    override fun help(context: Context): String {
        return context.theme.info("List containers")
    }

    val quiet by option("-q", "--quiet")
        .help("show only IDs")
        .flag()

    val format by option("-f", "--format")
        .help("output format: either table or json (default: table)")

}
