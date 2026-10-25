plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
    id("io.freefair.lombok") version "9.0.0"
}

group = "dev.giqnt.rbw.hook"
// Only v2 tags: `v1` marks the retired v1. With no matching tag this is the commit sha.
version = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty", "--match", "v2*")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().removePrefix("v").ifEmpty { "unknown" } }.get()

repositories {
    mavenCentral()
    maven {
        name = "spigot"
        url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    }
    maven {
        url = uri("https://repo.extendedclip.com/releases/")
    }
}

dependencies {
    api("org.jspecify:jspecify:1.0.0")
    implementation(project(":api"))
    compileOnly("org.spigotmc:spigot-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(fileTree("libs") { include("*.jar") })
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.12.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks {
    processResources {
        val version = project.version.toString()
        inputs.property("version", version)
        filesMatching("plugin.yml") {
            expand("version" to version)
        }
    }

    jar {
        archiveClassifier.set("not-shaded")
    }

    shadowJar {
        archiveClassifier.set(null)
        archiveFileName.set("rbw-hook.jar")
        // Explicit, not auto relocation: the api package and jspecify must keep their paths for external adapters.
        // Keep the patterns narrow: a pattern also rewrites our references, and com.google would take the server's Guava.
        listOf("com.google.gson", "com.google.errorprone", "kotlin", "okhttp3", "okio", "org.jetbrains").forEach {
            relocate(it, "${project.group}.libs.$it")
        }
        minimize {
            exclude(project(":api"))
        }
    }
}
