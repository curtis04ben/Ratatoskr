import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm()

    sourceSets {
        jvmMain {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                // Provides Dispatchers.Main (the Swing EDT) -- DesktopFiles
                // needs it to show native file dialogs. Compose Desktop
                // doesn't pull it in on its own.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.ratatoskr.desktop.MainKt"

        nativeDistributions {
            // Phase 2 targets Linux; jpackage's TargetFormat.Deb/Rpm cover
            // that directly. Msi/Exe (Windows) and Dmg (macOS) are listed
            // here too, ready for Phases 4/5 -- they're a no-op on a Linux
            // build machine (jpackage only produces the formats native to
            // the OS it runs on; Windows/macOS installers need to actually
            // be built on those OSes, not cross-compiled from Linux -- see
            // docs/development-plan.md).
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)

            packageName = "Ratatoskr"
            packageVersion = "0.1.0"
            description = "Self-hosted password manager -- native client"
            copyright = "© Ratatoskr project"
            vendor = "Ratatoskr"

            linux {
                iconFile.set(project.file("icons/ratatoskr_512.png"))
                packageName = "ratatoskr"
                debMaintainer = "noreply@example.invalid" // update before a real .deb release
                menuGroup = "Utility"
            }
            windows {
                iconFile.set(project.file("icons/ratatoskr_512.png")) // .ico conversion needed before Phase 4 -- see dev plan
                menuGroup = "Ratatoskr"
                perUserInstall = true
            }
            macOS {
                iconFile.set(project.file("icons/ratatoskr_512.png")) // .icns conversion needed before Phase 5 -- see dev plan
                bundleID = "com.ratatoskr.desktop"
                // macOS bundle versions must have MAJOR > 0, so 0.x can't
                // be used as-is here; revisit when Phase 5 settles versioning.
                packageVersion = "1.0.0"
            }
        }
    }
}
