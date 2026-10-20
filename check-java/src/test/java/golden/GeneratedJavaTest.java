package golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cohocms.delivery.ClientOptions;
import com.cohocms.delivery.ContentConfig;
import com.cohocms.delivery.ImageOptions;
import com.cohocms.delivery.Linked;
import com.cohocms.delivery.ListOptions;
import com.cohocms.delivery.Page;
import com.cohocms.delivery.ReadOptions;
import com.cohocms.delivery.ReleasedConfig;
import com.cohocms.delivery.Replay;
import golden.deliveryfixture.coho.Article;
import golden.deliveryfixture.coho.Citation;
import golden.deliveryfixture.coho.Coho;
import golden.deliveryfixture.coho.Figure;
import java.util.List;
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
