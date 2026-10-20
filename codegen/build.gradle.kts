plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

description = "Generate a typed Java or Kotlin content library from your Coho content types."

dependencies()
{
    implementation(libs.jackson.databind)

    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test()
{
    systemProperty("coho.spec", rootProject.file("spec").absolutePath)
    systemProperty("coho.expected", file("src/test/expected").absolutePath)
    // Rewrite the expected output instead of comparing with it: ./gradlew :codegen:test -Pcoho.rewrite
    systemProperty("coho.rewrite", providers.gradleProperty("coho.rewrite").isPresent.toString())
}

mavenPublishing()
{
    publishToMavenCentral()
    // Signed only where a key is given, as the publish workflow does; a local publish is not.
    if (providers.gradleProperty("signingInMemoryKey").isPresent)
    {
        signAllPublications()
    }
    coordinates(group.toString(), "coho-codegen", version.toString())
    pom()
    {
        name.set("Coho codegen")
        description.set(project.description)
        url.set("https://github.com/coho-cms/coho-delivery-sdk-jvm")
        licenses()
        {
            license()
            {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers()
        {
            developer()
            {
                id.set("coho-cms")
                name.set("Coho")
                url.set("https://github.com/coho-cms")
            }
        }
        scm()
        {
            url.set("https://github.com/coho-cms/coho-delivery-sdk-jvm")
            connection.set("scm:git:https://github.com/coho-cms/coho-delivery-sdk-jvm.git")
        }
    }
}
