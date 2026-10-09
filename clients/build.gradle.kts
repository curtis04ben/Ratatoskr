// Root build file: only declares plugin versions for the modules below to
// apply. No source lives here directly. :shared holds the portable
// Kotlin/Compose code, :desktop holds the JVM/desktop-specific entry point
// and packaging config, :android the Android app. iOS joins this same
// settings file as a sibling once that phase starts, without moving or
// duplicating anything already here.
plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    // AGP 9 needs the Android app in its own module (:android) rather than
    // an Android target on a Kotlin Multiplatform module; :shared uses the
    // KMP library plugin instead.
    id("com.android.application") version "9.4.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.4.1" apply false
}
