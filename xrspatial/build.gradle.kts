import java.security.MessageDigest

plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

// RayNeo X3 Pro SDK 1.1.2 Android libraries, obtained by the user from RayNeo (vendor/README.md).
// They are compile-time only here; the app that uses this library adds them itself. Never redistributed.
val rayneoSdkDir = rootProject.file(providers.gradleProperty("rayneoSdkDir").getOrElse("vendor/rayneo-sdk-1.1.2"))
val rayneoClientAar = rayneoSdkDir.resolve("RayNeoSDK-client.aar")
val rayneoPins = mapOf(
    "RayNeoSDK-client.aar" to "7deab5b1754065106065a5eea1fdab9dbf58f75c7226c8a60bef737141f7b28f",
    "RayNeoOpenXRLoader.aar" to "36c36d54603570fff2cc069f6c37c5398829990a082bdf204a12961febf76fab",
)
rayneoPins.forEach { (name, expected) ->
    val input = rayneoSdkDir.resolve(name)
    check(input.isFile) { "Missing $input: put the RayNeo X3 Pro SDK 1.1.2 Android libraries there (see vendor/README.md)" }
    val digest = MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
    check(digest == expected) { "Unexpected $name (SHA-256 $digest): this library is tested with RayNeo SDK 1.1.2 only" }
}

// Java classes of the RayNeo client for compilation (an AAR cannot be a compile-only dependency directly).
val extractRayneoClasses by tasks.registering(Copy::class) {
    from(zipTree(rayneoClientAar)) { include("classes.jar") }
    into(layout.buildDirectory.dir("rayneo-client"))
}

android {
    namespace = "io.github.alexskuznetsov.xrspatial"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }

tasks.named("preBuild") { dependsOn(extractRayneoClasses) }

dependencies {
    api(project(":xrspatial-core"))
    compileOnly(files(layout.buildDirectory.file("rayneo-client/classes.jar")))
    testImplementation("junit:junit:4.13.2")
}
