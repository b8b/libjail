package org.cikit.oci

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.vieiro.toml.TOMLParser
import org.cikit.forte.Forte
import org.cikit.forte.core.toNioPath
import org.cikit.forte.core.toUPath
import org.cikit.forte.eval.evalTemplate
import org.cikit.libjail.TraceEvent
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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

    private val ociRuntimeFlags by argument("OCI_RUNTIME_FLAGS").multiple()

    private val overrideLog by option()
        .help("override log file location")
        .path(canBeDir = false)

    private val overrideLogFormat by option()
        .help("override log format")

    private val overrideLogLevel by option()
        .help("override log level")

    private val localStateDir by option(envvar = "INTERCEPT_OCI_STATE_DIR")
        .help("Override default location for interceptor state database")
        .path(canBeFile = false)
        .default(Path("/var/run/oci-interceptor"))

    private val templatesDir by option(
        "-I", "--templates",
        envvar = "INTERCEPT_OCI_TEMPLATES_DIR"
    ).path(canBeFile = false)
        .help("Specify an additional location for hook templates")
        .multiple()

    protected open val defaultConfigFile =
        "/usr/local/etc/containers/oci-interceptor.conf"

    private val configFile by option(
        "--config",
        envvar = "INTERCEPT_OCI_CONFIG"
    ).path()
        .help("Path to interceptor config file.")

    private val configTest by option(eager = true).flag().help(
        "Check the configuration file."
    )

    open val config by lazy {
        val addTemplates = templatesDir.map { path -> path.toUPath() }
        val config = readInterceptorConfig()
        config?.copy(
            hooks = config.hooks.copy(
                templatesDir = config.hooks.templatesDir + addTemplates
            )
        ) ?: InterceptorConfig(hooks = Hooks(templatesDir = addTemplates))
    }

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
        overrideLog
            ?: config.interceptor.overrideLog
                ?.let { Path(it) }
                ?.let { s ->
                    logger.overrideLogFile(s.pathString)
                    logger.logFormat = if (s.endsWith(".json")) {
                        "json"
                    } else {
                        "text"
                    }
                }
        overrideLogFormat
            ?: config.interceptor.overrideLogFormat
                ?.let { f -> logger.logFormat = f }
        overrideLogLevel
            ?: config.interceptor.overrideLogLevel
                ?.let { level -> logger.logLevel = level }
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

    private fun parseInterceptorConfig(input: String): InterceptorConfig {
        val jsonInput = StringWriter().use { w ->
            TOMLParser.parseFromString(input).writeJSON(w)
            w.flush()
            w.toString()
        }
        return Json.decodeFromString(jsonInput)
    }

    fun readInterceptorConfig(): InterceptorConfig? {
        return (configFile ?: Path(defaultConfigFile).takeIf { it.exists() })
            ?.readText()
            ?.let { parseInterceptorConfig(it) }
    }

    private val forte = Forte {}

    private fun JsonElement.toAny(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> if (isString) {
            contentOrNull
        } else {
            booleanOrNull
                ?: intOrNull
                ?: longOrNull
                ?: doubleOrNull
                ?: contentOrNull
        }
        is JsonArray -> map { it.toAny() }
        is JsonObject -> entries.associate { (k, v) -> k to v.toAny() }
    }

    private fun MutableMap<String, Any?>.addVars(
        phase: Phase,
        command: OciCommand,
        vars: Map<String, JsonElement>,
    ) {
        put("phase", phase.name)
        put("command", command.commandName)
        put("containerId", command.containerId)
        val bundle = (vars["bundle"] as? JsonPrimitive)?.contentOrNull
        if (bundle != null) {
            put("bundle", bundle)
            if (!vars.containsKey("oci")) {
                val ociConfigFile = (Path(bundle) / "config.json")
                if (ociConfigFile.exists()) {
                    val ociConfig = Json.decodeFromString<JsonObject>(
                        ociConfigFile.readText()
                    )
                    put("oci", ociConfig.toAny())
                }
            }
        }
        for ((k, v) in vars) {
            if (!containsKey(k)) {
                put(k, v.toAny())
            }
        }
    }

    private fun runHook(
        hook: HookConfig,
        command: OciCommand,
        vars: MutableMap<String, Any?>
    ) {
        if (!hook.enabled) {
            return
        }
        vars["hook"] = mapOf(
            "timeout" to hook.timeout,
            "preempt" to hook.preempt,
        )
        val script = hook.template?.let { path ->
            val search = config.hooks.templatesDir
            val fullPath = if (path.isAbsolute) {
                path.toNioPath().takeIf { it.exists() }
            } else {
                search
                    .lastOrNull { d ->
                        d.appendSegments(path).toNioPath().exists()
                    }
                    ?.appendSegments(path)?.toNioPath()
            }
            if (fullPath == null) {
                logger.warn("failed to load template '$path': " +
                        "file not found in any template_dir $search")
                if (hook.preempt) {
                    throw java.nio.file.NoSuchFileException(path.pathString)
                } else {
                    return
                }
            }
            try {
                val templateSrc = fullPath.readText()
                val template = forte.parseTemplate(
                    templateSrc,
                    fullPath.toUPath()
                )
                runBlocking {
                    forte.captureToString()
                        .setVars(vars)
                        .evalTemplate(template)
                        .result
                }
            } catch (ex: Exception) {
                logger.warn("failed to render template '$fullPath': $ex", ex)
                if (hook.preempt) {
                    throw ex
                } else {
                    null
                }
            } ?: return
        }
        if (script?.isBlank() == true) {
            // template rendered to empty string
            return
        }
        val commandArgs = hook.command.map { arg ->
            try {
                val template = forte.parseTemplate(arg)
                runBlocking {
                    forte.captureToString()
                        .setVars(vars)
                        .evalTemplate(template)
                        .result
                }
            } catch (ex: Exception) {
                logger.warn("failed to render arg '$arg': $ex", ex)
                if (hook.preempt) {
                    throw ex
                } else {
                    return
                }
            }
        }
        val args = commandArgs + when (hook.appendArgs) {
            true -> rebuildOptions(command)
            false -> emptyList()
        }
        logger.trace(TraceEvent.Exec(args))
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        pb.directory(localStateDir.toFile())
        with (pb.environment()) {
            put("CID", command.containerId)
        }
        val p = pb.start()
        val output = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(hook.timeout * 1_000L) {
                launch {
                    p.outputStream.bufferedWriter().use { w ->
                        script?.let { w.appendLine(it) }
                    }
                }
                async {
                    p.inputStream.use { String(it.readBytes()) }
                }.await()
            }
        }
        if (output == null) {
            p.destroyForcibly()
        }
        val rc = p.waitFor()
        if (output == null) {
            if (hook.preempt) {
                error("hook script killed after ${hook.timeout} second(s)")
            }
            logger.warn("hook script killed after ${hook.timeout} second(s)")
            return
        }
        if (rc != 0) {
            logger.warn("hook script terminated with exit code $rc: $output")
            if (hook.preempt) {
                error("hook script terminated with exit code $rc: $output")
            }
            return
        }
        if (output.isNotBlank()) {
            logger.info("hook script terminated successfully: $output")
        }
    }

    fun runHook(
        phase: Phase,
        hook: HookConfig,
        command: OciCommand,
        vars: Map<String, JsonElement> = emptyMap()
    ) {
        if (!hook.enabled) {
            return
        }
        buildMap {
            addVars(phase, command, vars)
            runHook(hook, command, this)
        }
    }

    fun runHooks(
        phase: Phase,
        command: OciCommand,
        vars: Map<String, JsonElement>
    ) {
        buildMap {
            addVars(phase, command, vars)
            for (hook in phase.get(config.hooks)) {
                runHook(hook, command, this)
            }
        }
    }

    fun readContainerState(containerId: String): Map<String, JsonElement>? {
        val stateFile = localStateDir / "$containerId.json"
        if (stateFile.parent?.exists() != true) {
            return null
        }
        val stateJson = try {
            FileChannel.open(
                stateFile,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
        } catch (_: java.nio.file.NoSuchFileException) {
            return null
        }.use { fc ->
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

    fun writeContainerState(
        containerId: String,
        state: Map<String, JsonElement>,
    ) {
        val stateJson = json.encodeToString(state).encodeToByteArray()
        val stateFile = localStateDir / "$containerId.json"
        val stateFileTmp = localStateDir / "$containerId.json~"
        stateFile.parent?.let {
            if (!it.exists()) {
                it.createDirectories()
            }
        }
        FileChannel.open(
            stateFileTmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { fc ->
            fc.lock().use { lock ->
                require(lock.isValid) { "error locking $stateFileTmp" }
                fc.truncate(0L)
                fc.write(ByteBuffer.wrap(stateJson))
                fc.force(false)
                stateFileTmp.moveTo(stateFile, overwrite = true)
            }
        }
    }

    fun deleteContainerState(containerId: String) {
        val stateFile = localStateDir / "$containerId.json"
        stateFile.parent?.let {
            if (!it.exists()) {
                return
            }
        }
        val stateFileTmp = localStateDir / "$containerId.json~"
        FileChannel.open(
            stateFileTmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.DELETE_ON_CLOSE
        ).use { fc ->
            fc.lock().use { lock ->
                require(lock.isValid) { "error locking $stateFileTmp" }
                stateFile.deleteIfExists()
            }
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
            if (runtime.readContainerState(containerId) != null) {
                throw PrintMessage(
                    "container '$containerId' exists",
                    statusCode = 1,
                    printError = true
                )
            }
            val state = runtime.logger.saveState() + buildJsonObject {
                put("bundle", bundle.toAbsolutePath().pathString)
            }
            runtime.lockLocalStateDir {
                runtime.writeContainerState(containerId, state)
                try {
                    runtime.runHooks(Hooks::precreate, this, state)
                } catch (ex: Throwable) {
                    runtime.deleteContainerState(containerId)
                    throw ex
                }
            }
            runtime.callOciRuntime(this)
            runtime.runHooks(Hooks::postcreate, this, state)
        }
    }

    private class Delete : DeleteCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            val state = runtime.readContainerState(containerId)
            if (state != null) {
                runtime.logger.restoreState(JsonObject(state))
                runtime.runHooks(Hooks::predelete, this, state)
            }
            runtime.callOciRuntime(this)
            if (state != null) {
                runtime.runHooks(Hooks::postdelete, this, state)
                runtime.deleteContainerState(containerId)
            }
        }
    }

    private class Exec : ExecCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            val state = runtime.readContainerState(containerId)
            if (state != null) {
                runtime.logger.restoreState(JsonObject(state))
                runtime.runHooks(Hooks::preexec, this, state)
            }
            runtime.callOciRuntime(this)
            if (state != null) {
                runtime.runHooks(Hooks::postexec, this, state)
            }
        }
    }

    private class Kill : KillCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            val state = runtime.readContainerState(containerId)
            if (state != null) {
                runtime.logger.restoreState(JsonObject(state))
                runtime.runHooks(Hooks::prekill, this, state)
            }
            runtime.callOciRuntime(this)
            if (state != null) {
                runtime.runHooks(Hooks::postkill, this, state)
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
            val state = runtime.readContainerState(containerId)
            if (state != null) {
                runtime.logger.restoreState(JsonObject(state))
                runtime.runHooks(Hooks::prestart, this, state)
            }
            runtime.callOciRuntime(this)
            if (state != null) {
                runtime.runHooks(Hooks::poststart, this, state)
            }
        }
    }

    private class State : StateCommand() {
        val runtime: GenericInterceptor by requireObject()

        override fun run() {
            val state = runtime.readContainerState(containerId)
            if (state != null) {
                runtime.logger.restoreState(JsonObject(state))
            }
            runtime.callOciRuntime(this)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = GenericInterceptor().main(args)
    }
}
