// Compiles the Java the generator writes for each spec fixture, as committed in
// codegen/src/test/expected/java, and reads the recordings through it. Never published.
plugins {
    java
}

dependencies()
{
    implementation(project(":delivery"))

    testImplementation(project(":replay"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

java()
{
    toolchain()
    {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

sourceSets()
{
    main()
    {
        java.setSrcDirs(listOf(rootProject.file("codegen/src/test/expected/java")))
    }
}

tasks.withType<JavaCompile>().configureEach()
{
    options.release.set(17)
    // Generated code compiles without a warning.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test()
{
    useJUnitPlatform()
    systemProperty("coho.spec", rootProject.file("spec").absolutePath)
}
