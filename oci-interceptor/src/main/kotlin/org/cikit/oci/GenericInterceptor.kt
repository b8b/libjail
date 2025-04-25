package org.cikit.oci

import com.akuleshov7.ktoml.Toml
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.cikit.libjail.TraceEvent
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.error
import kotlin.io.path.*

open class GenericInterceptor(
    name: String = "intercept-oci-runtime",
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

    protected open val ociRuntimeBin by option(
        envvar = "INTERCEPT_OCI_RUNTIME_BIN"
    )
        .help("Path to oci runtime")
        .path(mustExist = true, canBeDir = false)
        .required()

    protected val ociRuntimeFlags by argument("OCI_RUNTIME_FLAGS").multiple()

    protected val overrideLog by option()
        .help("override log file location")
        .path(canBeDir = false)

    protected val overrideLogFormat by option()
        .help("override log format")

    protected val localStateDir by option()
        .help("Override default location for interceptor state database")
        .path(canBeFile = false)
        .default(Path("/var/run/oci-interceptor"))

    protected open val defaultConfigFile =
        "/usr/local/etc/containers/oci-interceptor.conf"

    protected val configFile by option(
        "--config",
        envvar = "INTERCEPT_OCI_CONFIG"
    ).path()
        .help("Path to interceptor config file.")

    protected val configTest by option(eager = true).flag().help(
        "Check the configuration file."
    )

    protected val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    override fun run() {
        if (configTest) {
            readInterceptorConfig() ?: throw PrintMessage(
                "no config file specified and '$defaultConfigFile' not present"
            )
            throw PrintMessage("${configFile ?: defaultConfigFile}: OK")
        }
        overrideLog?.let { s ->
            logger.overrideLogFile(s.pathString)
            logger.logFormat = if (s.endsWith(".json")) {
                "json"
            } else {
                "text"
            }
        }
        overrideLogFormat?.let { f ->
            logger.logFormat = f
        }
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
        logger.close()
        try {
            val rc = ProcessBuilder(allArgs).inheritIO().start().waitFor()
            if (rc != 0) {
                throw ProgramResult(rc)
            }
        } finally {
            logger.open()
        }
    }

    fun readInterceptorConfig(): InterceptorConfig? {
        return (configFile ?: Path(defaultConfigFile).takeIf { it.exists() })
            ?.readText()
            ?.let { Toml.decodeFromString<InterceptorConfig>(it) }
    }

    fun readHookConfig(): InterceptorConfig.Hooks {
        return readInterceptorConfig()?.hooks ?: InterceptorConfig.Hooks()
    }

    fun runHook(
        hook: InterceptorConfig.HookConfig,
        command: OciCommand
    ) {
        val args = hook.interpreter + listOf(
            hook.script,
            "pre$commandName"
        ) + rebuildOptions(command)
        logger.trace(TraceEvent.Exec(args))
        val pb = ProcessBuilder(args)
        pb.inheritIO()
        if (localStateDir.isDirectory()) {
            pb.directory(localStateDir.toFile())
        }
        pb.environment()["CID"] = command.containerId
        val p = pb.start()
        var timeout = false
        if (!p.waitFor(hook.timeout, TimeUnit.SECONDS)) {
            timeout = true
            p.destroyForcibly()
        }
        val rc = p.waitFor()
        if (timeout) {
            if (hook.preempt) {
                error("hook script killed after ${hook.timeout} second(s)")
            }
            logger.warn("hook script killed after ${hook.timeout} second(s)")
        }
        if (rc != 0) {
            if (hook.preempt) {
                error("hook script terminated with exit code $rc")
            }
            logger.warn("hook script terminated with exit code $rc")
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

    fun lockLocalStateDir(block: (Path) -> Unit) {
        val lockFile = localStateDir / ".lock"
        if (!localStateDir.isDirectory()) {
            localStateDir.createDirectories()
        }
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { fc ->
            fc.lock().use { lock ->
                if (!lock.isValid) {
                    logger.error("error locking $localStateDir")
                    error("error locking $localStateDir")
                }
                block(localStateDir)
            }
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
            val hookConfig = runtime.readHookConfig()
            if (hookConfig.precreate?.lock == false) {
                runtime.runHook(hookConfig.precreate, this)
            }
            runtime.lockLocalStateDir {
                if (hookConfig.precreate?.lock == true) {
                    runtime.runHook(hookConfig.precreate, this)
                }
                if (interceptorStateIn != interceptorStateOut) {
                    runtime.writeInterceptorState(
                        containerId,
                        interceptorStateOut
                    )
                }
            }
            runtime.callOciRuntime(this)
            if (hookConfig.postcreate != null) {
                runtime.runHook(hookConfig.postcreate, this)
            }
        }
    }

    private class Delete : DeleteCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            runtime.readInterceptorState(containerId)?.let {
                runtime.logger.restoreState(it)
            }
            val hookConfig = runtime.readHookConfig()
            when (hookConfig.predelete?.lock) {
                false -> runtime.runHook(hookConfig.predelete, this)
                true -> runtime.lockLocalStateDir {
                    runtime.runHook(hookConfig.predelete, this)
                }
                else -> {}
            }
            runtime.callOciRuntime(this)
            runtime.deleteInterceptorState(containerId)
            if (hookConfig.postdelete != null) {
                runtime.runHook(hookConfig.postdelete, this)
            }
        }
    }

    private class Exec : ExecCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            runtime.readInterceptorState(containerId)?.let {
                runtime.logger.restoreState(it)
            }
            val hookConfig = runtime.readHookConfig()
            when (hookConfig.preexec?.lock) {
                false -> runtime.runHook(hookConfig.preexec, this)
                true -> runtime.lockLocalStateDir {
                    runtime.runHook(hookConfig.preexec, this)
                }
                else -> {}
            }
            runtime.callOciRuntime(this)
            if (hookConfig.postexec != null) {
                runtime.runHook(hookConfig.postexec, this)
            }
        }
    }

    private class Kill : KillCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            runtime.readInterceptorState(containerId)?.let {
                runtime.logger.restoreState(it)
            }
            val hookConfig = runtime.readHookConfig()
            when (hookConfig.prekill?.lock) {
                false -> runtime.runHook(hookConfig.prekill, this)
                true -> runtime.lockLocalStateDir {
                    runtime.runHook(hookConfig.prekill, this)
                }
                else -> {}
            }
            runtime.callOciRuntime(this)
            if (hookConfig.postkill != null) {
                runtime.runHook(hookConfig.postkill, this)
            }
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
            val hookConfig = runtime.readHookConfig()
            when (hookConfig.prestart?.lock) {
                false -> runtime.runHook(hookConfig.prestart, this)
                true -> runtime.lockLocalStateDir {
                    runtime.runHook(hookConfig.prestart, this)
                }
                else -> {}
            }
            runtime.callOciRuntime(this)
            if (hookConfig.poststart != null) {
                runtime.runHook(hookConfig.poststart, this)
            }
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
