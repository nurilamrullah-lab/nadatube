import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

// Reuse the recorded InnerTube fixtures from :core in desktop tests.
sourceSets.test { resources.srcDir("../core/src/test/resources") }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    // Pure-Java AAC decoder (JavaSound cannot decode AAC); we demux the MP4 ourselves.
    implementation("de.sfuhrm:jaad:0.8.7")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(compose.desktop.uiTestJUnit4)
}

val appVersion = providers.gradleProperty("appVersion").getOrElse("1.2.0")

compose.desktop {
    application {
        mainClass = "com.nadatube.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "NadaTube"
            packageVersion = appVersion
            description = "Pemutar YouTube audio-only bebas iklan"
            vendor = "NadaTube"
            modules("java.net.http", "jdk.unsupported")
            windows {
                menuGroup = "NadaTube"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6c1f7d0e-4b6a-4a38-9c0e-2f7d5e8b1a43"
            }
        }
    }
}
