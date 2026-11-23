import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

group = "com.nadatube.desktop"
version = "1.3.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.tianscar.javasound:jaad:0.9.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

compose.desktop {
    application {
        mainClass = "com.nadatube.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "NadaTube"
            packageVersion = "1.2.0"
            description = "YouTube Audio-Only Player — ad-free, high-res AAC"
            vendor = "NadaTube"
            copyright = "Copyright (c) 2026 NadaTube"
            windows {
                perUserInstall = true
                dirChooser = true
                menuGroup = "NadaTube"
                shortcut = true
                // Ikon Windows = ikon launcher Android (icon.ico multi-ukuran 16-256),
                // sumber: workspace/nadatube/icon/icon.ico (replikasi ic_launcher_* Android).
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            macOS {
                bundleID = "com.nadatube.desktop"
            }
            linux {
                debMaintainer = "NadaTube"
                menuGroup = "Audio"
            }
        }
    }
}
