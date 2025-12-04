package org.cikit.oci

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import net.vieiro.toml.TOMLParser
import org.cikit.forte.Forte
import org.cikit.forte.core.UPath
import org.cikit.forte.core.toNioPath
import org.cikit.forte.core.toUPath
import org.cikit.forte.eval.evalTemplate
import org.cikit.libjail.TraceEvent
import org.cikit.oci.cni.*
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.system.exitProcess

class CniPluginCommand : CliktCommand("cni-plugin") {

    override val invokeWithoutSubcommand = true

    /**
     * CNI_COMMAND: indicates the desired operation;
     * ADD, DEL, CHECK, GC, or VERSION.
     */
    private val cniCommand by option(envvar = "CNI_COMMAND").required()

    /**
     * CNI_CONTAINERID: Container ID. A unique plaintext identifier for a
     * container, allocated by the runtime. Must not be empty. Must start with
     * an alphanumeric character, optionally followed by any combination of one
     * or more alphanumeric characters, underscore (), dot (.) or hyphen (-).
     */
    private val cniContainerId by option(envvar = "CNI_CONTAINERID").required()

    /**
     * CNI_NETNS: A reference to the container's "isolation domain". If using
     * network namespaces, then a path to the network namespace
     * (e.g. /run/netns/[nsname])
     */
    private val cniNetNs by option(envvar = "CNI_NETNS")

    /**
     * CNI_IFNAME: Name of the interface to create inside the container; if the
     * plugin is unable to use this interface name it must return an error.
     */
    private val cniIfName by option(envvar = "CNI_IFNAME")

    /**
     * CNI_ARGS: Extra arguments passed in by the user at invocation time.
     * Alphanumeric key-value pairs separated by semicolons;
     * for example, "FOO=BAR;ABC=123"
     */
    private val cniArgs by option(envvar = "CNI_ARGS")

    /**
     * CNI_PATH: List of paths to search for CNI plugin executables.
     * Paths are separated by an OS-specific list separator;
     * for example ':' on Linux and ';' on Windows
     */
    private val cniPath by option(envvar = "CNI_PATH")

    private val localStateDir by option(envvar = "INTERCEPT_OCI_STATE_DIR")
        .help("Override default location for interceptor state database")
        .path(canBeFile = false)
        .default(Path("/var/run/oci-interceptor"))

    private val templatesDir by option(
        "-I", "--templates",
        envvar = "CNI_TEMPLATES_DIR"
    ).path(canBeFile = false)
        .help("Specify an additional location for hook templates")
        .multiple()

    private val defaultConfigFile =
        "/usr/local/etc/containers/oci-interceptor.conf"

    private val configFile by option(
        "--config",
        envvar = "INTERCEPT_OCI_CONFIG"
    ).path()
        .help("Path to interceptor config file.")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val config by lazy {
        val addTemplates = templatesDir.map { path -> path.toUPath() }
        (configFile ?: Path(defaultConfigFile).takeIf { it.exists() })
            ?.readText()
            ?.let { configToml ->
                val configJson = StringWriter().use { w ->
                    TOMLParser.parseFromString(configToml).writeJSON(w)
                    w.flush()
                    w.toString()
                }
                val config =
                    json.decodeFromString<InterceptorConfig>(configJson)
                config.copy(
                    cni = config.cni.copy(
                        templatesDir = config.cni.templatesDir + addTemplates
                    )
                )
            }
            ?: InterceptorConfig(cni = CniConfig(templatesDir = addTemplates))
    }

    private val logger by lazy {
        OciLogger(
            logFile = config.interceptor.overrideLog,
            logFormat = config.interceptor.overrideLogFormat,
            logLevel = config.interceptor.overrideLogLevel,
            disableConsole = true
        )
    }

    private val forte by lazy {
        Forte {}
    }

    private val vars: Map<String, Any?> by lazy {
        mapOf(
            "command" to cniCommand.uppercase(),
            "containerId" to cniContainerId,
            "cniArgs" to parseArgs(),
            "env" to rebuildEnv(cniCommand.uppercase()),
            "logLevel" to logger.logLevel
        )
    }

    override fun run() {
        logger.trace(
            TraceEvent.Exec(
                "env",
                *rebuildEnv(cniCommand.uppercase()).entries
                    .map { (k, v) -> "$k=$v" }
                    .toTypedArray(),
                "cni-plugin"
            )
        )
        val result = try {
            when (cniCommand.uppercase()) {
                "VERSION" -> {
                    json.encodeToString(VersionResult())
                }
                "ADD" -> {
                    val result = runSetup(
                        config.cni.plugin,
                        CniPluginConfig.Setup
                    )
                    json.encodeToString(result)
                }
                "DEL" -> {
                    runPlugins(config.cni.plugin, CniPluginConfig.Delete)
                    null
                }
                "CHECK" -> {
                    runPlugins(config.cni.plugin, CniPluginConfig.Check)
                    null
                }
                "STATUS" -> {
                    TODO("not implemented")
                }
                "GC" -> {
                    TODO("not implemented")
                }
                else -> throw ErrorResultException(
                    "invalid command: $cniCommand",
                    ErrorCode.InvalidEnv,
                )
            }
        } catch (ex: ErrorResultException) {
            logger.error(ex.toString(), ex)
            println(json.encodeToString(ex.result))
            exitProcess(1)
        } catch (ex: Throwable) {
            logger.error(ex.toString(), ex)
            val st = StringWriter().use { w ->
                val pw = PrintWriter(w)
                ex.printStackTrace(pw)
                pw.flush()
                w.toString()
            }
            val result = ErrorResult(
                code = ErrorCode.TempError.code,
                msg = ex.message ?: ex.toString(),
                details = "$ex\n$st"
            )
            println(json.encodeToString(result))
            exitProcess(1)
        }
        if (result != null) {
            val compactResult = Json.encodeToString(
                Json.decodeFromString<JsonObject>(result)
            )
            logger.info("cni-plugin $cniCommand result: $compactResult")
            println(result)
        }
    }

    private fun runSetup(
        plugins: List<CniPluginConfig>,
        phase: CniPhase
    ): JsonObject {
        val ifName = cniIfName
        require(ifName != null) {
            "CNI_IFNAME environment variable not set"
        }
        val netConfig = readStdin()
        val netConfigMap = netConfig.mapValues { (_, v) -> v.toAny() }
        val version = readVersion(netConfig)
        val type = readType(netConfig)
        val enabledPlugins = plugins.filter { it.enabled && it.type == type }
        var addResult = readPrevResult(netConfig)
        var ifConfig = addResult?.let {
            json.decodeFromJsonElement<AddResult>(it).toIFConfig(netConfig)
        }
        if (!localStateDir.exists()) {
            localStateDir.createDirectories()
        }
        val setupResultFile =
            localStateDir / "${cniContainerId}-${cniIfName}.cni"

        val vars = vars.toMutableMap()
        val scriptEnv = rebuildEnv(phase.cniCommand)
        vars["command"] = phase.cniCommand
        vars["env"] = scriptEnv
        vars["cniConfig"] = netConfigMap
        vars["cniVersion"] = version
        vars["cniType"] = type
        vars["ifConfig"] = ifConfig?.let {
            json.encodeToJsonElement(it).toAny()
        }
        vars["setupResultFile"] = setupResultFile.name

        try {
            for (plugin in enabledPlugins) {
                val prepareScript = phase.prepare(plugin)?.let {
                    logger.info("rendering prepare script: $it")
                    renderTemplate(it, vars) ?: continue
                }
                val prepareCommand = phase.prepareCommand(plugin)
                    ?.let { renderCommand(it, vars) }
                    ?: prepareScript?.let { plugin.defaultCommand }
                if (prepareCommand != null) {
                    logger.info("running prepare command: $prepareCommand")
                    val setupResult = captureSetupResult(
                        setupResultFile,
                        ifConfig
                    ) {
                        runScript(
                            env = scriptEnv,
                            args = prepareCommand,
                            script = prepareScript,
                            workDir = localStateDir,
                            timeout = plugin.timeout
                        )
                    }
                    if (setupResult != null) {
                        logger.info(
                            "loaded result: ${Json.encodeToString(setupResult)}"
                        )
                        addResult = json.encodeToJsonElement(
                            setupResult.toAddResult(
                                version = version,
                                cniIfName = ifName,
                                cniContainerId = cniContainerId
                            )
                        ).jsonObject
                        ifConfig = json
                            .decodeFromJsonElement<AddResult>(addResult)
                            .toIFConfig(netConfig)
                        vars["cniConfig"] = netConfigMap +
                                ("prevResult" to addResult.toAny())
                        vars["ifConfig"] = json
                            .encodeToJsonElement(ifConfig)
                            .toAny()
                    }
                }
                val delegateCommand = when (plugin.delegate) {
                    CniPluginConfig.DelegationMode.NONE -> {
                        null
                    }

                    CniPluginConfig.DelegationMode.IPAM -> {
                        plugin.delegateCommand
                            ?.let { renderCommand(it, vars, true) }
                            ?: listOf(resolveIpamPlugin(netConfig))
                    }

                    CniPluginConfig.DelegationMode.CNI -> {
                        plugin.delegateCommand
                            ?.let { renderCommand(it, vars, true) }
                            ?: listOf(resolveCniPlugin(type))
                    }
                }
                if (delegateCommand != null) {
                    logger.info("running ${plugin.delegate}: $delegateCommand")
                    val output = runCniPlugin(
                        env = scriptEnv,
                        args = delegateCommand,
                        netConfig = netConfig,
                        workDir = localStateDir,
                        timeout = plugin.timeout,
                        runDelOnError = true
                    )
                    val delegateResult = json
                        .decodeFromString<JsonObject>(output)
                    logger.info(
                        "loaded result: ${Json.encodeToString(delegateResult)}"
                    )
                    val convertedResult = json
                        .decodeFromJsonElement<AddResult>(delegateResult)
                        .toIFConfig(netConfig)
                    if (plugin.delegate == CniPluginConfig.DelegationMode.CNI) {
                        // replace ifConfig with full result from CNI plugin
                        ifConfig = convertedResult
                        addResult = delegateResult
                    } else {
                        // merge in partial result from IPAM plugin
                        ifConfig = ifConfig?.merge(
                            json
                                .encodeToJsonElement(convertedResult)
                                .jsonObject
                        ) ?: convertedResult
                        addResult = json.encodeToJsonElement(
                            ifConfig.toAddResult(
                                version = version,
                                cniIfName = ifName,
                                cniContainerId = cniContainerId
                            )
                        ).jsonObject
                        // rebuild ifConfig from merged result
                        ifConfig = json
                            .decodeFromJsonElement<AddResult>(addResult)
                            .toIFConfig(netConfig)
                    }
                    vars["cniConfig"] = netConfigMap +
                            ("prevResult" to addResult.toAny())
                    vars["ifConfig"] = json
                        .encodeToJsonElement(ifConfig)
                        .toAny()
                }
                val setupScript = phase.main(plugin)?.let {
                    logger.info("rendering setup script: $it")
                    renderTemplate(it, vars) ?: continue
                }
                val setupCommand = phase.mainCommand(plugin)
                    ?.let { renderCommand(it, vars) }
                    ?: setupScript?.let { plugin.defaultCommand }
                if (setupCommand != null) {
                    logger.info("running setup script: $setupCommand")
                    val setupResult = captureSetupResult(
                        setupResultFile,
                        ifConfig
                    ) {
                        runScript(
                            env = scriptEnv,
                            args = setupCommand,
                            script = setupScript,
                            workDir = localStateDir,
                            timeout = plugin.timeout
                        )
                    }
                    if (setupResult != null) {
                        logger.info(
                            "loaded result: ${Json.encodeToString(setupResult)}"
                        )
                        addResult = json.encodeToJsonElement(
                            setupResult.toAddResult(
                                version = version,
                                cniIfName = ifName,
                                cniContainerId = cniContainerId
                            )
                        ).jsonObject
                        ifConfig = json
                            .decodeFromJsonElement<AddResult>(addResult)
                            .toIFConfig(netConfig)
                        vars["cniConfig"] = netConfigMap +
                                ("prevResult" to addResult.toAny())
                        vars["ifConfig"] = json
                            .encodeToJsonElement(ifConfig)
                            .toAny()
                    }
                }
            }
        } catch (ex: Exception) {
            val finalNetConfig = addResult?.let { finalResult ->
                buildJsonObject {
                    for ((k, v) in netConfig) {
                        put(k, v)
                    }
                    put("prevResult", finalResult)
                }
            }
            if (finalNetConfig != null) {
                try {
                    runPlugins(
                        config.cni.plugin,
                        CniPluginConfig.Delete,
                        finalNetConfig
                    )
                } catch (suppressed: Exception) {
                    ex.addSuppressed(suppressed)
                }
            }
            throw ex
        }

        return addResult ?: json.encodeToJsonElement(
                IFConfig().toAddResult(
                version = version,
                cniIfName = ifName,
                cniContainerId = cniContainerId
            )
        ).jsonObject
    }

    private fun captureSetupResult(
        setupResultFile: Path,
        ifConfig: IFConfig?,
        block: () -> Unit
    ): IFConfig? {
        val cleanup = thread(start = false) {
            setupResultFile.deleteIfExists()
        }
        Runtime.getRuntime().addShutdownHook(cleanup)
        val setupResult: IFConfig? = try {
            FileChannel.open(
                setupResultFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
            ).use { fc ->
                fc.lock().use { lock ->
                    require(lock.isValid) {
                        "error locking $setupResultFile"
                    }
                    ifConfig?.let {
                        val ifConfigEncoded = Json.encodeToString(ifConfig)
                        val outputStream = Channels.newOutputStream(fc)
                        outputStream.write(
                            ifConfigEncoded.encodeToByteArray()
                        )
                        outputStream.write("\n".encodeToByteArray())
                        outputStream.flush()
                    }
                    block()
                    fc.position(0L)
                    val inputStream = Channels.newInputStream(fc)
                    val lines = inputStream.bufferedReader().lineSequence()
                        .map { Json.decodeFromString<JsonObject>(it) }
                        .iterator()
                    if (lines.hasNext()) {
                        var finalResult: IFConfig = IFConfig
                            .decodeFromJsonElement(lines.next())
                        while (lines.hasNext()) {
                            finalResult = finalResult.merge(lines.next())
                        }
                        finalResult
                    } else {
                        null
                    }
                }
            }
        } finally {
            (cleanup as Runnable).run()
            Runtime.getRuntime().removeShutdownHook(cleanup)
        }
        return setupResult
    }

    private fun runPlugins(
        plugins: List<CniPluginConfig>,
        phase: CniPhase,
        netConfig: JsonObject = readStdin()
    ) {
        val version = readVersion(netConfig)
        val type = readType(netConfig)
        val enabledPlugins = plugins.filter { it.enabled && it.type == type }
        val prevResult = readPrevResult(netConfig)
        if (!localStateDir.exists()) {
            localStateDir.createDirectories()
        }

        val vars = vars.toMutableMap()
        val scriptEnv = rebuildEnv(phase.cniCommand)
        vars["command"] = phase.cniCommand
        vars["env"] = scriptEnv
        vars["cniConfig"] = netConfig.toAny()
        vars["cniVersion"] = version
        vars["cniType"] = type
        vars["ifConfig"] = prevResult?.let {
            val ipc = json.decodeFromJsonElement<AddResult>(it)
                .toIFConfig(netConfig)
            json.encodeToJsonElement(ipc).toAny()
        }

        for (plugin in enabledPlugins) {
            val prepareScript = phase.prepare(plugin)?.let {
                logger.info("rendering prepare $phase script: $it")
                renderTemplate(it, vars) ?: continue
            }
            val prepareCommand = phase.prepareCommand(plugin)
                ?.let { renderCommand(it, vars) }
                ?: prepareScript?.let { plugin.defaultCommand }
            if (prepareCommand != null) {
                logger.info("running prepare $phase script: $prepareCommand")
                runScript(
                    env = scriptEnv,
                    args = prepareCommand,
                    script = prepareScript,
                    workDir = localStateDir,
                    timeout = plugin.timeout
                )
            }
            val delegateCommand = when (plugin.delegate) {
                CniPluginConfig.DelegationMode.NONE -> {
                    null
                }
                CniPluginConfig.DelegationMode.IPAM -> {
                    plugin.delegateCommand
                        ?.let { renderCommand(it, vars, true) }
                        ?: listOf(resolveIpamPlugin(netConfig))
                }
                CniPluginConfig.DelegationMode.CNI -> {
                    //TODO implement mechanism against recursive reinvocation
                    plugin.delegateCommand
                        ?.let { renderCommand(it, vars, true) }
                        ?: listOf(resolveCniPlugin(type))
                    val args = plugin.delegateCommand
                        ?.let { renderCommand(it, vars) }
                    require(args != null && UPath(args.first()).isAbsolute) {
                        "recursive plugin prevention not implemented: must " +
                                "provide delegateCommand with absolute path"
                    }
                    args
                }
            }
            if (delegateCommand != null) {
                logger.info(
                    "running ${plugin.delegate} $phase: $delegateCommand"
                )
                runCniPlugin(
                    env = scriptEnv,
                    args = delegateCommand,
                    netConfig = netConfig,
                    workDir = localStateDir,
                    timeout = plugin.timeout
                )
            }
            val mainScript = phase.main(plugin)?.let {
                logger.info("rendering $phase script: $it")
                renderTemplate(it, vars) ?: continue
            }
            val mainCommand = phase.mainCommand(plugin)
                ?.let { renderCommand(it, vars) }
                ?: mainScript?.let { plugin.defaultCommand }
            if (mainCommand != null) {
                logger.info("running $phase script: $mainCommand")
                runScript(
                    env = scriptEnv,
                    args = mainCommand,
                    script = mainScript,
                    workDir = localStateDir,
                    timeout = plugin.timeout
                )
            }
        }
    }

    private fun renderTemplate(
        path: UPath,
        vars: Map<String, Any?>
    ): String? {
        val search = config.cni.templatesDir
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
            throw java.nio.file.NoSuchFileException(path.pathString)
        }
        val script = try {
            runBlocking {
                val templateSrc = fullPath.readText()
                val template = forte.parseTemplate(
                    templateSrc,
                    fullPath.toUPath()
                )
                forte.captureToString()
                    .setVars(vars)
                    .evalTemplate(template)
                    .result
            }
        } catch (ex: Exception) {
            logger.warn("failed to render '$fullPath': $ex", ex)
            throw ex
        }
        if (script.isBlank()) {
            return null
        }
        return script
    }

    private fun renderCommand(
        command: List<String>,
        vars: Map<String, Any?>,
        resolveCniPlugin: Boolean = false
    ) = buildList {
        for (arg in command) {
            var renderedArg = try {
                val template = forte.parseTemplate(arg)
                runBlocking {
                    forte.captureToString()
                        .setVars(vars)
                        .evalTemplate(template)
                        .result
                }
            } catch (ex: Exception) {
                logger.warn("failed to render arg '$arg': $ex", ex)
                throw ex
            }
            if (resolveCniPlugin && isEmpty()) {
                val path = UPath(arg)
                if (!path.isAbsolute && path.segments.count() == 1) {
                    renderedArg = resolveCniPluginOrNull(path.name)
                        ?.absolutePathString()
                        ?: renderedArg
                }
            }
            add(renderedArg)
        }
    }

    private fun resolveCniPluginOrNull(pluginType: String): Path? {
        //FIXME may not resolve to self
        val path = cniPath
            ?.split(File.pathSeparator)
            ?.firstNotNullOfOrNull { p ->
                val parent = Path(p)
                val file = parent / pluginType
                file.takeIf { it.parent == parent && it.exists() }
            }
        return path
    }

    private fun resolveCniPlugin(pluginType: String): String {
        val path = resolveCniPluginOrNull(pluginType)
        require(path != null) {
            "cni-plugin '$pluginType' not found in CNI_PATH '$cniPath'"
        }
        return path.absolutePathString()
    }

    private fun resolveIpamPlugin(netConfig: JsonObject): String {
        val ipamConfig = netConfig["ipam"]
        require(ipamConfig != null) {
            "cannot resolve ipam-plugin: ipam not configured"
        }
        val ipamType = (ipamConfig as? JsonObject)
            ?.get("type")
            ?.let { (it as? JsonPrimitive) }
            ?.contentOrNull
        require(ipamType != null) {
            "cannot resolve ipam-plugin: invalid ipam config"
        }
        return resolveCniPlugin(ipamType)
    }

    private fun rebuildEnv(cniCommand: String) = buildMap {
        put("CNI_COMMAND", cniCommand)
        put("CNI_CONTAINERID", cniContainerId)
        cniNetNs?.let { put("CNI_NETNS", it) }
        cniIfName?.let { put("CNI_IFNAME", it) }
        cniArgs?.let { put("CNI_ARGS", it) }
        cniPath?.let { put("CNI_PATH", it) }
    }

    private fun parseArgs(): Map<String, String> {
        val args = cniArgs ?: return emptyMap()
        return args.split(';').associate {
            it.substringBefore('=') to it.substringAfter('=', "")
        }
    }

    private fun readStdin(): JsonObject {
        val stdinData = System.`in`.bufferedReader().use { it.readText() }
        val stdinObj = try {
            json.decodeFromString<JsonObject>(stdinData)
        } catch (ex: IOException) {
            throw ErrorResultException(
                ex.message ?: ex.javaClass.simpleName,
                errorCode = ErrorCode.IOFailure,
                result = ErrorResult(
                    code = ErrorCode.IOFailure.code,
                    msg = ex.message ?: ex.javaClass.simpleName,
                    details = ex.toString()
                )
            )
        } catch (ex: SerializationException) {
            throw ErrorResultException(
                ex.message ?: ex.javaClass.simpleName,
                errorCode = ErrorCode.DecodeFailure,
                ErrorResult(
                    code = ErrorCode.DecodeFailure.code,
                    msg = ex.message ?: ex.javaClass.simpleName,
                    details = ex.toString()
                )
            )
        }
        logger.info(
            "loaded net config: " + Json.encodeToString(stdinObj)
        )
        return stdinObj
    }

    private fun readVersion(netConfig: JsonObject): String {
        val version = (netConfig["cniVersion"] as JsonPrimitive).content
        require(version in VersionResult().supportedVersions)
        return version
    }

    private fun readType(netConfig: JsonObject): String {
        return (netConfig["type"] as? JsonPrimitive)?.contentOrNull
            ?: throw ErrorResultException(
                "missing field 'type' in network config",
                errorCode = ErrorCode.InvalidNetworkConfig
            )
    }

    private fun readPrevResult(netConfig: JsonObject): JsonObject? {
        return (netConfig["prevResult"])?.let { it as JsonObject }
    }

    private fun runScript(
        env: Map<String, String>,
        args: List<String>,
        script: String?,
        workDir: Path,
        timeout: Long
    ) {
        logger.trace(TraceEvent.Exec(args))
        val out1 = StringWriter()
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        pb.directory(workDir.toFile())
        with (pb.environment()) {
            put("CID", cniContainerId)
            putAll(env)
        }
        val p = pb.start()
        val rc = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(timeout * 1_000L) {
                launch {
                    p.outputStream.bufferedWriter().use { w ->
                        script?.let { w.write(it) }
                    }
                }
                launch {
                    p.inputStream.bufferedReader().use { r ->
                        r.copyTo(out1)
                    }
                }
                p.waitFor()
            }
        }
        out1.flush()
        val output = out1.toString()
        if (rc == null) {
            logger.warn("hook script killed after $timeout second(s): $output")
            p.destroyForcibly()
            error("hook script killed after $timeout second(s)")
        }
        if (rc != 0) {
            logger.warn("hook script terminated with exit code $rc: $output")
            error("hook script terminated with exit code $rc: $output")
        }
        if (output.isNotBlank()) {
            logger.info("hook script terminated successfully: $output")
        } else {
            logger.info("hook script terminated successfully")
        }
    }

    private fun runCniPlugin(
        env: Map<String, String>,
        args: List<String>,
        netConfig: JsonObject,
        workDir: Path,
        timeout: Long,
        runDelOnError: Boolean = false
    ): String {
        val path = args.first()
        val netConfigJson = json.encodeToString(netConfig)
        logger.trace(TraceEvent.Exec(args))
        val out1 = StringWriter()
        val pb = ProcessBuilder(args)
        pb.inheritIO()
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
        pb.directory(workDir.toFile())
        pb.environment().putAll(env)
        val p = pb.start()
        val rc = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(timeout * 1_000L) {
                launch {
                    p.outputStream.bufferedWriter().use { w ->
                        w.write(netConfigJson)
                    }
                }
                launch {
                    p.inputStream.bufferedReader().use { r ->
                        r.copyTo(out1)
                    }
                }
                p.waitFor()
            }
        }
        out1.flush()
        val output = out1.toString()
        val ex = when(rc) {
            0 -> null
            null -> {
                p.destroyForcibly()
                IllegalStateException(
                    "$path killed after $timeout second(s): $output"
                )
            }
            else -> IllegalStateException(
                "$path terminated with exit code $rc: $output"
            )
        }
        if (ex != null) {
            if (runDelOnError) {
                try {
                    runCniDel(env, args, netConfigJson, workDir, timeout)
                } catch (ex2: Throwable) {
                    ex.addSuppressed(ex2)
                }
            }
            throw ex
        }
        logger.info("$path terminated successfully")
        return output
    }

    private fun runCniDel(
        env: Map<String, String>,
        args: List<String>,
        netConfigJson: String,
        workDir: Path,
        timeout: Long
    ) {
        val path = args.first()
        logger.trace(TraceEvent.Exec(args))
        val out1 = StringWriter()
        val pb = ProcessBuilder(args)
        pb.inheritIO()
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
        pb.directory(workDir.toFile())
        pb.environment().putAll(env)
        pb.environment()["CNI_COMMAND"] = "DEL"
        val p = pb.start()
        val rc = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(timeout * 1_000L) {
                launch {
                    p.outputStream.bufferedWriter().use { w ->
                        w.write(netConfigJson)
                    }
                }
                launch {
                    p.inputStream.bufferedReader().use { r ->
                        r.copyTo(out1)
                    }
                }
                p.waitFor()
            }
        }
        out1.flush()
        val output = out1.toString()
        if (rc == null) {
            logger.warn("$path killed after $timeout second(s): $output")
            p.destroyForcibly()
        }
        if (rc != 0) {
            logger.warn("$path terminated with exit code $rc: $output")
        } else {
            logger.info("$path terminated successfully")
        }
    }

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

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            CniPluginCommand().main(args)
        }
    }
}
