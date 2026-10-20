package com.cohocms.codegen

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenerateTest
{
    private val spec = File(System.getProperty("coho.spec") ?: "../spec")
    private val expected = File(System.getProperty("coho.expected") ?: "src/test/expected")
    private val rewrite = System.getProperty("coho.rewrite") == "true"

    private val fixtures = File(spec, "type-model/fixtures").listFiles()!!.filter { it.extension == "json" }.sortedBy { it.name }

    /** The package a fixture's code is generated into: `golden.clinextblog.coho`. */
    private fun packageOf(fixture: File) = "golden.${fixture.nameWithoutExtension.replace("-", "")}.coho"

    @Test
    fun `each fixture generates what is committed, in both languages`()
    {
        // After a change to the generator: ./gradlew :codegen:test -Pcoho.rewrite, and review the diff.
        // The compile-check project compiles what is committed, and reads the recordings through it.
        val problems = mutableListOf<String>()
        for (language in Language.entries)
        {
            for (fixture in fixtures)
            {
                val generated = generate(TypeModel.parse(fixture.readText(), fixture.name), packageOf(fixture), language)
                val outDir = File(expected, "${language.name.lowercase()}/${packageOf(fixture).replace('.', '/')}")
                if (rewrite)
                {
                    outDir.deleteRecursively()
                    Files.write(generated.files, outDir)
                }
                problems += Files.drift(generated.files, outDir).map { "$language ${fixture.name}: $it" }
            }
        }
        assertEquals(emptyList(), problems)
    }

    private fun model(fields: String, slug: String = "story", immutable: Boolean = true) = TypeModel.parse(
        """{"irVersion":"1.4","source":{"project":"p","ref":"v1","immutable":$immutable,"schemaHash":"sha256:0"},
            "types":{"$slug":{"slug":"$slug","name":"Story","version":"1","fields":[$fields]}}}""",
        "test",
    )

    private fun field(id: String, kind: String = "text", extra: String = "") =
        """{"id":"$id","name":"$id","kind":"$kind","presence":"maybe","required":false,"localized":false,"sortable":false$extra}"""

    private fun contents(generated: Generated, path: String) = generated.files.single { it.path == path }.contents

    @Test
    fun `a field named like a keyword or a system value is renamed, and keeps its id on the wire`()
    {
        val generated = generate(model(listOf(field("class"), field("type"), field("when")).joinToString(",")), "p.coho", Language.KOTLIN)
        val page = contents(generated, "Story.kt")
        assertContains(page, """val classValue: String? = entry.text("class")""")
        assertContains(page, """val typeValue: String? = entry.text("type")""")
        assertContains(page, """val whenValue: String? = entry.text("when")""")
        assertEquals(3, generated.warnings.count { "is read as" in it })
    }

    @Test
    fun `a type named like the generated code gets Entry after it, and one read like the client gets Type`()
    {
        val coho = generate(model(field("title"), slug = "coho"), "p.coho", Language.JAVA)
        assertContains(contents(coho, "CohoEntry.java"), "public final class CohoEntry extends EntryBase")

        val content = generate(model(field("title"), slug = "content"), "p.coho", Language.KOTLIN)
        assertContains(contents(content, "Coho.kt"), "val contentType: TypeReader<Content, NoOrder>")
    }

    @Test
    fun `an unknown kind is read as JSON, with a warning, never a failure`()
    {
        val generated = generate(model(field("where", "geopoint")), "p.coho", Language.JAVA)
        assertContains(contents(generated, "Story.java"), "this.where = entry.json(\"where\");")
        assertTrue(generated.warnings.any { "'geopoint'" in it })
    }

    @Test
    fun `a branch warns that its types can change`()
    {
        assertTrue(generate(model(field("title"), immutable = false), "p.coho", Language.KOTLIN).warnings.any { "a branch" in it })
    }

    @Test
    fun `a field delivery always sends has no null, and is read with its empty value`()
    {
        val generated = generate(model(field("rank", "integer").replace("\"maybe\"", "\"always\"")), "p.coho", Language.JAVA)
        assertContains(contents(generated, "Story.java"), "this.rank = entry.requiredInteger(\"rank\");")
        assertContains(contents(generated, "Story.java"), "public long getRank()")
    }

    @Test
    fun `names in comments cannot end them, and text in strings cannot escape them`()
    {
        val generated = generate(model(field("title").replace("\"name\":\"title\"", "\"name\":\"Ends */ here, costs \$5\"")), "p.coho", Language.KOTLIN)
        val page = contents(generated, "Story.kt")
        assertContains(page, "/** Ends *&#47; here, costs \$5 */")
    }

    @Test
    fun `an unsupported major version is refused`()
    {
        val error = assertFailsWith<CodegenException> { TypeModel.parse("""{"irVersion":"2.0","source":{},"types":{}}""", "test") }
        assertContains(error.message!!, "version 2.0; this generator reads 1.x")
    }

    @Test
    fun `generate writes the configured language, removes what it no longer writes, and check sees drift`()
    {
        val directory = kotlin.io.path.createTempDirectory("coho").toFile()
        File(directory, "coho-ir.json").writeText(File(spec, "type-model/fixtures/cli-next-blog.json").readText())
        File(directory, "coho.config.json").writeText("""{"package": "com.acme.content", "language": "kotlin"}""")
        val outDir = File(directory, "src/main/kotlin/com/acme/content/coho")
        outDir.mkdirs()
        File(outDir, "Gone.kt").writeText("$GENERATED_MARK\n")
        File(outDir, "Mine.kt").writeText("// Not generated.\n")
        val out = ByteArrayOutputStream()
        val context = Main.Context(directory, { null }, PrintStream(out), PrintStream(ByteArrayOutputStream()), HttpClient.newHttpClient())

        assertEquals(0, Main.run(listOf("generate"), context))
        assertTrue(File(outDir, "BlogPost.kt").readText().startsWith(GENERATED_MARK))
        assertTrue(!File(outDir, "Gone.kt").exists() && File(outDir, "Mine.kt").exists())

        File(outDir, "Mine.kt").delete()
        assertEquals(0, Main.run(listOf("check"), context))
        File(outDir, "Tag.kt").appendText("// changed\n")
        assertEquals(1, Main.run(listOf("check"), context))
    }

    @Test
    fun `init renames the library's package wherever it appears`()
    {
        val directory = kotlin.io.path.createTempDirectory("coho").toFile()
        File(directory, "coho.config.json").writeText("""{"package": "com.yourorg.content", "language": "java"}""")
        val source = File(directory, "src/main/java/com/yourorg/content/CohoReads.java")
        source.parentFile.mkdirs()
        source.writeText("package com.yourorg.content;\n")
        File(directory, "build.gradle.kts").writeText("group = \"com.yourorg.content\"\n")
        val context = Main.Context(directory, { null }, PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()), HttpClient.newHttpClient())

        assertEquals(0, Main.run(listOf("init", "--package", "com.acme.blogcontent"), context))

        val moved = File(directory, "src/main/java/com/acme/blogcontent/CohoReads.java")
        assertEquals("package com.acme.blogcontent;\n", moved.readText())
        assertTrue(!File(directory, "src/main/java/com/yourorg").exists())
        assertEquals("group = \"com.acme.blogcontent\"\n", File(directory, "build.gradle.kts").readText())
        assertContains(File(directory, "coho.config.json").readText(), "\"package\": \"com.acme.blogcontent\"")
    }
}
