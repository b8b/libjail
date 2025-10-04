package org.cikit.oci.jail

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.path
import org.cikit.libjail.sysctlByNameInt32
import org.cikit.libjail.sysctlByNameString
import org.cikit.oci.OciLogger
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.pathString

private class CommandOptions : OptionGroup(
    name = "Command Options",
    help =
    """At the root level and for pkg sub commands, any options not handled
    by pkgbuild are passed to pkg. For more information consult the pkg manual 
    pages or use the help sub command.
    
    The following options are available on every sub command.
    """
) {
    val mount by option("--mount", metavar = "source:destination:options")
        .help(
            """Mount a directory into the jail before executing the 
            command. After command execution, the directory is unmounted
            unless when specified at the root level, in which case the
            directory remains mounted for all subsequent command executions.

            This option can be specified multiple times.
            """
        )
        .multiple()

    val commit by option()
        .help(
            """Commit a named image on successful completion of the 
            command. When specified at the root level, commit on successful 
            completion of all commands.

            This option can only be used together with the '--from' option.
            """
        )
}

class PkgbuildCommand : CliktCommand("pkgbuild") {

    override val invokeWithoutSubcommand = true
    override val treatUnknownOptionsAsArgs = true
    override val printHelpOnEmptyArgs = true

    init {
        subcommands(
            RunCommand(),
            BuildPackageCommand(),
            PkgCommand(),
            *pkgCommands.map { PkgCommand(it) }.toTypedArray()
        )
        context {
            allowInterspersedArgs = false
            helpFormatter = {
                MordantHelpFormatter(it, showDefaultValues = true)
            }
        }
    }

    override fun help(context: Context): String {
        return context.theme.info(
            """Create a temporary jail and perform minimal preparations 
            to allow subsequent pkg invocation(s) using the '--jail' option.

            Without any jail creation option ('--root', '--from'), run pkg
            directly passing all options unaltered. 
            """
        )
    }

    private val log by option()
        .help("Log file")

    private val logFormat by option()
        .help("Log format")

    private val logLevel by option()
        .help("Log level")
        .default("warn")

    private val interceptRcJail by option(
        envvar = "INTERCEPT_RC_JAIL",
        hidden = System.getenv("INTERCEPT_RC_JAIL") != null
    )
        .help("Path to intercept-rcjail command.")
        .required()

    private val pkgBin by option(envvar = "PKGBUILD_PKG_BIN")
        .help("Path to pkg binary.")
        .path(canBeDir = false)

    private val pkgCacheBase by option(envvar = "PKGBUILD_CACHE_BASE")
        .help("Base path to local cache.")
        .path(canBeFile = false)
        .default(
            Path("/var/cache/pkgbuild"),
            System.getenv("PKGBUILD_CACHE_BASE") ?: "/var/cache/pkgbuild"
        )

    private val src by mutuallyExclusiveOptions(
        option("--from").help(
            """Create and mount a container from the specified image using 
            'buildah'. Then create a jail using the container mount path."""
        ),
        option("--path").path(canBeFile = false).help(
            "Create a jail using an existing root directory."
        )
    ).required()

    private val jailAbi by option("--abi")
        .help("jail abi string")

    private val jailBase by option("--base")
        .help("jail base repository")

    private val commandOptions by CommandOptions()

    private val pipeline by option("-P", "--pipeline").flag().help(
        "Run multiple sub commands separated by the pipeline separator."
    )

    private val pipelineSeparator by option().default("--then").help(
        "Argument to be used as separator when running with '--pipeline'."
    )

    private val args by argument().multiple()

    private val logger by lazy {
        OciLogger(
            logFile = log,
            logFormat = logFormat,
            logLevel = logLevel
        )
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null && args.isEmpty()) {
            throw PrintHelpMessage(currentContext)
        }

        val osRelDate = sysctlByNameInt32("kern.osreldate")!!
        val arch = sysctlByNameString("hw.machine_arch")!!
        val major = osRelDate / 100_000
        val minor = (osRelDate / 1_000) % 100
        val patch = (osRelDate % 1000)

        val jailAbi = jailAbi ?: "FreeBSD:$major:$arch"
        val jailBase = jailBase ?: "base_release_$minor"

        val pkgSite = System.getenv("PKGBUILD_SITE") ?: pkgSiteDefault
        val pkgKeys = System.getenv("PKGBUILD_KEYS") ?: pkgKeysDefault

        val source = (src as? Path)
            ?.let { PkgbuildPipeline.Source.Root(it) }
            ?: PkgbuildPipeline.Source.From(src as String)

        val pipelineBuilder = PkgbuildPipeline(
            separator = pipelineSeparator.takeIf { pipeline },
            logger = logger,
            pkgBin = pkgBin,
            pkgCacheBase = pkgCacheBase,
            pkgSite = pkgSite,
            pkgKeys = Path(pkgKeys),
            hostMajorVersion = major,
            hostMinorVersion = minor,
            hostPatchVersion = patch,
            hostArch = arch,
            jailAbi = jailAbi,
            jailBasePkgDir = jailBase,
            interceptRcJail = interceptRcJail,
            pkgOptions = args,
            mount = commandOptions.mount,
            source = source,
            commit = commandOptions.commit
        )

        currentContext.findOrSetObject {
            pipelineBuilder
        }

        if (currentContext.invokedSubcommand == null) {
            pipelineBuilder.add(
                PkgbuildPipeline.Step.Pkg(
                    mount = emptyList(),
                    commit = null,
                    args = emptyList()
                )
            )
        }

        currentContext.callOnClose {
            val cleanupHook = thread(start = false) {
                pipelineBuilder.cleanup()
            }
            Runtime.getRuntime().addShutdownHook(cleanupHook)
            try {
                pipelineBuilder.run()
            } catch (ex: PrintMessage) {
                throw ex
            } catch (ex: Throwable) {
                throw PrintMessage(
                    ex.toString(),
                    statusCode = 1,
                    printError = true
                )
            } finally {
                pipelineBuilder.cleanup()
                Runtime.getRuntime().removeShutdownHook(cleanupHook)
            }
        }
    }

    companion object {

        private const val ENV_INTERCEPT_RC_JAIL = "INTERCEPT_RC_JAIL"

        @JvmStatic
        fun main(args: Array<String>) {
            val finalArgs = mutableListOf<String>()
            if (System.getenv(ENV_INTERCEPT_RC_JAIL) == null &&
                args.none {
                    it == "--intercept-rc-jail" ||
                            it.startsWith("--intercept-rc-jail=")
                })
            {
                val p = Path(System.getProperty("java.home")) /
                        "bin" /
                        "intercept-rcjail"
                finalArgs.add("--intercept-rc-jail=${p.pathString}")
            }
            if (finalArgs.isEmpty()) {
                PkgbuildCommand().main(args)
            } else {
                finalArgs.addAll(args)
                PkgbuildCommand().main(finalArgs)
            }
        }
    }
}

private class NextCommand(
    private val pipelineBuilder: PkgbuildPipeline
) : CliktCommand("next") {

    override val invokeWithoutSubcommand: Boolean = true

    init {
        subcommands(
            RunCommand(),
            BuildPackageCommand(),
            PkgCommand(),
            *pkgCommands.map { PkgCommand(it) }.toTypedArray()
        )
        context {
            allowInterspersedArgs = false
            helpFormatter = {
                MordantHelpFormatter(it, showDefaultValues = true)
            }
        }
    }

    private val then by option().flag()

    override fun run() {
        currentContext.findOrSetObject { pipelineBuilder }
        if (!then) {
            throw PrintHelpMessage(currentContext, error = true, 1)
        }
    }
}

private class RunCommand : CliktCommand("run") {

    init {
        context {
            allowInterspersedArgs = false
        }
    }

    override val treatUnknownOptionsAsArgs = true

    override fun help(context: Context): String {
        return context.theme.info(
            "Run a command in the jail environment."
        )
    }

    private val pipelineBuilder by requireObject<PkgbuildPipeline>()

    private val options by CommandOptions()

    private val noExpand by option().flag().help(
        "Do not expand template expressions (currently not implemented)" +
                " within the command arguments."
    )

    private val passEnv by option("-m", "--pass-env").flag().help(
        """Leave the environment unmodified. This will cause 'jexec' to be 
        called without the '-l' flag.
        """
    )

    private val shell by option("--shell", "-c").flag().help(
        "Execute `/bin/sh` passing the first argument as script. " +
                "Subsequent arguments are passed as script arguments."
    )

    private val args by argument("ARG").multiple().help(
        "Command and arguments to execute."
    )

    override fun run() {
        val runArgs = mutableListOf<String>()
        val nextArgs = mutableListOf<String>()
        for (arg in args) {
            if (nextArgs.isNotEmpty() || arg == pipelineBuilder.separator) {
                nextArgs.add(arg)
            } else {
                runArgs.add(arg)
            }
        }
        pipelineBuilder.add(
            PkgbuildPipeline.Step.Run(
                mount = options.mount,
                commit = options.commit,
                noExpand = noExpand,
                clean = !passEnv,
                shell = shell,
                args = runArgs.toList()
            )
        )
        if (nextArgs.isNotEmpty()) {
            NextCommand(pipelineBuilder).main(nextArgs.toList())
        }
    }
}

private class BuildPackageCommand : CliktCommand("build-package") {

    init {
        context {
            allowInterspersedArgs = false
        }
    }

    override val treatUnknownOptionsAsArgs = true

    override fun help(context: Context): String {
        return context.theme.info(
            "Build a package from ports in the jail environment."
        )
    }

    private val pipelineBuilder by requireObject<PkgbuildPipeline>()

    private val options by CommandOptions()

    private val portOptions by option("--port-options", "-O").help(
        "PORT_OPTIONS to activate in the build. (space separated)"
    ).default("")

    private val makeEnv by option("-e", "--make-env").multiple().help(
        "Define environment variables to set when running make."
    )

    private val args by argument("ARG").multiple(required = true).help(
        "Origin of the package to build within the ports tree, " +
                "plus optional make variables to pass. (variable=value)"
    )

    override fun run() {
        val runArgs = mutableListOf<String>()
        val nextArgs = mutableListOf<String>()
        for (arg in args) {
            if (nextArgs.isNotEmpty() || arg == pipelineBuilder.separator) {
                nextArgs.add(arg)
            } else {
                runArgs.add(arg)
            }
        }
        pipelineBuilder.add(
            PkgbuildPipeline.Step.BuildPackage(
                mount = options.mount,
                commit = options.commit,
                origin = runArgs.first(),
                makeEnv = makeEnv.associate {
                    it.substringBefore('=') to it.substringAfter('=', "")
                },
                makeVars = runArgs.drop(1).associate {
                    it.substringBefore('=') to it.substringAfter('=', "")
                },
                portOptions = portOptions.split(Regex("""\s+""")).toSet()
            )
        )
        if (nextArgs.isNotEmpty()) {
            NextCommand(pipelineBuilder).main(nextArgs.toList())
        }
    }
}

private val pkgCommands = setOf(
    "add",
    "alias",
    "annotate",
    "audit",
    "autoremove",
    "check",
    "clean",
    "config",
    "create",
    "delete",
    "fetch",
    "help",
    "info",
    "install",
    "key",
    "lock",
    "plugins",
    "query",
    "register",
    "remove",
    "repo",
    "repositories",
    "rquery",
    "search",
    "set",
    "ssh",
    "shell",
    "shlib",
    "stats",
    "triggers",
    "unlock",
    "update",
    "updating",
    "upgrade",
    "version",
    "which",
)

private class PkgCommand(name: String = "pkg") : CliktCommand(name) {

    init {
        context {
            allowInterspersedArgs = false
        }
    }

    override val treatUnknownOptionsAsArgs = true
    override val hiddenFromHelp: Boolean = commandName != "pkg"

    override fun help(context: Context): String {
        return context.theme.info(
            if (commandName == "pkg") {
                "Run a pkg command with global options. Any pkg sub command " +
                        "can also be used directly $pkgCommands."
            } else {
                "For more information see 'pkg help $commandName'."
            }
        )
    }

    private val pipelineBuilder by requireObject<PkgbuildPipeline>()

    private val options by CommandOptions()

    private val args by argument("PKG_ARG").multiple().help(
        "Options and arguments passed to pkg"
    )

    override fun run() {
        val pkgArgs = mutableListOf<String>()
        if (commandName != "pkg") {
            pkgArgs.add(commandName)
        }
        val nextArgs = mutableListOf<String>()
        for (arg in args) {
            if (nextArgs.isNotEmpty() || arg == pipelineBuilder.separator) {
                nextArgs.add(arg)
            } else {
                pkgArgs.add(arg)
            }
        }
        pipelineBuilder.add(
            PkgbuildPipeline.Step.Pkg(
                mount = options.mount,
                commit = options.commit,
                args = pkgArgs.toList()
            )
        )
        if (nextArgs.isNotEmpty()) {
            NextCommand(pipelineBuilder).main(nextArgs.toList())
        }
    }
}
