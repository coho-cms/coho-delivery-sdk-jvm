package com.cohocms.codegen

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * `coho.config.json`, the same file every Coho generator reads. Paths are relative to it. The
 * token is never here: `pull` reads it from `COHO_TOKEN`, so the file can be committed.
 *
 * @property packageName The library's package, `"package"` in the file. The generated code is
 *   in its `coho` subpackage.
 * @property language `"java"` or `"kotlin"`: what `generate` writes.
 */
class CodegenConfig(
    val url: String?,
    val account: String?,
    val project: String?,
    val ref: String?,
    val typeModel: String?,
    val outDir: String?,
    val packageName: String?,
    val language: String?,
)
{
    fun withRef(ref: String) = CodegenConfig(url, account, project, ref, typeModel, outDir, packageName, language)
}

/** A config, with its paths resolved. */
class Loaded(val config: CodegenConfig, val file: File)
{
    val directory: File = file.absoluteFile.parentFile

    val typeModel: File get() = File(directory, config.typeModel ?: "coho-ir.json")

    val language: Language get() = Language.of(config.language)

    /** The generated code's package: the library's, and `coho`. */
    val generatedPackage: String
        get() = "${config.packageName ?: throw CodegenException("${file.name} names no \"package\" for the library, such as \"com.acme.content\"")}.coho"

    val outDir: File
        get() = File(directory, config.outDir ?: "${language.sourceRoot}/${generatedPackage.replace('.', '/')}")

    companion object
    {
        fun from(file: File): Loaded
        {
            val json = try
            {
                MAPPER.readTree(file)
            }
            catch (error: Exception)
            {
                throw CodegenException("Cannot read $file: ${error.message}")
            }
            if (json !is ObjectNode)
            {
                throw CodegenException("$file is not a JSON object")
            }
            fun text(key: String) = json.get(key)?.takeIf { it.isTextual }?.asText()?.ifBlank { null }
            return Loaded(
                CodegenConfig(
                    text("url"), text("account"), text("project"), text("ref"), text("typeModel"), text("outDir"), text("package"), text("language"),
                ),
                file,
            )
        }

        /** Sets [values] in the config file, keeping everything else in it as it was. */
        fun edit(file: File, values: Map<String, String?>)
        {
            val json = if (file.exists()) MAPPER.readTree(file) as? ObjectNode ?: throw CodegenException("$file is not a JSON object") else MAPPER.createObjectNode()
            values.forEach { (key, value) -> if (!value.isNullOrEmpty()) json.put(key, value) }
            file.writeText(PRETTY.writeValueAsString(json).replace("\" : ", "\": ") + "\n")
        }

        private val MAPPER = ObjectMapper()
        private val PRETTY = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    }
}

object Files
{
    fun readTypeModel(file: File): TypeModel
    {
        if (!file.exists())
        {
            throw CodegenException("No type model at $file: run ./gradlew cohoPull")
        }
        return TypeModel.parse(file.readText(), file.toString())
    }

    /**
     * Fetches the type model for the configured ref and writes it to [file] as it arrived, so the
     * committed file is exactly what the server sent.
     *
     * The server's ETag is the SHA-256 of the body, and the file is the body, so the file's own
     * hash asks whether anything changed; an unchanged model costs a `304`. A model this
     * generator cannot read is refused before anything is written.
     *
     * @return whether the file changed, and the model it now holds
     */
    fun pull(config: CodegenConfig, file: File, token: String, http: HttpClient): Pair<Boolean, TypeModel>
    {
        val url = config.url
        val account = config.account
        val project = config.project
        val ref = config.ref
        if (url == null || account == null || project == null || ref == null)
        {
            throw CodegenException("pull needs url, account, project and ref in the config")
        }
        val endpoint = "${url.trimEnd('/')}/api/v1/accounts/${encode(account)}/projects/${encode(project)}/refs/${encode(ref)}/type-model"

        val existing = if (file.exists()) file.readBytes() else null
        val request = HttpRequest.newBuilder(URI.create(endpoint)).header("accept", "application/json").header("authorization", "Bearer $token")
        existing?.let { request.header("if-none-match", "\"${sha256(it)}\"") }
        val response = send(http, request.build(), url)
        if (response.statusCode() == 304 && existing != null)
        {
            return false to TypeModel.parse(String(existing), file.toString())
        }
        if (response.statusCode() !in 200..299)
        {
            throw CodegenException(failure(response, "Pulling the type model"))
        }
        val model = TypeModel.parse(String(response.body()), endpoint)

        // Written beside the file and moved over it, so a failure never leaves half a model.
        file.absoluteFile.parentFile.mkdirs()
        val temporary = File(file.absoluteFile.parentFile, "${file.name}.tmp")
        temporary.writeBytes(response.body())
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return true to model
    }

    /**
     * Writes [files] into [outDir], and removes the files an earlier run generated that this one
     * does not, so a type taken off the ref takes its file with it. A file without the generated
     * header is never removed.
     */
    fun write(files: List<GeneratedFile>, outDir: File)
    {
        outDir.mkdirs()
        files.forEach { File(outDir, it.path).writeText(it.contents) }
        val names = files.map { it.path }.toSet()
        outDir.listFiles()?.filter { it.isFile && it.name !in names && it.readText().startsWith(GENERATED_MARK) }?.forEach { it.delete() }
    }

    /**
     * How [outDir] differs from [files]: each file missing or different, and each generated file
     * there that nothing generates now. Empty when the generated code is up to date.
     */
    fun drift(files: List<GeneratedFile>, outDir: File): List<String>
    {
        val problems = mutableListOf<String>()
        for (file in files)
        {
            val path = File(outDir, file.path)
            when
            {
                !path.exists() -> problems += "${file.path} is missing"
                path.readText() != file.contents -> problems += "${file.path} differs from what the type model generates"
            }
        }
        val names = files.map { it.path }.toSet()
        outDir.listFiles()?.filter { it.isFile && it.name !in names }?.sortedBy { it.name }?.forEach()
        {
            problems += "${it.name} is in the output directory, but nothing generates it"
        }
        return problems
    }

    internal fun send(http: HttpClient, request: HttpRequest, origin: String): HttpResponse<ByteArray> =
        try
        {
            http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        }
        catch (error: java.io.IOException)
        {
            throw CodegenException("Cannot reach Coho at $origin: ${error.message ?: error}")
        }

    internal fun failure(response: HttpResponse<ByteArray>, doing: String): String
    {
        val problem = runCatching { ObjectMapper().readTree(response.body()) }.getOrNull()
        val detail = listOfNotNull(
            problem?.get("code")?.asText(),
            (problem?.get("detail") ?: problem?.get("title"))?.asText(),
        ).joinToString(": ")
        val hint = if (response.statusCode() in setOf(401, 403)) " Check COHO_TOKEN: it needs read access to the project." else ""
        return "$doing failed with ${response.statusCode()}${if (detail.isEmpty()) "" else " ($detail)"}.$hint"
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

/** A `.env` file: `NAME=value` lines, as Coho's other generators read them. */
object DotEnv
{
    fun read(file: File): Map<String, String>
    {
        if (!file.exists())
        {
            return emptyMap()
        }
        return file.readLines().mapNotNull()
        { raw ->
            val line = raw.trim().removePrefix("export ").trim()
            val equals = line.indexOf('=')
            if (line.isEmpty() || line.startsWith('#') || equals <= 0)
            {
                null
            }
            else
            {
                var value = line.substring(equals + 1).trim()
                if (value.length >= 2 && (value[0] == '"' || value[0] == '\'') && value.last() == value[0])
                {
                    value = value.substring(1, value.length - 1)
                }
                line.substring(0, equals).trim() to value
            }
        }.toMap()
    }
}
