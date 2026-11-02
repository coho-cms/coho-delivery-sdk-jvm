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

    private fun model(fields: String, slug: String = "story", immutable: Boolean = true, extra: String = "") = TypeModel.parse(
        """{"irVersion":"1.7","source":{"project":"p","ref":"v1","immutable":$immutable,"schemaHash":"sha256:0"},
            "types":{"$slug":{"slug":"$slug","name":"Story","version":"1"$extra,"fields":[$fields]}}}""",
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
    fun `a list is a list of its item's type, and a localized one is null where the locale has no translation`()
    {
        val fields = listOf(
            field("keywords", "list", ""","item":"text"""").replace("\"localized\":false", "\"localized\":true"),
            field("sizes", "list", ""","item":"integer","max":5"""),
            field("scores", "list", ""","item":"number""""),
            field("sessions", "list", ""","item":"datetime","min":1""").replace("\"maybe\"", "\"always\""),
        ).joinToString(",")

        val kotlin = generate(model(fields), "p.coho", Language.KOTLIN)
        val story = contents(kotlin, "Story.kt")
        assertContains(story, "/** keywords. Null where the locale has no translation, and empty where it has none on purpose. */")
        assertContains(story, """val keywords: List<String>? = entry.textsOrNull("keywords")""")
        assertContains(story, """val sizes: List<Long> = entry.integers("sizes")""")
        assertContains(story, """val scores: List<Double> = entry.numbers("scores")""")
        assertContains(story, """val sessions: List<OffsetDateTime> = entry.dateTimes("sessions")""")
        assertContains(story, "import java.time.OffsetDateTime")
        assertEquals(emptyList(), kotlin.warnings)

        val java = generate(model(fields), "p.coho", Language.JAVA)
        val page = contents(java, "Story.java")
        assertContains(page, "this.keywords = entry.textsOrNull(\"keywords\");")
        assertContains(page, "public @Nullable List<String> getKeywords()")
        // Not localized, so never null: missing, it is empty.
        assertContains(page, "public List<Long> getSizes()")
        assertContains(page, "public List<OffsetDateTime> getSessions()")
        assertEquals(emptyList(), java.warnings)
    }

    @Test
    fun `a component's list, localized or not, is typed as a type's is`()
    {
        val fixture = File(spec, "type-model/fixtures/lists.json")
        val kotlin = generate(TypeModel.parse(fixture.readText(), fixture.name), "p.coho", Language.KOTLIN)
        val talk = contents(kotlin, "Talk.kt")
        assertContains(talk, """val speakers: List<String> = entry.texts("speakers")""")
        assertContains(talk, "/** tracks. Null where the locale has no translation, and empty where it has none on purpose. */")
        assertContains(talk, """val tracks: List<Long>? = entry.integersOrNull("tracks")""")

        val java = generate(TypeModel.parse(fixture.readText(), fixture.name), "p.coho", Language.JAVA)
        assertContains(contents(java, "Talk.java"), "public @Nullable List<Long> getTracks()")
        assertContains(contents(java, "Talk.java"), "public List<String> getSpeakers()")
        assertTrue(java.warnings.none { "does not know" in it })
    }

    @Test
    fun `a component holds a repeater as a type does, and one that holds itself is a recursive class`()
    {
        val fixture = File(spec, "type-model/fixtures/nested.json")
        val kotlin = generate(TypeModel.parse(fixture.readText(), fixture.name), "p.coho", Language.KOTLIN)
        assertContains(contents(kotlin, "Section.kt"), """val cards: List<Card> = entry.items("cards", Card::class.java)""")
        assertContains(contents(kotlin, "MenuItem.kt"), """val children: List<MenuItem> = entry.items("children", MenuItem::class.java)""")
        assertContains(contents(kotlin, "LocaleMenuItem.kt"), """val children: List<LocaleMenuItem> = entry.items("children", LocaleMenuItem::class.java)""")
        assertEquals(emptyList(), kotlin.warnings)

        val java = generate(TypeModel.parse(fixture.readText(), fixture.name), "p.coho", Language.JAVA)
        assertContains(contents(java, "MenuItem.java"), "this.children = entry.items(\"children\", MenuItem.class);")
        assertContains(contents(java, "MenuItem.java"), "public List<MenuItem> getChildren()")
        assertEquals(emptyList(), java.warnings)
    }

    @Test
    fun `a singleton is read by one getter, at the slug the type model names`()
    {
        val fixture = File(spec, "type-model/fixtures/singletons.json")
        val kotlin = generate(TypeModel.parse(fixture.readText(), fixture.name), "p.coho", Language.KOTLIN)
        val client = contents(kotlin, "Coho.kt")
        assertContains(client, """val siteSettings: SingletonReader<SiteSettings> = content.singleton("siteSettings", "site-settings", SiteSettings::class.java)""")
        // It extends page, so a read of page finds it, as any subtype's entry.
        assertContains(client, """val page: TypeReader<Page, NoOrder> = content.type("page", Page::class.java)""")
        assertContains(contents(kotlin, "SiteSettings.kt"), "/** Site settings. A singleton: its one entry answers to \"site-settings\". */")
        assertEquals(emptyList(), kotlin.warnings)

        val java = generate(TypeModel.parse(fixture.readText(), fixture.name), "p.coho", Language.JAVA)
        assertContains(contents(java, "Coho.java"), "this.homePage = content.singleton(\"homePage\", \"home-page\", HomePage.class);")
        assertContains(contents(java, "Coho.java"), "public SingletonReader<HomePage> homePage()")
        assertEquals(emptyList(), java.warnings)
    }

    @Test
    fun `a singleton with no entrySlug is read as an ordinary type, with a warning`()
    {
        val generated = generate(model(field("siteName"), slug = "siteSettings", extra = ",\"singleton\":true"), "p.coho", Language.KOTLIN)
        assertContains(contents(generated, "Coho.kt"), "val siteSettings: TypeReader<SiteSettings, NoOrder> = content.type(\"siteSettings\", SiteSettings::class.java)")
        assertEquals(
            listOf("Type 'siteSettings' is a singleton with no entrySlug, so it is read as an ordinary type. Pull the type model again."),
            generated.warnings,
        )
    }

    @Test
    fun `a list of an item this generator does not know is read as JSON, with a warning`()
    {
        val generated = generate(model(field("where", "list", ""","item":"geopoint"""")), "p.coho", Language.KOTLIN)
        assertContains(contents(generated, "Story.kt"), """val where: JsonNode? = entry.json("where")""")
        assertTrue(generated.warnings.any { "a list of 'geopoint'" in it })
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
