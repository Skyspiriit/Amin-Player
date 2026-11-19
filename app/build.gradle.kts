import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.enigma.farsilandtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.enigma.farsilandtv"
        minSdk = 23
        targetSdk = 35
        versionCode = 27
        versionName = "1.26"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional device test build; normal builds still contain every supported ABI.
        providers.gradleProperty("aminTargetAbi").orNull?.let { abi ->
            require(abi in setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
            ndk { abiFilters += abi }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
    // Keep the ARM TV download within the existing updater's size limit.
    packaging.jniLibs.useLegacyPackaging = true
}

// Official unmodified Android engine. Both the download and extracted AAR are pinned.
val vpnEngine = layout.buildDirectory.file("vpn-engine/libXray.aar")
val engineHash = "4ddb0608ffee6e642ab9cda056c37df0b420a6b1e2be7292de33a1a3f3ca7a9a"
fun sha256(file: File) = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
val prepareVpnEngine by tasks.registering {
    outputs.file(vpnEngine)
    outputs.upToDateWhen { vpnEngine.get().asFile.let { it.isFile && sha256(it) == engineHash } }
    doLast {
        val target = vpnEngine.get().asFile
        target.parentFile.mkdirs()
        val archive = File(target.parentFile, "libxray-android-v26.11.9.zip")
        val archiveHash = "1b1aa12124a7112af983a6cea74c77f27aadc6b650acf3c5a57019619ba9fa48"
        if (!archive.isFile || sha256(archive) != archiveHash) {
            URI("https://github.com/XTLS/libXray/releases/download/v26.11.9/libxray-android.zip")
                .toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }.getInputStream().use { input -> archive.outputStream().use(input::copyTo) }
        }
        check(sha256(archive) == archiveHash) { "Xray archive checksum mismatch" }
        ZipFile(archive).use { zip ->
            val entry = checkNotNull(zip.getEntry("libxray-android/libXray.aar"))
            zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
        }
        check(sha256(target) == engineHash) { "Xray Android library checksum mismatch" }
    }
}


dependencies {
    implementation(files(vpnEngine).builtBy(prepareVpnEngine))
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.87")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
