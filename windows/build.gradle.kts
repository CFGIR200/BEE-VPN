import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.beevpn"
version = "1.0.0"

repositories {
    // JetBrains' read-through mirror of Maven Central — resilient when the
    // origin rate-limits (429), identical artifacts.
    maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    // The exact org.json API the Android app uses — ShardOutboundBuilder.kt
    // ports over without a single call-site change.
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// ---------------------------------------------------------------------------
// BeeConfig.kt — generated from the environment, exactly like Android's
// buildConfigField block and iOS's gen-config.sh. With BEE_LICENSE_URL /
// BEE_LICENSE_PUBKEY_B64 empty the gate compiles to a pass-through.
// ---------------------------------------------------------------------------
val beeConfigDir = layout.buildDirectory.dir("generated/beekt")

sourceSets.main {
    resources.srcDir(project.file("cores-bin"))
}

val generateBeeConfig by tasks.registering {
    val outDir = beeConfigDir
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("com/beevpn/win/config")
        dir.mkdirs()
        val licenseUrl = System.getenv("BEE_LICENSE_URL") ?: ""
        val licenseKey = System.getenv("BEE_LICENSE_PUBKEY_B64") ?: ""
        val telegram = System.getenv("BEE_TELEGRAM_URL") ?: "https://t.me/BEESELL_GP"
        val support = System.getenv("BEE_SUPPORT_URL") ?: "https://t.me/Bee_hive_VPN"
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        dir.resolve("BeeConfig.kt").writeText(
            """
            // GENERATED at build time from the BEE_* environment — do not edit.
            package com.beevpn.win.config

            object BeeConfig {
                const val LICENSE_URL = "${esc(licenseUrl)}"
                const val LICENSE_PUBKEY_B64 = "${esc(licenseKey)}"
                const val TELEGRAM_URL = "${esc(telegram)}"
                const val SUPPORT_URL = "${esc(support)}"
                const val GITHUB_URL = "https://github.com/CFGIR200/BEE-VPN"
                const val VERSION = "1.0.0"
            }
            """.trimIndent()
        )
    }
}

sourceSets.main {
    kotlin.srcDir(beeConfigDir)
}

tasks.named("compileKotlin") { dependsOn(generateBeeConfig) }

// ---------------------------------------------------------------------------
// Fat jar — CI (on a Windows runner) packs this into the portable zip.
// ---------------------------------------------------------------------------
val fatJar = tasks.register<Jar>("fatJar") {
    group = "build"
    archiveBaseName.set("BEE-VPN")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.beevpn.win.MainKt"
        attributes["Implementation-Version"] = project.version
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("META-INF/versions/9/module-info.class", "module-info.class")
        exclude("kotlin/**/module-info.class")
    }
    dependsOn(generateBeeConfig)
}

compose.desktop {
    application {
        mainClass = "com.beevpn.win.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "BEE-VPN"
            packageVersion = "1.0.0"
            description = "BEE-VPN — the honey-fast Windows client"
            vendor = "BEE-VPN"
            windows {
                menu = true
                shortcut = true
                perUserInstall = true
                iconFile.set(project.file("resources/appicon.ico"))
            }
        }
    }
}

tasks.withType<Test> { useJUnitPlatform() }
