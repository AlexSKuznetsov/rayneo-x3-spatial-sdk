plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

// World-locked panels, images and text on top of :xrspatial. Kotlin and GLES only, no native code.
android {
    namespace = "io.github.alexskuznetsov.xrspatial.scene"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":xrspatial"))
    testImplementation("junit:junit:4.13.2")
}
