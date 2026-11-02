package golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cohocms.delivery.Answering;
import com.cohocms.delivery.ClientOptions;
import com.cohocms.delivery.ContentConfig;
import com.cohocms.delivery.EntryBase;
import com.cohocms.delivery.EntryGraph;
import com.cohocms.delivery.HydrationKt;
import com.cohocms.delivery.Issue;
import com.cohocms.delivery.ImageOptions;
import com.cohocms.delivery.Linked;
import com.cohocms.delivery.ListOptions;
import com.cohocms.delivery.Page;
import com.cohocms.delivery.ReadOptions;
import com.cohocms.delivery.ReleasedConfig;
import com.cohocms.delivery.Replay;
import com.cohocms.delivery.SingletonReader;
import com.cohocms.delivery.Spec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import golden.deliveryfixture.coho.Article;
import golden.deliveryfixture.coho.Citation;
import golden.deliveryfixture.coho.Coho;
import golden.deliveryfixture.coho.Figure;
import golden.deliveryfixture.coho.Note;
import golden.deliveryfixture.coho.Outline;
import golden.deliveryfixture.coho.OutlineSection;
import golden.deliveryfixture.coho.Point;
import golden.deliveryfixture.coho.Step;
import golden.singletons.coho.HomePage;
import golden.singletons.coho.SiteSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Java the generator writes, read through: the committed code for the spec's fixtures, run
 * against the recordings, so what the generator writes is checked against what the server sends,
 * and written the way a Java app writes it.
 */
class GeneratedJavaTest
{
    private static Coho coho()
    {
        return new Coho(
            new ContentConfig(new ReleasedConfig("https://delivery.test", "p1", "test-key"), "v0.0.1"),
            new ClientOptions().withTransport(new Replay()));
    }

    @Test
    void aTypedReadReturnsTheTypesClassWithItsReferencesFollowed()
    {
        Article post = coho().article().bySlug("chain-1", new ReadOptions(2, "en-US"));

        assertNotNull(post);
        assertEquals("Chain 1", post.getTitle());
        Article second = Linked.resolved(post.getRelated()).get(0);
        assertEquals("chain-2", second.getSlug());
        Linked<Article> absent = second.getRelated().get(1);
        assertInstanceOf(Linked.Unresolved.class, absent);
        assertEquals("absent", ((Linked.Unresolved) absent).getReason());
        Article third = second.getRelated().get(0).getValue();
        assertNotNull(third);
        assertEquals("cycle", ((Linked.Unresolved) third.getRelated().get(0)).getReason());
    }

    @Test
    void aListIsOrderedByASortableFieldDescending()
    {
        Page<Article> page = coho().article().list(
            new ListOptions<Article.SortBy>().withOrder(Article.SortBy.RANK.descending()).withLimit(3).withLocale("en-US"));

        List<Long> ranks = page.getItems().stream().map(article -> article.getRank() == null ? 0L : article.getRank()).toList();
        assertEquals(ranks.stream().sorted((a, b) -> Long.compare(b, a)).toList(), ranks);
        assertTrue(page.getHasMore());
    }

    @Test
    void assetsAndRepeaterItemsAreTyped()
    {
        Coho coho = coho();
        Figure figure = coho.figure().byId("00000000-0000-7000-8000-0000000000f1");
        assertNotNull(figure);
        assertNotNull(figure.getImage());
        assertTrue(figure.getImage().image(ImageOptions.fill(800, 400)).endsWith("fit=fill&fx=0.25&fy=0.75"));
        assertNull(figure.getPoster());

        Figure withSources = coho.figure().byId("00000000-0000-7000-8000-0000000000f2");
        assertNotNull(withSources);
        List<Citation> sources = withSources.getSources();
        assertEquals(2, sources.size());
        assertEquals(2010L, sources.get(1).getYear());
    }

    @Test
    void listsAndLocalizedItemFieldsReadInTheRequestedLocale()
    {
        Figure figure = coho().figure().byId("00000000-0000-7000-8000-0000000000f3", ReadOptions.DEFAULT.withLocale("de-DE"));
        assertNotNull(figure);

        List<Step> steps = figure.getSteps();
        assertEquals("Messen", steps.get(0).getLabel());
        assertNull(steps.get(1).getLabel());
        // None in this locale, on purpose: an empty list, as delivery sent it.
        assertEquals(List.of(), figure.getKeywords());
        assertEquals(List.of(3L, 5L), figure.getSizes());
    }

    @Test
    void aLocalizedListTheLocaleHasNoTranslationOfIsNullAndAnUnlocalizedOneEmpty()
    {
        JsonNode json = Spec.INSTANCE.body("entry-with-localized-items").deepCopy();
        ((ObjectNode) json.path("entries").path("00000000-0000-7000-8000-0000000000f3").path("fields")).remove(List.of("keywords", "sizes"));

        List<EntryBase> roots = HydrationKt.hydrate(EntryGraph.of(json), Coho.FACTORIES, issue -> { });
        Figure figure = assertInstanceOf(Figure.class, roots.get(0));
        assertNull(figure.getKeywords());
        assertEquals(List.of(), figure.getSizes());
    }

    @Test
    void itemsHoldItemsTypedAtEveryLevelInTheRequestedLocale()
    {
        Outline outline = coho().outline().byId("00000000-0000-7000-8000-0000000000b1", ReadOptions.DEFAULT.withLocale("en-US"));
        assertNotNull(outline);

        OutlineSection section = outline.getSections().get(0);
        assertEquals("00000000-0000-7000-8000-0000000000b2", section.getId());
        assertEquals("outlineSection", section.getType());
        assertEquals("Plan", section.getHeading());
        Point first = section.getPoints().get(0);
        Point second = first.getChildren().get(0);
        Point third = second.getChildren().get(0);
        assertEquals("Survey", first.getText());
        assertEquals("Measure", second.getText());
        // Only in pt-BR, so left out in en-US.
        assertNull(third.getText());
        assertEquals("00000000-0000-7000-8000-0000000000b5", third.getId());
        assertEquals("point", third.getType());
        assertEquals(List.of(), third.getChildren());
        List<Note> notes = section.getNotes();
        assertEquals(List.of("Bring cones"), notes.stream().map(Note::getText).toList());
    }

    @Test
    void aNestedItemOfAComponentTheCodeWasNotGeneratedWithIsLeftOutAndReportedWhereItIs()
    {
        JsonNode json = Spec.INSTANCE.body("entry-with-nested-items").deepCopy();
        ((ArrayNode) json.at("/entries/00000000-0000-7000-8000-0000000000b1/fields/sections/0/points/0/children"))
            .addObject().put("_id", "00000000-0000-7000-8000-0000000000b9").put("_type", "callout");

        List<Issue> issues = new ArrayList<>();
        List<EntryBase> roots = HydrationKt.hydrate(EntryGraph.of(json), Coho.FACTORIES, issues::add);
        Outline outline = assertInstanceOf(Outline.class, roots.get(0));
        List<Point> children = outline.getSections().get(0).getPoints().get(0).getChildren();
        assertEquals(List.of("Measure"), children.stream().map(Point::getText).toList());
        assertEquals(List.of("unknown-item sections/0/points/0/children"), issues.stream().map(issue -> issue.getKind() + " " + issue.getField()).toList());
    }

    @Test
    void aSingletonIsReadByItsOneGetterAtTheSlugTheTypeModelNames()
    {
        // The recordings have no singleton, so its entry is answered here.
        Answering answering = new Answering(200, """
            {"roots":["s"],"entries":{
                "s":{"id":"s","slug":"site-settings","type":"siteSettings","fields":{"siteName":"Acme","home":"h"}},
                "h":{"id":"h","slug":"home-page","type":"homePage","fields":{"title":"Home","featured":[]}}},
                "unresolved":{}}""", Map.of("content-type", "application/json"));
        golden.singletons.coho.Coho coho = new golden.singletons.coho.Coho(
            new ContentConfig(new ReleasedConfig("https://delivery.test", "p1", "test-key"), "v0.0.1"),
            new ClientOptions().withTransport(answering));

        SingletonReader<SiteSettings> reader = coho.siteSettings();
        SiteSettings settings = reader.get(ReadOptions.DEFAULT.withDepth(1));
        assertNotNull(settings);
        assertEquals("Acme", settings.getSiteName());
        assertNotNull(settings.getHome());
        HomePage home = settings.getHome().getValue();
        assertNotNull(home);
        // It extends page, so it is a Page too.
        golden.singletons.coho.Page page = home;
        assertEquals("Home", page.getTitle());
        assertEquals("https://delivery.test/v1/projects/p1/v0.0.1/entries-by-slug/site-settings?depth=1", answering.getAsked().get(0).getUrl());
        // The entry at another singleton's slug is of another type, so it is not that singleton's.
        assertNull(coho.homePage().getAsync(ReadOptions.DEFAULT).join());
    }

    @Test
    void aSingletonIsNullBeforeItsEntryIsFirstWritten()
    {
        golden.singletons.coho.Coho coho = new golden.singletons.coho.Coho(
            new ContentConfig(new ReleasedConfig("https://delivery.test", "p1", "test-key"), "v0.0.1"),
            new ClientOptions().withTransport(new Answering()));
        assertNull(coho.siteSettings().get());
    }

    @Test
    void anAsyncReadCompletesWithTheSame()
    {
        Article post = coho().article().bySlugAsync("chain-1", new ReadOptions(2, "en-US")).join();
        assertNotNull(post);
        assertEquals("Chain 1", post.getTitle());
    }

    @Test
    void theSourceNamesTheTypeModel()
    {
        assertTrue(Coho.SOURCE.getSchemaHash().startsWith("sha256:"));
        assertTrue(Coho.CONTENT_TYPES.contains("article"));
    }

    @Test
    void aSubtypeIsItsBaseTypesClassAndATraitIsAnInterface()
    {
        assertTrue(golden.inheritance.coho.Page.class.isAssignableFrom(golden.inheritance.coho.ServicePage.class));
        assertTrue(golden.inheritance.coho.ServicePage.class.isAssignableFrom(golden.inheritance.coho.PremiumService.class));
        assertTrue(golden.traits.coho.Seo.class.isAssignableFrom(golden.traits.coho.Article.class));
        assertTrue(golden.traits.coho.Seo.class.isAssignableFrom(golden.traits.coho.Page.class));
    }
}
