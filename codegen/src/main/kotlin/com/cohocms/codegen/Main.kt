package com.cohocms.codegen

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import kotlin.system.exitProcess

/**
 * `coho-codegen`: point a content library at a Coho project, and generate its code. A library
 * made from the template runs it through Gradle: `./gradlew cohoInit`, `cohoPull`,
 * `cohoGenerate` and `cohoCheck`.
 */
object Main
{
    const val USAGE = """Usage: coho-codegen <command> [--config coho.config.json]

Commands:
  init      Point this library at a Coho project: asks Coho which account and project the
            project token in COHO_TOKEN, or .env, is for, and writes them to the config.
            --url <url>, --ref <tag>, --package <name> to rename the library's package.
  pull      Fetch the type model for the configured ref into the type model file.
            Needs COHO_TOKEN, from the environment or a .env beside the config.
            --ref <ref> pulls another tag or branch than the config names.
  generate  Write Java or Kotlin for the type model into the output directory.
  check     Fail if the output directory differs from what generate would write."""

    @JvmStatic
    fun main(args: Array<String>)
    {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        exitProcess(run(args.toList(), Context(File("").absoluteFile, System::getenv, System.out, System.err, http)))
    }

    /** Where a command runs: its directory, its environment, its output, and how it reaches Coho. */
    class Context(
        val directory: File,
        val environment: (String) -> String?,
        val out: PrintStream,
        val err: PrintStream,
        val http: HttpClient,
    )
    {
        fun shown(file: File): String = file.absoluteFile.relativeToOrNull(directory)?.path?.ifEmpty { "." } ?: file.path
    }

    fun run(args: List<String>, context: Context): Int =
        try
        {
            command(args, context)
        }
        catch (error: CodegenException)
        {
            // Something to fix, not a bug: the message says what.
            context.err.println("error: ${error.message}")
            1
        }

    private fun command(args: List<String>, context: Context): Int
    {
        val options = Options.parse(args)
        if (options.help)
        {
            context.out.println(USAGE)
            return 0
        }
        val configFile = File(context.directory, options.config).absoluteFile
        when (options.command)
        {
            null ->
            {
                context.err.println(USAGE)
                return 1
            }
            "init" -> return init(options, configFile, context)
            "pull" ->
            {
                val loaded = Loaded.from(configFile)
                val token = token(context, loaded.directory) ?: throw CodegenException("COHO_TOKEN is not set: pull needs a project token with read access")
                val config = options.ref?.let { loaded.config.withRef(it) } ?: loaded.config
                val (changed, model) = Files.pull(config, loaded.typeModel, token, context.http)
                val shown = context.shown(loaded.typeModel)
                context.out.println(if (changed) "Updated $shown from ${model.source.ref}." else "$shown is up to date.")
                return 0
            }
            "generate" ->
            {
                val loaded = Loaded.from(configFile)
                val generated = generate(Files.readTypeModel(loaded.typeModel), loaded.generatedPackage, loaded.language)
                Files.write(generated.files, loaded.outDir)
                generated.warnings.forEach { context.err.println("warning: $it") }
                val count = generated.files.size
                context.out.println("Wrote $count ${if (count == 1) "file" else "files"} to ${context.shown(loaded.outDir)}.")
                return 0
            }
            "check" ->
            {
                val loaded = Loaded.from(configFile)
                val problems = Files.drift(generate(Files.readTypeModel(loaded.typeModel), loaded.generatedPackage, loaded.language).files, loaded.outDir)
                if (problems.isEmpty())
                {
                    context.out.println("Generated code is up to date.")
                    return 0
                }
                problems.forEach { context.err.println("${context.shown(loaded.outDir)}: $it") }
                context.err.println("Generated code is out of date: run ./gradlew cohoGenerate.")
                return 1
            }
            else ->
            {
                context.err.println("Unknown command '${options.command}'.\n\n$USAGE")
                return 1
            }
        }
    }

    /**
     * Checks the token with Coho before anything is written: a token Coho refuses leaves the
     * library as it was. A new `--package` renames the library's own package, wherever it appears.
     */
    private fun init(options: Options, configFile: File, context: Context): Int
    {
        val existing = if (configFile.exists()) Loaded.from(configFile).config else null
        val url = options.url ?: existing?.url
        val token = token(context, configFile.absoluteFile.parentFile)
        if (token != null && url == null)
        {
            throw CodegenException("A token needs the Coho URL to ask: pass --url")
        }
        val described = if (token != null && url != null) describe(url, token, context.http) else null

        val renamed = options.packageName?.let()
        { name ->
            if (!Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+""").matches(name))
            {
                throw CodegenException("'$name' is not a package name: lower case, with dots, such as com.acme.content")
            }
            val old = existing?.packageName
            if (old != null && old != name)
            {
                Rename.packageOf(configFile.absoluteFile.parentFile, old, name)
            }
            name
        }

        Loaded.edit(
            configFile,
            mapOf(
                "url" to url,
                "account" to described?.account,
                "project" to described?.project,
                "ref" to (options.ref ?: existing?.ref),
                "package" to renamed,
            ),
        )

        context.out.println("Updated ${configFile.name}.")
        renamed?.let { context.out.println("The library's package is $it.") }
        if (described != null)
        {
            context.out.println("It reads ${described.projectName}, in ${described.accountName}, with the token in .env.")
            // The library only reads types; a token that can do more is more to lose if it leaks.
            if (described.role != "viewer")
            {
                context.out.println("That token is a ${described.role}. The library only reads, so a viewer token is enough: coho token create --role viewer")
            }
        }
        val config = Loaded.from(configFile).config
        val unset = listOf("url" to config.url, "account" to config.account, "project" to config.project, "ref" to config.ref).filter { it.second == null }.map { it.first }
        context.out.println("\nNext:")
        if (unset.isNotEmpty())
        {
            context.out.println("  set ${unset.joinToString(", ")} in ${configFile.name}")
        }
        if (token == null)
        {
            context.out.println("  cp .env.example .env, set COHO_TOKEN to a project token with read access, and run this again")
        }
        context.out.println("  ./gradlew cohoPull cohoGenerate")
        return 0
    }

    private class Described(val account: String, val accountName: String, val project: String, val projectName: String, val role: String)

    /**
     * Asks Coho at [url] which account and project [token] is for. A token names its own
     * project, so nothing else is needed to find them: no account or project ids to copy.
     */
    private fun describe(url: String, token: String, http: HttpClient): Described
    {
        if (!url.startsWith("http://") && !url.startsWith("https://"))
        {
            throw CodegenException("'$url' is not a URL, such as https://app.coho.example")
        }
        if (!token.startsWith("coho_pt_"))
        {
            throw CodegenException("A project token starts coho_pt_. Create one with: coho token create --role viewer")
        }
        val request = HttpRequest.newBuilder(URI.create("${url.trimEnd('/')}/api/v1/token"))
            .header("accept", "application/json")
            .header("authorization", "Bearer $token")
            .build()
        val response = Files.send(http, request, url)
        if (response.statusCode() in 200..299)
        {
            val body = runCatching { ObjectMapper().readTree(response.body()) }.getOrNull()
            val account = body?.path("account")
            val project = body?.path("project")
            if (account?.get("id") == null || project?.get("id") == null)
            {
                throw CodegenException("Coho's description of the token is not what this tool reads")
            }
            return Described(
                account.path("id").asText(),
                account.path("name").asText(account.path("id").asText()),
                project.path("id").asText(),
                project.path("name").asText(project.path("id").asText()),
                body.path("token").path("role").asText(),
            )
        }
        val hint = when (response.statusCode())
        {
            401 -> " Check that it is a current project token, not revoked or expired."
            403 -> " The account is suspended or closed."
            404 -> " Check the URL: it is Coho's, as `coho status` shows it, not delivery's."
            else -> ""
        }
        val said = Files.failure(response, "Asking Coho about the token").replace(" Check COHO_TOKEN: it needs read access to the project.", "")
        throw CodegenException("Coho refused the token. $said$hint")
    }

    /** The token: the environment's, then a `.env` beside the config's. CI sets the variable, which wins. */
    private fun token(context: Context, directory: File): String? =
        context.environment("COHO_TOKEN")?.trim()?.ifEmpty { null }
            ?: DotEnv.read(File(directory, ".env"))["COHO_TOKEN"]?.trim()?.ifEmpty { null }

    private class Options(
        val command: String?,
        val config: String,
        val ref: String?,
        val url: String?,
        val packageName: String?,
        val help: Boolean,
    )
    {
        companion object
        {
            fun parse(args: List<String>): Options
            {
                var command: String? = null
                val named = HashMap<String, String>()
                var help = false
                var i = 0
                while (i < args.size)
                {
                    when (val arg = args[i])
                    {
                        "-h", "--help" -> help = true
                        "-c", "--config", "--ref", "--url", "--package" ->
                        {
                            if (i + 1 >= args.size)
                            {
                                throw CodegenException("$arg needs a value")
                            }
                            named[if (arg == "-c") "--config" else arg] = args[++i]
                        }
                        else ->
                        {
                            if (arg.startsWith("-"))
                            {
                                throw CodegenException("Unknown option '$arg'. See coho-codegen --help.")
                            }
                            if (command != null)
                            {
                                throw CodegenException("One command at a time: '$command', then '$arg'")
                            }
                            command = arg
                        }
                    }
                    i++
                }
                return Options(command, named["--config"] ?: "coho.config.json", named["--ref"], named["--url"], named["--package"], help)
            }
        }
    }
}

/** Renames a library made from the template: its package, in every file and directory that names it. */
internal object Rename
{
    private val SKIPPED = setOf(".git", ".gradle", "build", ".kotlin", ".idea")
    private val TEXT = setOf("java", "kt", "kts", "json", "md", "properties", "toml", "yml", "yaml")

    fun packageOf(root: File, old: String, new: String)
    {
        val files = root.walkTopDown().onEnter { it.name !in SKIPPED }.filter { it.isFile }.toList()
        for (file in files.filter { it.extension in TEXT })
        {
            val text = file.readText()
            if (old in text)
            {
                file.writeText(text.replace(old, new))
            }
        }
        // Each source root's directory for the package moves to the new one.
        val from = old.replace('.', '/')
        val to = new.replace('.', '/')
        for (directory in root.walkTopDown().onEnter { it.name !in SKIPPED }.filter { it.isDirectory && it.path.endsWith("/$from") }.toList())
        {
            val target = File(directory.path.removeSuffix(from) + to)
            target.parentFile.mkdirs()
            if (!directory.renameTo(target))
            {
                throw CodegenException("Cannot move $directory to $target")
            }
            // Remove the old package's parents, now empty.
            var parent = directory.parentFile
            while (parent != null && parent.isDirectory && parent.list()?.isEmpty() == true)
            {
                parent.delete()
                parent = parent.parentFile
            }
        }
    }
}
