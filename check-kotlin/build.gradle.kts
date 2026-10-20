// Compiles the Kotlin the generator writes for each spec fixture, as committed in
// codegen/src/test/expected/kotlin, and reads the recordings through it. Never published.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies()
{
    implementation(project(":delivery"))

    testImplementation(project(":replay"))
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

sourceSets()
{
    main()
    {
        kotlin.setSrcDirs(listOf(rootProject.file("codegen/src/test/expected/kotlin")))
    }
}

kotlin()
{
    compilerOptions()
    {
        // Generated code compiles without a warning.
        allWarningsAsErrors.set(true)
    }
}

tasks.test()
{
    systemProperty("coho.spec", rootProject.file("spec").absolutePath)
}
