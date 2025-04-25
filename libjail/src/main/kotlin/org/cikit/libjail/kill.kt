package org.cikit.libjail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

suspend fun kill(jail: JailParameters, signal: String = "-9") {
    var requireKill = false
    val checkArgs = listOf("killall", "-q", "-j", jail.name, "-s", signal)
    pRead(checkArgs) { _, rc, _ ->
        requireKill = rc == 0
    }
    if (requireKill) {
        pRead(listOf("killall", "-q", "-j", jail.name, signal))
        var done = false
        while (!done) {
            delay(250L)
            withContext(Dispatchers.IO) {
                pRead(checkArgs) { _, rc, _ ->
                    done = rc != 0
                }
            }
        }
    }
}
