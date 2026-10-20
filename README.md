# coho-delivery-sdk-jvm

Java and Kotlin packages for reading content from Coho CMS.

| Package | What it is |
|---|---|
| [`com.coho-cms:coho-delivery`](delivery) | The delivery client, and the runtime generated content libraries call. Written in Kotlin, for Java and Kotlin alike |
| [`com.coho-cms:coho-codegen`](codegen) | Generates a typed content library from your project's content types, in Java or in Kotlin |

To read your content, start with a content library, made from
[coho-template-java](https://github.com/coho-cms/coho-template-java) or
[coho-template-kotlin](https://github.com/coho-cms/coho-template-kotlin). The
[Spring Boot tutorial](https://github.com/coho-cms/coho-samples/tree/HEAD/java/cli-spring-blog)
and the [Ktor tutorial](https://github.com/coho-cms/coho-samples/tree/HEAD/kotlin/cli-ktor-blog)
walk through one, from a content model to a web service.

## Development

A JDK, 21 or later. The packages run on Java 17 and later.

```bash
./gradlew build
```

That runs everything CI runs:

- `delivery`'s tests, against the spec's recorded responses.
- `codegen`'s tests, which compare what it generates for each type model fixture, in both
  languages, with what is committed in `codegen/src/test/expected`.
- `check-java` and `check-kotlin`, which compile that committed code, with warnings as
  errors, and read the recordings through it.

After changing the generator, rewrite the expected code and review the diff:

```bash
./gradlew :codegen:test -Pcoho.rewrite
```

`spec/` is a copy of [coho-sdk-spec](https://github.com/coho-cms/coho-sdk-spec): recorded
delivery responses and type model fixtures, produced by the Coho server. Don't edit it by
hand. `replay` answers from the recordings, for the tests; it is never published.

Kotlin and Gradle scripts here use Allman braces: an opening brace on its own line, lambdas
included, which needs a call's parentheses even when empty, `forEach()`. `.editorconfig` says
how, and which ktlint rules it turns off.

To try a template against a local build, publish to your local Maven repository and add
`mavenLocal()` to the template's repositories, or pass an init script that does:

```bash
./gradlew publishToMavenLocal
```

## Publishing

The two packages are published to Maven Central together, at one version: a library made
from a template depends on that version of both.

1. Set the new version in `gradle.properties`, such as `2026.1.1`, and in both templates'
   `build.gradle.kts`, and merge.
2. Tag this repository at the release commit, `release-v2026.1.1`. The **Publish** workflow
   checks the version, builds and tests, signs, and publishes both packages.

Versions are year.release, as in Coho's other SDKs: a fix is a patch, `2026.1.1`, and a
change that breaks a generated library or the runtime's API is the next release, `2026.2.0`.
A version cannot be published twice.

The workflow needs, in the `maven-central` environment: `MAVEN_CENTRAL_USERNAME` and
`MAVEN_CENTRAL_PASSWORD`, a Central Portal user token for the verified `com.coho-cms`
namespace; and `SIGNING_KEY` and `SIGNING_KEY_PASSWORD`, an armored GPG key whose public half
is on a key server.

## License

Apache-2.0
