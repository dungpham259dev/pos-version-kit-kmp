import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        publishLibraryVariants("release")
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api` for types that appear in the public API surface, so consumers
                // (ipos / kpos) get them on their compile classpath transitively.
                api(libs.kotlinx.coroutines.core)     // StateFlow<DownloadProgress>
                api(libs.okio)                        // Path / FileSystem in public signatures
                api(libs.koin.core)                   // Module return type of versionKitModule()
                api(compose.runtime)                  // @Composable
                api(compose.foundation)
                api(compose.material3)
                api(compose.materialIconsExtended)
                api(compose.ui)                       // Modifier
                // Internal implementation details — not exposed in any public signature.
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.okio.fakefilesystem)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                api(libs.koin.android)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

android {
    namespace = "com.vtcode.pos.version"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    // Sign all publications (REQUIRED for the real Maven Central release). Signing is only
    // wired in when a GPG key is configured (signingInMemoryKey / signing.keyId properties),
    // so `publishToMavenLocal` works without keys while the Central release stays signed.
    val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent ||
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent
    if (hasSigningKey) {
        signAllPublications()
    }
    // Coordinates come from gradle.properties (GROUP / POM_ARTIFACT_ID / VERSION_NAME).
    // Re-declaring them here conflicts with those final values, so they are intentionally omitted.
    pom {
        name.set("POS Version Kit (KMP)")
        description.set("Kotlin Multiplatform version check, download and install for VTcode POS (Android + Desktop).")
        url.set("https://github.com/dungpham259dev/pos-version-kit-kmp")
        licenses { license { name.set("Proprietary — VTcode") } }
        developers { developer { id.set("vtcode"); name.set("VTcode") } }
        scm {
            url.set("https://github.com/dungpham259dev/pos-version-kit-kmp")
            connection.set("scm:git:git://github.com/dungpham259dev/pos-version-kit-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/dungpham259dev/pos-version-kit-kmp.git")
        }
    }
}
