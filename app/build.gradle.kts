plugins {
    id("com.android.application")
}

// The version: -Pversion=1.2.3 (the release workflow passes the tag's), else 0.1.0. The version
// code grows with it (1.2.3 -> 10203), so a newer release always installs over an older one.
val appVersion = (findProperty("version") as String?)?.takeIf { it != "unspecified" } ?: "0.1.0"
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

// Release signing, from the environment (the release workflow sets these from the repository's
// secrets): the keystore's path, its password, the key's alias and the key's password.
val releaseKeystore = System.getenv("ANDROID_KEYSTORE")?.takeIf { it.isNotBlank() }

// The native libraries (chiaki-ng for PS Remote Play, and the shared Rust core) are built apart,
// on Linux, by native/build.sh (Docker locally, Ubuntu in CI), into native/out/jniLibs/<abi>/.
// Without them the app still builds: PS Remote Play then says it isn't in this build, and the
// translation uses the stand-in core.
val nativeLibs = rootProject.file("native/out/jniLibs")

// -PrequireNative: fail if the native libraries are missing (CI and release builds)
if (findProperty("requireNative") != null) {
    for (abi in listOf("arm64-v8a", "armeabi-v7a")) for (lib in listOf("libchiaki-jni.so", "libvi_translator_ffi.so")) {
        val so = nativeLibs.resolve("$abi/$lib")
        if (!so.exists()) throw GradleException("$so is missing: run native/build.sh first (VIT_CORE_DIR for the core)")
    }
}

android {
    namespace = "app.vitranslator.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.vitranslator.phone"
        // Android 8.0: PixelCopy of a window (the Xbox player) and the decoder writing to an ImageReader
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        ndk {
            // phones (arm64, and armeabi-v7a for those whose system is 32-bit, like some Galaxy A
            // models) and the emulator (x86_64); native/build.sh builds the same ones
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add(nativeLibs.path)
        }
    }

    // Two builds of the same app (the same version, key and package name):
    //   github: the APK on GitHub Releases, which updates itself from them (Updater)
    //   play:   the app bundle for Google Play, which Play updates; Play forbids an app that updates
    //           itself, and REQUEST_INSTALL_PACKAGES, so this one has neither (src/play/AndroidManifest.xml)
    buildFeatures {
        buildConfig = true
    }
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        jniLibs {
            // (the native libraries are extracted, so a PS5 session can load them from a service)
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // on-device text recognition, Latin script, bundled in the APK (no download from Play services)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // the shared core's UniFFI bindings (app.vitranslator.core) call its library through JNA
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    // Xbox Remote Play: WebRTC (Google's libwebrtc, prebuilt), the stream Microsoft's own player uses
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
