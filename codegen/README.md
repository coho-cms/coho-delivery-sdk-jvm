# coho-codegen

Generates a typed content library from your Coho CMS content types, in Java or in Kotlin: a
class per content type, and a client whose reads return them.

A library made from [coho-template-java](https://github.com/coho-cms/coho-template-java) or
[coho-template-kotlin](https://github.com/coho-cms/coho-template-kotlin) runs it as Gradle
tasks:

```bash
./gradlew cohoInit --args="--url https://app.coho.example --ref v1.4.0 --package com.acme.content"
./gradlew cohoPull       # fetch the type model for the configured ref into coho-ir.json
./gradlew cohoGenerate   # write the library's coho package from coho-ir.json
./gradlew cohoCheck      # fail if the coho package differs from what generate would write
```

Commit `coho-ir.json` and the generated package. `cohoGenerate` removes the files of types no
longer on the ref, and leaves anything it did not write. `cohoCheck` runs with `check`, so CI
catches generated code that is out of date. Neither calls the server.

`cohoInit` and `cohoPull` need a project token with read access, `coho token create --role
viewer`, as `COHO_TOKEN`: from the environment, or for local runs from the library's `.env`.
`cohoInit` asks Coho which account and project the token is for, writes them into the config,
and with `--package`, renames the library's package wherever it appears. `cohoPull
--args="--ref v1.4.0"` pulls another tag than the config names.

## Configuration

`coho.config.json`, beside the library's `build.gradle.kts`. Paths are relative to it.

```json
{
  "url": "https://app.coho.example",
  "account": "0192…",
  "project": "0192…",
  "ref": "v1.4.0",
  "typeModel": "coho-ir.json",
  "package": "com.acme.content",
  "language": "kotlin"
}
```

The generated code is in the package's `coho` subpackage, `com.acme.content.coho`, under
`src/main/java` or `src/main/kotlin`; `outDir` puts it elsewhere.

## What is generated

- **One class per content type, in its own file**: `blogPost` is `BlogPost`. Its constructor
  reads each field from the entry. In Java, each field has a getter, `getPublishedAt()`, with
  JSpecify's `@Nullable` where delivery can leave it out; in Kotlin, a property, nullable
  there. Every localized field can be left out, in a repeater's items too, so it is nullable,
  a localized list included.
- **References are typed by their targets**: `Linked<TeamMember>`, the entry, or a stub
  saying why it is not there. A list of them is never null: it is empty.
- **An asset field is an `Asset`**, or an `ImageAsset` when it accepts only raster images,
  nullable even when required, since an asset deleted on the ref is delivered as null; in a
  list it is left out.
- **A repeater holds typed items**: each component type is a class deriving from `ItemBase`,
  `Citation`, and a repeater is a `List<Citation>`, or a `List<ItemBase>` when it holds several
  components. A component can hold repeaters too, typed as a content type's are, so items
  hold items, and one can hold itself: a `MenuItem` whose `children` are a `List<MenuItem>`.
  Since type model 1.6. An item of a component added after the code was generated is left
  out, and reported, at every level, so a nested item is never typed as something it is not;
  its issue names the repeater by its path in the entry, `sections/0/points/0/children`.
- **A list holds plain values**: `List<String>`, `List<Long>`, `List<Double>`, or
  `List<OffsetDateTime>`, by the kind of its items. A localized list is nullable: null where
  the requested locale has no translation, and empty where it has none on purpose, so the two
  read differently. A list that is not localized is never null: where delivery leaves it out,
  it is empty, as a list of references is. Its `min` and `max` count its values, and it is
  never sortable. Since type model 1.5; an older coho-codegen reads a list as JSON, with a
  warning.
- **A type that extends another extends its class**, which is then open, and declares only
  what it adds. A read or a link of the base type holds each entry as its own class, so
  `instanceof PhotoGallery` or `is PhotoGallery` tells them apart.
- **A trait is an interface**, `Seo`, which every type with it implements, so code written
  against it takes an entry of any of them.
- **A singleton has one getter.** A type marked as a singleton has one entry on a ref, so
  `coho.siteSettings().get()` in Java and `coho.siteSettings.get()` in Kotlin read it, as a
  `SiteSettings`, and are null before it is first written; each has a `getAsync` form. It
  has no `list`, `bySlug`, or `byId`: its entry's slug is fixed, and the getter reads it from
  the type model's `entrySlug`. A singleton that extends a type is still read with its
  parent, so `coho.page().list()` includes the home page. Since type model 1.7, with
  coho-delivery from this release. An older coho-codegen reads a singleton as an ordinary
  type, which still works.
- **Each type's `SortBy`** names the fields it can be sorted by, `BlogPost.SortBy.PUBLISHED_AT`
  in Java and `BlogPost.SortBy.PublishedAt` in Kotlin, each with its `descending` form, so an
  unsortable order does not compile.
- **`Coho`** has a reader per type, `coho.blogPost()` in Java and `coho.blogPost` in Kotlin,
  with blocking and `Async` reads, and `FACTORIES`, `SOURCE` and `CONTENT_TYPES`.
- **A choice is a `String`**, which its documentation lists the values of, so a value added
  after generation still reads.

A field whose name is a keyword or a system value, such as `type`, is read as `typeValue`. A
type whose class would hide one the generated code uses, such as `Linked`, gets `Entry` after
it, and one read like a member of `Coho`, such as `content`, is read as `contentType`.
`cohoGenerate` says when any of these happens.

## License

Apache-2.0
