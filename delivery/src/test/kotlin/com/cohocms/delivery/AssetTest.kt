package com.cohocms.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AssetTest
{
    private val base = "https://assets.test/p1"

    private fun asset(json: String) = Asset.of(Spec.mapper.readTree(json))

    private val photo = asset(
        """{"url":"$base/a/abc/culvert.png","imageUrl":"$base/i/abc/culvert.png",
            "file":{"filename":"culvert.png","mediaType":"image/png","width":2000,"height":1000},
            "focalPoint":{"x":0.25,"y":0.333},"alt":"A culvert under Elm Street"}""",
    )

    private val pdf = asset("""{"url":"$base/a/def/guide.pdf","file":{"filename":"guide.pdf","mediaType":"application/pdf","byteSize":52000}}""")

    @Test
    fun `an image with no options is its original`()
    {
        assertEquals(photo.url, photo.image())
        assertIs<ImageAsset>(photo)
    }

    @Test
    fun `a resize goes to the image base, its parameters in a fixed order`()
    {
        assertEquals("$base/i/abc/culvert.png?w=800&fm=auto&q=80", photo.image(ImageOptions(quality = 80, format = ImageFormat.AUTO, width = 800)))
    }

    @Test
    fun `a crop centres on the focal point, rounded to two decimals`()
    {
        assertEquals("$base/i/abc/culvert.png?w=400&h=400&fit=fill&fx=0.25&fy=0.33", photo.image(ImageOptions.fill(400, 400)))
        // A scale keeps the whole image, so it has nothing to centre.
        assertEquals("$base/i/abc/culvert.png?w=400&fit=scale", photo.image(ImageOptions(width = 400, fit = ImageFit.SCALE)))
    }

    @Test
    fun `a file that is not a raster image is its original whatever the options`()
    {
        assertEquals(pdf.url, pdf.image(ImageOptions.width(400)))
        assertEquals(pdf.url, pdf.srcset())
        assertFalse(pdf.isImage)
    }

    @Test
    fun `a responsive srcset offers the edge widths from 640, up to the original, keeping a crop's shape`()
    {
        val candidates = photo.srcset(ImageOptions(format = ImageFormat.AUTO)).split(", ")
        assertEquals(Asset.IMAGE_WIDTHS.filter { it in 640..2000 }.map { "${it}w" }, candidates.map { it.split(' ')[1] })
        assertEquals("$base/i/abc/culvert.png?w=640&fm=auto 640w", candidates.first())

        assertEquals(
            "$base/i/abc/culvert.png?w=400&h=200&fit=fill&fx=0.25&fy=0.33 400w, $base/i/abc/culvert.png?w=800&h=400&fit=fill&fx=0.25&fy=0.33 800w",
            photo.srcset(ImageOptions.fill(400, 200), listOf(800, 400, 800)),
        )
    }

    @Test
    fun `densities give 1x and 2x of a fixed width`()
    {
        assertEquals(
            "$base/i/abc/culvert.png?w=300 1x, $base/i/abc/culvert.png?w=600 2x",
            photo.srcsetDensities(ImageOptions.width(300), listOf(1.0, 2.0)),
        )
        assertFailsWith<IllegalArgumentException> { photo.srcsetDensities(ImageOptions(), listOf(1.0, 2.0)) }
    }

    @Test
    fun `a download is the original, asked for as an attachment`()
    {
        assertEquals("${pdf.url}?dl=1", pdf.download())
        assertEquals("https://assets.test/a?v=1&dl=1", asset("""{"url":"https://assets.test/a?v=1"}""").download())
        assertFailsWith<IllegalStateException> { asset("{}").download() }
    }
}
