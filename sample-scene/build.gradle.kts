plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// The app adds the RayNeo X3 Pro SDK 1.1.2 Android libraries itself (checked by :xrspatial, see vendor/README.md).
val rayneoSdkDir = rootProject.file(providers.gradleProperty("rayneoSdkDir").getOrElse("vendor/rayneo-sdk-1.1.2"))

android {
    namespace = "io.github.alexskuznetsov.xrspatial.samplescene"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.alexskuznetsov.xrspatial.samplescene"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    // The RayNeo SDK loads its native libraries from nativeLibraryDir, so they must be extracted.
    packaging { jniLibs.useLegacyPackaging = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":xrspatial-scene"))
    implementation(files(rayneoSdkDir.resolve("RayNeoSDK-client.aar"), rayneoSdkDir.resolve("RayNeoOpenXRLoader.aar")))
}
