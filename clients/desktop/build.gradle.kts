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

// The desktop app's single authoritative version lives in
// clients/gradle.properties (ratatoskrDesktopVersion). The Linux release
// workflow checks the pushed linux-vX.Y.Z tag against it, and
// packaging/linux/build-packages.sh reads it for the package filenames.
val appVersion = providers.gradleProperty("ratatoskrDesktopVersion").get()

compose.desktop {
    application {
        mainClass = "com.ratatoskr.desktop.MainKt"

        nativeDistributions {
            // Linux packages are NOT built with jpackage's own Deb/Rpm
            // formats: its generated .desktop file can't carry our app ID,
            // StartupWMClass (needed for KDE/GNOME to match the window to
            // its launcher entry) or AppStream metadata. Instead
            // `createDistributable` builds the self-contained app image
            // (bundled JRE + launcher) and packaging/linux/build-packages.sh
            // wraps that one image as AppImage, .deb and .rpm -- see
            // clients/README.md. Msi/Exe (Windows) and Dmg (macOS) stay
            // here, ready for Phases 4/5 -- they're a no-op on a Linux
            // build machine (jpackage only produces the formats native to
            // the OS it runs on; Windows/macOS installers need to actually
            // be built on those OSes, not cross-compiled from Linux -- see
            // docs/development-plan.md).
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)

            // jlink only bundles the JDK modules listed here; these were
            // added from `./gradlew :desktop:suggestRuntimeModules` after
            // the default set left the packaged app without modules its
            // dependencies need at runtime. Re-run that task after adding
            // dependencies.
            modules("java.instrument", "java.management", "jdk.unsupported")

            packageName = "Ratatoskr"
            packageVersion = appVersion
            description = "Self-hosted password manager -- native client"
            copyright = "© Ratatoskr project"
            vendor = "Ratatoskr"

            linux {
                iconFile.set(project.file("icons/ratatoskr_512.png"))
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
