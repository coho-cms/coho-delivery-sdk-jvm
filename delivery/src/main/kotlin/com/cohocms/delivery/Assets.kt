package com.cohocms.delivery

import com.fasterxml.jackson.databind.JsonNode
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Where a crop centres, as fractions of the width and height. */
data class FocalPoint(val x: Double, val y: Double)

/**
 * The bytes behind an asset, as the server sniffed them.
 *
 * @property width Raster images only, as is [height].
 */
data class AssetFile(
    val filename: String?,
    val mediaType: String?,
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
)

/**
 * How to resize an image. [SCALE], the default, keeps the aspect ratio; [FILL] crops to
 * exactly the width and height around the focal point, as [CROP] and [THUMB] do; [PAD] fits the
 * image inside them and fills the rest with a colour the deployment sets.
 */
enum class ImageFit(internal val value: String)
{
    SCALE("scale"),
    PAD("pad"),
    FILL("fill"),
    CROP("crop"),
    THUMB("thumb"),
}

/** [AUTO] lets the edge pick AVIF, WebP or JPEG from what the browser accepts. */
enum class ImageFormat(internal val value: String)
{
    AUTO("auto"),
    AVIF("avif"),
    WEBP("webp"),
    JPG("jpg"),
    PNG("png"),
}

/**
 * How an image is asked for. Every value is optional.
 *
 * @property quality 1 to 100, which the edge rounds to a multiple of 5; its default is 75.
 *
 * ⚠️ There is no `background`. A [ImageFit.PAD] is filled, and a `jpg` flattened, with a colour
 * the deployment sets rather than one a URL carries. It used to be `bg` here: six hex digits, so
 * about seventeen million cache keys for one image and as many transforms, which anyone could
 * walk. Adding it back means adding that back.
 */
data class ImageOptions @JvmOverloads constructor(
    val width: Int? = null,
    val height: Int? = null,
    val fit: ImageFit? = null,
    val format: ImageFormat? = null,
    val quality: Int? = null,
)
{
    fun withWidth(width: Int) = copy(width = width)

    fun withHeight(height: Int) = copy(height = height)

    fun withFit(fit: ImageFit) = copy(fit = fit)

    fun withFormat(format: ImageFormat) = copy(format = format)

    fun withQuality(quality: Int) = copy(quality = quality)

    companion object
    {
        /** An image of exactly [width] × [height], cropped around its focal point. */
        @JvmStatic
        fun fill(width: Int, height: Int) = ImageOptions(width = width, height = height, fit = ImageFit.FILL)

        @JvmStatic
        fun width(width: Int) = ImageOptions(width = width)
    }
}

/**
 * An asset, as delivery embeds it in the entry that links it: what its URLs are built from,
 * and what a page needs, such as [alt] for an image. Its URLs need no request and no secret.
 *
 * The edge serves an asset two ways: [url], the original bytes of any file, and for raster
 * images only [imageUrl], which resizes on request from query parameters. The edge snaps
 * widths up to [IMAGE_WIDTHS], so these only spell the request; they never resize.
 *
 * An asset is as public as its URL, drafts included, and deleting it does not stop its URL
 * working.
 *
 * @property id The asset's id: what the entry's field links.
 * @property url The original bytes, from the asset CDN.
 * @property imageUrl Raster images only: the base that resizes on request. Use [image].
 */
open class Asset(
    val id: String?,
    val url: String?,
    open val imageUrl: String?,
    open val file: AssetFile?,
    val focalPoint: FocalPoint?,
    val title: String?,
    val alt: String?,
)
{
    /** Whether the edge resizes it: a raster image. */
    val isImage: Boolean get() = imageUrl != null

    /**
     * The asset's URL, resized as [options] ask. A file that is not a raster image, such as an
     * SVG or a PDF, has no resizing, so its original is returned whatever the options.
     */
    @JvmOverloads
    fun image(options: ImageOptions = ImageOptions()): String
    {
        val query = imageQuery(options)
        val base = imageUrl
        return if (query.isEmpty() || base == null) original() else "$base?$query"
    }

    /**
     * A `srcset` for the asset: `url 640w, url 750w, …` for a responsive image, widths beyond the
     * original left out, since the edge never enlarges. A file that is not a raster image has one
     * candidate, its original.
     */
    @JvmOverloads
    fun srcset(options: ImageOptions = ImageOptions(), widths: List<Int> = RESPONSIVE_WIDTHS): String
    {
        if (imageUrl == null)
        {
            return original()
        }
        val largest = file?.width
        var offered = widths.toSortedSet().filter { largest == null || it <= largest }
        // An image smaller than every width offered is still offered, at its own size.
        if (offered.isEmpty() && largest != null)
        {
            offered = listOf(largest)
        }
        return offered.joinToString(", ") { "${image(at(options, it))} ${it}w" }
    }

    /** A `srcset` of an image shown at a fixed `width`, at each pixel density: `url 1x, url 2x`. */
    fun srcsetDensities(options: ImageOptions, densities: List<Double>): String
    {
        require(options.width != null) { "srcset with densities needs a width" }
        if (imageUrl == null)
        {
            return original()
        }
        val width = options.width
        return densities.joinToString(", ") { "${image(at(options, (width * it).roundToInt()))} ${density(it)}x" }
    }

    /**
     * A URL that downloads the asset rather than opening it: the original, with `dl=1` asking
     * the edge to send it as an attachment under its filename. A browser ignores the `download`
     * attribute on a link to another origin, which the asset CDN is.
     *
     * The edge does not honour `dl` yet. Until it does, the file opens in the browser as [url]
     * does; the link needs no change when it starts to.
     */
    fun download(): String
    {
        val original = original()
        return "$original${if ('?' in original) '&' else '?'}dl=1"
    }

    /** [options] at [width], with the height that keeps their shape when they give one. */
    private fun at(options: ImageOptions, width: Int): ImageOptions
    {
        val ratio = if (options.width != null && options.height != null) options.height.toDouble() / options.width else null
        return options.copy(width = width, height = ratio?.let { (width * it).roundToInt() })
    }

    /** The query the edge resizes from, in a fixed order, so equal options make equal URLs. */
    private fun imageQuery(options: ImageOptions): String
    {
        val params = mutableListOf<Pair<String, Any?>>(
            "w" to options.width,
            "h" to options.height,
            "fit" to options.fit?.value,
            "fm" to options.format?.value,
            "q" to options.quality,
        )
        // The edge cannot look the focal point up, so a crop is told where to centre.
        val focal = focalPoint
        if (focal != null && options.fit in setOf(ImageFit.FILL, ImageFit.CROP, ImageFit.THUMB))
        {
            params += "fx" to round2(focal.x)
            params += "fy" to round2(focal.y)
        }
        return params.filter { it.second != null }.joinToString("&") { "${it.first}=${encode(it.second.toString())}" }
    }

    private fun original(): String =
        url ?: throw IllegalStateException("This asset has no URL: the deployment serving it has no asset CDN")

    override fun toString() = "Asset(${file?.filename ?: id})"

    companion object
    {
        /** The widths the edge serves. A requested width snaps up to the next one. */
        @JvmField
        val IMAGE_WIDTHS: List<Int> =
            listOf(16, 32, 48, 64, 96, 128, 192, 256, 320, 384, 480, 640, 750, 828, 960, 1080, 1200, 1440, 1600, 1920, 2048, 2560, 3840)

        /** The widths a responsive `srcset` offers by default: phones up to large screens. */
        @JvmField
        val RESPONSIVE_WIDTHS: List<Int> = IMAGE_WIDTHS.filter { it >= 640 }

        /** The media types an [ImageAsset] can be: the raster images the edge resizes. */
        @JvmField
        val RASTER_IMAGE_TYPES: Set<String> =
            setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/heic")

        /** An asset from what delivery embeds: an [ImageAsset] when it is a raster image with dimensions. */
        @JvmStatic
        fun of(json: JsonNode): Asset
        {
            fun text(node: JsonNode, name: String) = node.get(name)?.takeIf { it.isTextual }?.asText()
            val fileNode = json.get("file")
            val file = fileNode?.takeIf { it.isObject }?.let()
            {
                AssetFile(
                    filename = text(it, "filename"),
                    mediaType = text(it, "mediaType"),
                    byteSize = it.get("byteSize")?.takeIf { size -> size.isNumber }?.asLong(),
                    width = it.get("width")?.takeIf { width -> width.isNumber }?.asInt(),
                    height = it.get("height")?.takeIf { height -> height.isNumber }?.asInt(),
                )
            }
            val focal = json.get("focalPoint")?.takeIf { it.isObject }?.let { FocalPoint(it.path("x").asDouble(), it.path("y").asDouble()) }
            val imageUrl = text(json, "imageUrl")
            val id = text(json, "id")
            val url = text(json, "url")
            return if (imageUrl != null && file?.width != null && file.height != null)
            {
                ImageAsset(id, url, imageUrl, file, focal, text(json, "title"), text(json, "alt"))
            }
            else
            {
                Asset(id, url, imageUrl, file, focal, text(json, "title"), text(json, "alt"))
            }
        }

        private fun round2(value: Double): Any
        {
            val rounded = (value * 100).roundToLong() / 100.0
            return if (rounded == rounded.toLong().toDouble()) rounded.toLong() else rounded
        }

        private fun density(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}

/**
 * An asset whose field accepts only raster images, so it always has dimensions and resizes at
 * the edge. A field that also accepts SVG, or any image, is an [Asset].
 */
class ImageAsset internal constructor(
    id: String?,
    url: String?,
    override val imageUrl: String,
    override val file: AssetFile,
    focalPoint: FocalPoint?,
    title: String?,
    alt: String?,
) : Asset(id, url, imageUrl, file, focalPoint, title, alt)
{
    val width: Int get() = file.width ?: 0
    val height: Int get() = file.height ?: 0
}
