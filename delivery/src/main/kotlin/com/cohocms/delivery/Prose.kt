package com.cohocms.delivery

/**
 * Turning an `asset:` reference in a body into the file it names (coho-data doc 80 ML-1).
 *
 * A Markdown or HTML field names an asset by **slug** — `![A bridge](asset:bridge-at-dusk)` —
 * rather than by URL, so the body carries no asset domain, survives being moved between
 * branches, and is seen by everything that counts a file as used. Nothing renders that on its
 * own: Coho records what the prose named in the entry's [EntryBase.bodyAssets], delivery embeds
 * those assets as it embeds any asset field, and this turns one into the other.
 *
 * It is here rather than in each application because the rule is not obvious and has to match
 * the server's: several copies of these patterns already exist, and every consumer that renders
 * a body would otherwise write another.
 *
 * ⚠️ **Resolve at the read, and never before a write.** Call this where an entry arrives, once,
 * rather than at each place a body is drawn — a body usually reaches more than one renderer, and
 * the version that none of them can forget is the one at the read.
 *
 * ⛔ **Never resolve a body you may write back.** A resolved body holds URLs, and saving one
 * destroys the reference the scheme exists to keep: the asset stops counting as used, the link
 * breaks when the file is replaced, and moving the entry to another project carries the old
 * domain with it. Anything that edits content must keep the body exactly as delivered — which is
 * why resolving is a call you make and not something a read does for you.
 *
 * ⚠️ A reference to an asset the entry does not carry is **left exactly as written**. That is an
 * asset not merged onto this ref, or a slug typed by hand; an image then falls back to its alt
 * text. Rewriting it to nothing would hide it, and throwing would stop a page over one figure.
 */
object Prose
{
    /** The scheme a body names an asset by. */
    const val SCHEME: String = "asset:"

    /** The key delivery sends an entry's prose assets under (coho-data doc 80 §10). */
    const val BODY_ASSETS: String = "_bodyAssets"

    /** A slug's shape, as the server reads one. These must agree. */
    private const val SLUG = "[A-Za-z0-9][A-Za-z0-9._-]*"

    private val slugOnly = Regex("^$SLUG$")

    /**
     * `![alt](asset:slug)` and `[label](asset:slug)`, with Markdown's optional title after it.
     * Bare text is deliberately not a reference, so a body can write about the scheme.
     */
    private val target = Regex("""(!?\[[^\]]*\]\(\s*)$SCHEME($SLUG)(\s*(?:"[^"]*")?\s*\))""")

    /**
     * `src`, `href` or `poster` naming an asset: a video directive's attribute in Markdown, or an
     * attribute in an HTML field. Only attributes that fetch something, and only when the whole
     * value is the reference — so `alt="asset:x"` and a URL with the text inside it are not
     * mistaken for one.
     */
    private val attribute = Regex("""(\b(?:src|href|poster)\s*=\s*["'])$SCHEME($SLUG)(["'])""")

    /** What a body writes to name the asset with this [slug]. */
    @JvmStatic
    fun reference(slug: String): String = SCHEME + slug

    /**
     * The slug [value] references, or null when it is an ordinary URL or anything else. For code
     * that wants to find references rather than replace them.
     */
    @JvmStatic
    fun referencedSlug(value: String?): String?
    {
        val slug = value?.removePrefix(SCHEME)?.takeIf { it != value } ?: return null
        return slug.takeIf { slugOnly.matches(it) }
    }

    /**
     * [text] with every `asset:` reference [entry] carries replaced by that asset's URL: the
     * overload a renderer holding a typed entry wants.
     */
    @JvmStatic
    fun resolve(text: String?, entry: EntryBase): String? = resolve(text, entry.bodyAssets)

    /**
     * [text] with every `asset:` reference [assets] can place replaced by that asset's URL. An
     * asset with no slug or no URL cannot be resolved to, and a reference to one is left as
     * written.
     */
    @JvmStatic
    fun resolve(text: String?, assets: List<Asset>): String?
    {
        if (text == null || !text.contains(SCHEME)) return text

        val urls = assets.mapNotNull { asset ->
            val slug = asset.slug?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = asset.url?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            slug to url
        }.toMap()
        if (urls.isEmpty()) return text

        val swap: (MatchResult) -> CharSequence = { match ->
            val url = urls[match.groupValues[2]]
            if (url == null) match.value else match.groupValues[1] + url + match.groupValues[3]
        }
        return attribute.replace(target.replace(text, swap), swap)
    }
}
