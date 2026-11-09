import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.beevpn"
version = "1.7.4"

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
    // The secp256r1 certificate the MASQUE identity needs — the same library
    // the Android enrollment uses (IdentityRelayClient.enrollVia), pinned to
    // the same version as app/build.gradle.kts.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
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
    // The environment values are the task's INPUTS — the 1.4.0 release shipped
    // an empty LICENSE_URL because the task declared outputs only: the test
    // invocation (no env) generated the file first, and the build invocation
    // (with BEE_LICENSE_URL set) saw the outputs present and skipped as
    // UP-TO-DATE, baking the stale gate-less config into the MSI. Declaring
    // the values as inputs forces a regeneration whenever any of them changes.
    val licenseUrl = System.getenv("BEE_LICENSE_URL")?.takeIf { it.isNotBlank() } ?: ""
    val licenseKey = System.getenv("BEE_LICENSE_PUBKEY_B64")?.takeIf { it.isNotBlank() } ?: ""
    // Empty-string env (a missing CI secret) must fall through to the default,
    // not bake an empty support/telegram URL into the app.
    val telegram = System.getenv("BEE_TELEGRAM_URL")?.takeIf { it.isNotBlank() } ?: "https://t.me/BEESELL_GP"
    val support = System.getenv("BEE_SUPPORT_URL")?.takeIf { it.isNotBlank() } ?: "https://t.me/Bee_hive_VPN"
    val relayHmac = System.getenv("BEE_IDENTITY_RELAY_HMAC")?.takeIf { it.isNotBlank() } ?: ""
    inputs.property("licenseUrl", licenseUrl)
    inputs.property("licenseKey", licenseKey)
    inputs.property("telegram", telegram)
    inputs.property("support", support)
    inputs.property("relayHmac", relayHmac)
    inputs.property("version", project.version.toString())
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("com/beevpn/win/config")
        dir.mkdirs()
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
                // The identity-relay Workers' HMAC — the abuse barrier the
                // phone's IdentityRelayClient rides (BEE_IDENTITY_RELAY_HMAC).
                const val IDENTITY_RELAY_HMAC_B64 = "${esc(relayHmac)}"
                // The two relay endpoints, the same placeholders Android ships.
                const val BEE_RELAY_A = "https://beevpn-identity-relay-a.beevpn-edge-one.workers.dev"
                const val BEE_RELAY_B = "https://beevpn-identity-relay-b.beevpn-edge-two.workers.dev"
                const val GITHUB_URL = "https://github.com/CFGIR200/BEE-VPN"
                const val VERSION = "${project.version}"
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
            // Single source of truth: the project version — the MSI product
            // name follows it, so a version bump is a one-line change.
            packageVersion = project.version.toString()
            description = "BEE-VPN — the honey-fast Windows client"
            vendor = "BEE-VPN"
            // ── The 1.5.0 lesson, wired shut ────────────────────────────
            // With no explicit module list the packaged runtime was trimmed
            // without java.net.http, so the first tap on «فعال‌سازی» died with
            // «Could not initialize class: LicenseAPI» — the gate could never
            // even start its HttpClient. Every module below is load-bearing:
            //   java.net.http      → LicenseAPI (the gate itself)
            //   java.desktop       → Compose / AWT clipboard & fonts
            //   jdk.crypto.ec      → TLS to the Workers (ECDSA certs)
            //   jdk.crypto.cryptoki→ PKCS11 provider
            //   jdk.crypto.mscapi  → Windows certificate store for TLS
            //   java.naming        → BouncyCastle provider plumbing
            //   jdk.unsupported    → sun.misc.Unsafe (skiko / coroutines)
            // CI now asserts the produced image really carries the classes
            // (see windows.yml, the jimage guard step).
            // mscapi exists only on Windows JDKs — gate it so a non-Windows
            // machine can build the very same list for verification.
            val beeModules = mutableListOf(
                "java.base", "java.desktop", "java.logging", "java.management",
                "java.naming", "java.net.http", "java.sql", "java.xml",
                "jdk.crypto.ec", "jdk.crypto.cryptoki",
                "jdk.unsupported", "jdk.zipfs",
            )
            if (System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true) {
                beeModules += "jdk.crypto.mscapi"
            }
            modules(*beeModules.toTypedArray())
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
