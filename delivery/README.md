# coho-delivery

The Coho CMS delivery client, and the runtime your generated content library calls. Written
in Kotlin, for Java and Kotlin alike.

Most projects don't use this directly. They depend on their own content library, generated
from their content types by `coho-codegen`, which depends on this package and gives every
content type its own class and typed reads. Use this package directly for untyped access, or
for anything the generated reads don't cover.

```kotlin
implementation("com.coho-cms:coho-delivery:2026.1.0")
```

Java 17 or later. Built on the JDK's HTTP client and Jackson.

## Untyped reads

```java
DeliveryClient delivery = new DeliveryClient(
    new ReleasedConfig("https://delivery.coho.example", "0192…", System.getenv("COHO_DELIVERY_KEY")));

EntryGraph home = delivery.entryBySlug("prod", "home-page", new ReadOptions(2, "en-US"));
// getRoots(), getEntries() by id, and getUnresolved() by id ("depth" or "absent"); or null
```

`entries`, `entry`, `entryBySlug` and `entriesById` return the delivery API's response as it
was sent: the entries you asked for in `roots`, everything they reference within `depth` hops
in `entries`, and anything referenced but not returned in `unresolved`. Each has an `Async`
form returning a `CompletableFuture`; from a Kotlin coroutine, `await()` it.

## Hydrated reads

`ContentClient` returns entries as their own classes, with their references replaced by the
entries they point at. It is what generated libraries are built on.

```kotlin
// What a generated library declares for you.
class Author(entry: EntryReader) : EntryBase(entry)
{
    val fullName: String = entry.requiredText("fullName")
}

class BlogPost(entry: EntryReader) : EntryBase(entry)
{
    val title: String? = entry.text("title")
    val author: Linked<Author>? = entry.link("author", Author::class.java)
}

val content = ContentClient(
    ContentConfig(config, "prod", "en-US"),
    mapOf("author" to EntryFactory(::Author), "blogPost" to EntryFactory(::BlogPost)),
)
val post = content.type<BlogPost, NoOrder>("blogPost", BlogPost::class.java).bySlug("hello-world", ReadOptions(depth = 2))
println(post?.author?.value?.fullName)
```

- **Each type reads its own fields** from an `EntryReader`, typed. A reader never throws: a
  value of the wrong kind is reported, and read as null.
- **A reference is a `Linked<T>`**: `Resolved`, holding its target, or `Unresolved`, saying
  why not: `depth` beyond the requested depth, `absent` not on the ref, `cycle` where
  following it would loop back, `type` where the target is of a type the field does not link
  to, and `invalid` where it could not be read. `Linked.resolved(list)`, or `list.resolved()`
  in Kotlin, gives the targets that are here.
- **A link to a base type holds its subtype.** Each entry is built as its own class, so a link
  to a `page` that is a landing page holds a `LandingPage`, and `instanceof` or `is` tells
  them apart.
- **Problems are reported, not thrown.** An entry missing a field its type always has, which
  happens to older entries after a field becomes required, takes the field's empty value,
  and is passed to `ClientOptions.onIssue`, once per entry and field for the life of the
  client. The default is a warning on the `com.cohocms.delivery` logger.

## Released and unreleased content

| | `ReleasedConfig` | `PreviewConfig` |
|---|---|---|
| Refs | Tags, and environments pointing at one, such as `qa` and `prod` | Branches, and environments pointing at one, such as `dev` |
| Served by | Delivery, cached at the edge | Coho, live, never cached |
| `url` | Delivery's, such as `https://delivery.coho.example` | Coho's own, where you sign in, such as `https://api.coho.example` |
| Credential | `key`: a delivery key | `token`: a project token, `coho_pt_…`, with the viewer role, plus the `account` |

Both answer the same requests in the same shapes, so a preview runs the code production does.
**Keep both on the server.** A credential is sent as `Authorization: Bearer`, never in a URL,
and a config's `toString()` never shows it.

`CohoEnvironment.fromEnvironment()` reads a config from the `COHO_*` environment variables,
and `CohoEnvironment.from(lookup)` from anything that looks values up by name, such as
Spring's `Environment::getProperty`:

| Variable | |
|---|---|
| `COHO_PROJECT`, `COHO_REF` | Always |
| `COHO_LOCALE` | Optional |
| `COHO_DELIVERY_URL`, `COHO_DELIVERY_KEY` | Released content. The key may be left out for a public environment |
| `COHO_PREVIEW_TOKEN`, `COHO_URL`, `COHO_ACCOUNT` | A preview |

The names are the same in every Coho SDK. Setting a preview token and a delivery key together
is refused, since they read different content.

## Assets

An asset field is an `Asset`: its `file` (media type, size, and for images width and height),
`alt`, `focalPoint`, and the URLs the asset CDN serves it at. It builds resized URLs with no
request and no secret:

```kotlin
hero.image(ImageOptions(width = 800, format = ImageFormat.AUTO))      // resized at the edge
hero.image(ImageOptions.fill(400, 400))                               // cropped around its focal point
hero.srcset()                                                         // 640w … up to the original
hero.srcsetDensities(ImageOptions.width(300), listOf(1.0, 2.0))       // 1x, 2x
brochure.download()                                                   // the original, as an attachment
```

Only raster images resize (`isImage`); an SVG, a PDF or any other file has only its original.
A field that accepts only raster images is an `ImageAsset`, with its `width` and `height`.
The edge snaps widths up to `Asset.IMAGE_WIDTHS`. An asset is as public as its URL, drafts
included.

## Caching

Each client keeps a private cache of responses, following `Cache-Control` and `ETag`. A tag
named directly is fetched once. An environment is reused for its short `max-age`, then
revalidated, which costs a `304` when nothing was promoted. A preview is fetched every time.
`ClientOptions(cacheSize = 0)` turns it off.

## Errors

A refusal throws `CohoDeliveryException`. Branch on its `code`: `REF_NOT_FOUND`,
`KEY_REQUIRED`, `PROJECT_NOT_FOUND`, `INVALID_QUERY` or `RESOLUTION_LIMIT` for the request;
`ARTIFACT_UNREADABLE` or `POOL_UNAVAILABLE` for the server; or `UNEXPECTED_RESPONSE` for an
answer that isn't a problem document. A preview adds `UNAUTHENTICATED`, `ACCOUNT_SUSPENDED`,
`ACCOUNT_CLOSED` and `USER_SUSPENDED`. A missing entry is null, not an exception.

## License

Apache-2.0
