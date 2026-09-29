// Root build file: only declares plugin versions for the modules below to
// apply. No source lives here directly -- :shared holds the portable
// Kotlin/Compose code, :desktop holds the JVM/desktop-specific entry point
// and packaging config. Android and iOS modules join this same settings
// file as siblings of :shared and :desktop once those phases start,
// without moving or duplicating anything already here.
plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
