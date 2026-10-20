package com.cohocms.delivery

import java.util.function.Function

/** Where a client reads, with which credential: released content, a preview, or a mount. */
sealed class DeliveryConfig
{
    internal abstract val base: String
    internal abstract val credential: String?
    internal abstract val reads: Reads
}

/**
 * Released content: tags, and environments pointing at them, such as `qa` and `prod`, from
 * delivery, with a delivery key.
 *
 * @property url Where delivery is served, such as `https://delivery.coho.example`.
 * @property project The project id. It selects the project; the key is what authorizes.
 * @property key A delivery key, sent as `Authorization: Bearer` and never in a URL. Leave it
 *   out only for an environment the project has opened to keyless reads.
 */
class ReleasedConfig @JvmOverloads constructor(
    val url: String,
    val project: String,
    val key: String? = null,
) : DeliveryConfig()
{
    override val base = "${url.trimEnd('/')}/v1/projects/${encode(project)}"
    override val credential get() = key
    override val reads = Reads.RELEASED

    /** Never shows the key. */
    override fun toString() = "ReleasedConfig(url=$url, project=$project, key=${if (key == null) "none" else "***"})"

    override fun equals(other: Any?) = other is ReleasedConfig && other.url == url && other.project == project && other.key == key

    override fun hashCode() = listOf(url, project, key).hashCode()
}

/**
 * Unreleased content: `dev` and branch heads, read live through Coho with a project token.
 * The same answers in the same shapes as delivery, never cached, so a preview runs the code
 * production runs.
 *
 * @property url Coho's own URL, where you sign in, such as `https://api.coho.example`. Not delivery's.
 * @property account The account the project belongs to.
 * @property token A project token, `coho_pt_…`, with read access to the project: the viewer
 *   role is enough. It reads everything unreleased, so keep it on the server.
 */
class PreviewConfig(
    val url: String,
    val account: String,
    val project: String,
    val token: String,
) : DeliveryConfig()
{
    init
    {
        require(token.startsWith(TOKEN_PREFIX)) { "A Coho preview token is a project token, starting $TOKEN_PREFIX" }
    }

    override val base = "${url.trimEnd('/')}/api/v1/accounts/${encode(account)}/projects/${encode(project)}/preview"
    override val credential get() = token
    override val reads = Reads.PREVIEW

    /** Never shows the token. */
    override fun toString() = "PreviewConfig(url=$url, account=$account, project=$project, token=***)"

    override fun equals(other: Any?) =
        other is PreviewConfig && other.url == url && other.account == account && other.project == project && other.token == token

    override fun hashCode() = listOf(url, account, project, token).hashCode()

    companion object
    {
        /** The project token prefix, by which Coho tells a token from a person's sign-in. */
        const val TOKEN_PREFIX = "coho_pt_"
    }
}

/** The whole base URL, `…/v1/projects/{project}`, for a mount `url` and `project` cannot express. */
class MountedConfig @JvmOverloads constructor(
    val baseUrl: String,
    val key: String? = null,
) : DeliveryConfig()
{
    override val base = baseUrl.trimEnd('/')
    override val credential get() = key
    override val reads = Reads.RELEASED

    override fun toString() = "MountedConfig(baseUrl=$baseUrl, key=${if (key == null) "none" else "***"})"
}

/** Released content, from delivery; or unreleased, read live through Coho's preview mount. */
enum class Reads
{
    RELEASED,
    PREVIEW,
}

/**
 * What a content client reads, and where from.
 *
 * @property ref The ref to read: an environment such as `prod`, or a tag. With a project
 *   token, a branch, or an environment pointing at one such as `dev`.
 * @property locale The locale to project to, unless a read names its own. Without one,
 *   localized fields are absent.
 */
data class ContentConfig @JvmOverloads constructor(
    val delivery: DeliveryConfig,
    val ref: String,
    val locale: String? = null,
)

/**
 * A content config from environment variables, so whether an app reads released content or
 * previews unreleased content is a deployment's choice, not a code change.
 *
 * | Variable | |
 * |---|---|
 * | `COHO_PROJECT`, `COHO_REF` | Always. The ref is a tag or an environment, or with a preview token, a branch or `dev` |
 * | `COHO_LOCALE` | Optional |
 * | `COHO_DELIVERY_URL`, `COHO_DELIVERY_KEY` | Released content, from delivery. The key may be left out for a public environment |
 * | `COHO_PREVIEW_TOKEN`, `COHO_URL`, `COHO_ACCOUNT` | Unreleased content, live through Coho: a project token, Coho's URL, and the project's account |
 *
 * The names are the same in every Coho SDK, so one deployment's variables serve an app in any
 * language. A preview token selects a preview. Setting it and a delivery key is refused
 * rather than one quietly winning, since the two read different content. An empty value,
 * such as `COHO_DELIVERY_KEY=`, counts as unset.
 */
object CohoEnvironment
{
    /** From the process's environment variables. */
    @JvmStatic
    fun fromEnvironment(): ContentConfig = from(Function { name -> System.getenv(name) })

    /** From any lookup of the variables by name, such as Spring's `Environment::getProperty`. */
    @JvmStatic
    fun from(lookup: Function<String, String?>): ContentConfig
    {
        fun value(name: String): String? = lookup.apply(name)?.trim()?.ifEmpty { null }

        val token = value("COHO_PREVIEW_TOKEN")
        val key = value("COHO_DELIVERY_KEY")
        if (token != null && key != null)
        {
            throw IllegalStateException(
                "Coho: set COHO_PREVIEW_TOKEN to preview unreleased content, or COHO_DELIVERY_KEY to read released content, not both",
            )
        }

        val needed = if (token != null)
        {
            listOf("COHO_URL", "COHO_ACCOUNT", "COHO_PROJECT", "COHO_REF")
        }
        else
        {
            listOf("COHO_DELIVERY_URL", "COHO_PROJECT", "COHO_REF")
        }
        val missing = needed.filter { value(it) == null }
        if (missing.isNotEmpty())
        {
            val reading = if (token != null) "a preview, since COHO_PREVIEW_TOKEN is set" else "released content"
            val verb = if (missing.size == 1) "is" else "are"
            throw IllegalStateException("Coho: ${missing.joinToString(", ")} $verb needed to read $reading")
        }

        // Checked present above, so each `!!` names a variable the list required.
        val delivery = if (token != null)
        {
            PreviewConfig(value("COHO_URL")!!, value("COHO_ACCOUNT")!!, value("COHO_PROJECT")!!, token)
        }
        else
        {
            ReleasedConfig(value("COHO_DELIVERY_URL")!!, value("COHO_PROJECT")!!, key)
        }
        return ContentConfig(delivery, value("COHO_REF")!!, value("COHO_LOCALE"))
    }
}

internal fun encode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
