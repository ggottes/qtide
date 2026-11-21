// A mochy zip module: an apk of plain kotlin, signed with the key the running mochy uses.
// The sources under api/ are mochy's own :modules:api and :modules:bridge, copied in so a module
// builds against exactly the contract it will be loaded with.
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(17)
}

// one source set: the api, the bridge and the module compile together into a single jar
sourceSets {
    main {
        kotlin.srcDirs("api", "module/src")
    }
}

// one of the three; build.sh is what actually builds the zips, this is for an IDE run
application {
    mainClass.set("com.qtide.QtideTidal")
}
