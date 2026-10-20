rootProject.name = "coho-delivery-sdk-jvm"

dependencyResolutionManagement()
{
    repositories()
    {
        mavenCentral()
    }
}

// The runtime and the code generator, which are published; a transport that replays the spec's
// recordings; and a check in each language that the code the generator writes compiles, and
// reads the recordings.
include("delivery", "codegen", "replay", "check-java", "check-kotlin")
