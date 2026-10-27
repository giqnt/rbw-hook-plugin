plugins {
    `java-library`
    `maven-publish`
}

// JitPack serves the coordinates it was asked for, and with a custom install it only promises VERSION and GROUP.
val jitpack = providers.environmentVariable("JITPACK").isPresent
group = if (jitpack) providers.environmentVariable("GROUP").get() else rootProject.group
version = if (jitpack) providers.environmentVariable("VERSION").get() else rootProject.version

repositories {
    mavenCentral()
    maven {
        name = "spigot"
        url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    }
}

dependencies {
    api("org.jspecify:jspecify:1.0.0")
    compileOnly("org.spigotmc:spigot-api:1.21.4-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "rbw-hook-api"
            from(components["java"])
        }
    }
}
