package org.cikit.oci

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.cikit.libjail.TraceEvent
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

open class GenericInterceptor(
    name: String = "oci-interceptor",
    create: CreateCommand = Create(),
    delete: DeleteCommand = Delete(),
    exec: ExecCommand = Exec(),
    kill: KillCommand = Kill(),
    list: ListCommand = List(),
    start: StartCommand = Start(),
    state: StateCommand = State()
) : OciRuntimeCommand(
    name = name,
    create = create,
    delete = delete,
    exec = exec,
    kill = kill,
    list = list,
    start = start,
    state = state,
) {
    override val treatUnknownOptionsAsArgs = true

    override fun help(context: Context): String {
        return context.theme.info("intercept oci runtime invocation")
    }

    protected open val ociRuntimeBin by option()
        .help("Path to oci runtime")
        .path(mustExist = true, canBeDir = false)
        .required()

    protected val ociRuntimeFlags by argument("OCI_RUNTIME_FLAGS").multiple()

    protected val localStateDir by option()
        .help("Override default location for interceptor state database")
        .path(canBeFile = false)
        .default(Path("/var/run/oci-interceptor"))

    protected val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    override fun run() {
        super.run()
        if (currentContext.invokedSubcommand == null) {
            throw PrintHelpMessage(
                currentContext,
                error = true,
                statusCode = 1
            )
        }
        currentContext.findOrSetObject { this }
    }

    override fun printVersion() {
        val args = listOf("--version")
        val allArgs = listOf(ociRuntimeBin.pathString) +
                ociRuntimeFlags +
                args
        logger.trace(TraceEvent.Exec(allArgs))
        val rc = ProcessBuilder(allArgs).inheritIO().start().waitFor()
        currentContext.exitProcess(rc)
    }

    open fun rebuildGlobalOptions() = buildList {
        add(ociRuntimeBin.pathString)
        addAll(ociRuntimeFlags)
    }

    open fun rebuildOptions(command: ListCommand) = buildList {
        add(command.commandName)
        with (command) {
            if (quiet) {
                add("--quiet")
            }
            format?.let {
                add("--format=$it")
            }
        }
    }

    open fun rebuildOptions(command: OciCommand) = buildList {
        add(command.commandName)
        when (command) {
            is CreateCommand -> with (command) {
                add("--bundle=$bundle")
                consoleSocket?.let {
                    add("--console-socket=$it")
                }
                pidFile?.let {
                    add("--pid-file=$it")
                }
                preserveFds?.let {
                    add("--preserve-fds=$it")
                }
                add(containerId)
            }
            is DeleteCommand -> with (command) {
                if (force) {
                    add("--force")
                }
                add(containerId)
            }
            is ExecCommand -> with (command) {
                add("--process=$process")
                consoleSocket?.let {
                    add("--console-socket=$it")
                }
                pidFile?.let {
                    add("--pid-file=$it")
                }
                if (tty) {
                    add("--tty")
                }
                if (detach) {
                    add("--detach")
                }
                add(containerId)
                preserveFds?.let {
                    add("--preserve-fds=$it")
                }
            }
            is KillCommand -> with (command) {
                if (all) {
                    add("--all")
                }
                pid?.let {
                    add("--pid=$it")
                }
                add(containerId)
                signal?.let { add(it.toString()) }
            }
            is StartCommand -> with (command) {
                add(containerId)
            }
            is StateCommand -> with (command) {
                add(containerId)
            }
            else -> {
                error("unrecognized command: $command")
            }
        }
    }

    fun callOciRuntime(command: ListCommand) {
        callOciRuntime(rebuildOptions(command))
    }

    fun callOciRuntime(command: OciCommand) {
        callOciRuntime(rebuildOptions(command))
    }

    fun callOciRuntime(args: Iterable<String>) {
        val allArgs = rebuildGlobalOptions() + args
        logger.trace(TraceEvent.Exec(allArgs))
        val rc = ProcessBuilder(allArgs).inheritIO().start().waitFor()
        if (rc != 0) {
            throw ProgramResult(rc)
        }
    }

    fun readInterceptorState(containerId: String): JsonObject? {
        val stateFile = localStateDir / "$containerId.json"
        if (stateFile.parent?.exists() != true) {
            return null
        }
        val stateJson = FileChannel.open(
            stateFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { fc ->
            fc.lock().use { lock ->
                require(lock.isValid) { "error locking $stateFile" }
                if (stateFile.isReadable()) {
                    stateFile.readText()
                } else {
                    return null
                }
            }
        }
        return if (stateJson.isBlank()) {
            null
        } else {
            json.decodeFromString(stateJson)
        }
    }

    fun writeInterceptorState(containerId: String, state: JsonObject) {
        val stateJson = json.encodeToString(state)
        val stateFile = localStateDir / "$containerId.json"
        stateFile.parent?.let {
            if (!it.exists()) {
                it.createDirectories()
            }
        }
        FileChannel.open(
            stateFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { fc ->
            fc.lock().use { lock ->
                require(lock.isValid) { "error locking $stateFile" }
                fc.truncate(0L)
                fc.write(ByteBuffer.wrap(stateJson.encodeToByteArray()))
            }
        }
    }

    fun deleteInterceptorState(containerId: String) {
        val stateFile = localStateDir / "$containerId.json"
        if (stateFile.exists()) {
            stateFile.deleteIfExists()
        }
    }

    private class Create : CreateCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            val interceptorStateIn = runtime.readInterceptorState(containerId)
            val interceptorStateOut = buildJsonObject {
                if (interceptorStateIn != null) {
                    for ((k, v) in interceptorStateIn.entries) {
                        put(k, v)
                    }
                }
                for ((k, v) in runtime.logger.saveState()) {
                    put(k, v)
                }
            }
            runtime.callOciRuntime(this)
            if (interceptorStateIn != interceptorStateOut) {
                runtime.writeInterceptorState(containerId, interceptorStateOut)
            }
        }
    }

    private class Delete : DeleteCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            runtime.readInterceptorState(containerId)?.let {
                runtime.logger.restoreState(it)
            }
            runtime.callOciRuntime(this)
            runtime.deleteInterceptorState(containerId)
        }
    }

    private class Exec : ExecCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            runtime.readInterceptorState(containerId)?.let {
                runtime.logger.restoreState(it)
            }
            runtime.callOciRuntime(this)
        }
    }

    private class Kill : KillCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            runtime.readInterceptorState(containerId)?.let {
                runtime.logger.restoreState(it)
            }
            runtime.callOciRuntime(this)
        }
    }

    private class List : ListCommand() {
        val runtime: GenericInterceptor by requireObject()
        override fun run() = runtime.callOciRuntime(this)
    }

    private class Start : StartCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            runtime.readInterceptorState(containerId)?.let {
                runtime.logger.restoreState(it)
            }
            runtime.callOciRuntime(this)
        }
    }

    private class State : StateCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            runtime.readInterceptorState(containerId)?.let {
                runtime.logger.restoreState(it)
            }
            runtime.callOciRuntime(this)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = GenericInterceptor().main(args)
    }

}
