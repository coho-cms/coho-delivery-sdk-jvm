plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

description = "Read Coho content from Java and Kotlin: the delivery client, and the runtime generated content libraries call."

dependencies()
{
    // In the API: generated libraries read JSON values and declare nullability with these.
    api(libs.jackson.databind)
    api(libs.jspecify)

    testImplementation(project(":replay"))
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test()
{
    // The recordings and fixtures every Coho SDK is tested against.
    systemProperty("coho.spec", rootProject.file("spec").absolutePath)
}

mavenPublishing()
{
    publishToMavenCentral()
    // Signed only where a key is given, as the publish workflow does; a local publish is not.
    if (providers.gradleProperty("signingInMemoryKey").isPresent)
    {
        signAllPublications()
    }
    coordinates(group.toString(), "coho-delivery", version.toString())
    pom()
    {
        name.set("Coho delivery")
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

