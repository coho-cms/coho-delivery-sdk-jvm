plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
}

subprojects()
{
    // Built with JDK 21, for Java 17 and later: what Spring Boot 4 and Ktor 3 run on.
    plugins.withId("org.jetbrains.kotlin.jvm")
    {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
        {
            jvmToolchain(21)
            compilerOptions()
            {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                // Kotlin's nullability reaches Java callers as annotations, and Java's
                // JSpecify annotations reach Kotlin as types.
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
        tasks.withType<JavaCompile>().configureEach()
        {
            options.release.set(17)
        }
        tasks.withType<Test>().configureEach()
        {
            useJUnitPlatform()
        }
    }
}
