plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm()
    android {
        namespace = "com.ratatoskr.shared"
        compileSdk = 37
        minSdk = 26
    }
    // iosX64()/iosArm64()/iosSimulatorArm64() join here in Phase 6 --
    // commonMain below is written to be ready for that without changes;
    // only per-target engine wiring (already isolated to each target's own
    // source set) needs adding.

    sourceSets {
        commonMain {
            dependencies {
                // Kept in step with the org.jetbrains.compose plugin version
                // in the root build file; material3 is versioned separately.
                implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
                implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
                implementation("org.jetbrains.compose.material3:material3:1.9.0")
                implementation("org.jetbrains.compose.ui:ui:1.12.1")

                implementation("io.ktor:ktor-client-core:3.6.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        jvmMain {
            dependencies {
                // CIO is a pure-Kotlin engine with no extra native
                // dependency -- one less thing to go wrong across Linux/
                // Windows/macOS desktop builds versus OkHttp's platform
                // quirks. Revisit only if a JVM-specific feature (e.g.
                // fine-grained connection pooling control) is ever needed.
                implementation("io.ktor:ktor-client-cio:3.6.0")
            }
        }
        androidMain {
            dependencies {
                // OkHttp is Ktor's recommended Android engine: it uses the
                // platform's TLS and network stack.
                implementation("io.ktor:ktor-client-okhttp:3.6.0")
            }
        }
    }
}
