import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing credentials live in a git-ignored keystore.properties next to this
// file (storeFile is resolved relative to this module dir), never in source control.
// See README.MD "Release signing" for how to generate one.
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.reader(Charsets.UTF_8).use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

// One version for both apps: Version.xcconfig is also the iOS project's base configuration.
val appVersion: String = providers.fileContents(rootProject.layout.projectDirectory.file("Version.xcconfig"))
    .asText.get()
    .let { Regex("""(?m)^MARKETING_VERSION\s*=\s*(\S+)""").find(it)?.groupValues?.get(1) }
    ?: error("No MARKETING_VERSION in Version.xcconfig")

android {
    namespace = "com.foodtracker.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.foodtracker.android"
        minSdk = 24
        targetSdk = 37
        // CI passes -PversionCode (see .github/workflows/ci.yml) so every upload to Google Play
        // gets a new, higher code; a local build falls back to this one.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 7
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            keyAlias = "debug"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Target build type for the :macrobenchmark module. Non-debuggable (a debuggable build
        // measures nothing useful — the interpreter dominates) but `profileable`, so the
        // benchmark can capture traces. Minification is deliberately OFF: the point of these
        // benchmarks is to compare two revisions of *our* source, and R8's output shifts
        // between builds enough to swamp that signal. Signed with the debug key so it installs
        // without needing the git-ignored release keystore.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all {
            // Names the directory the JVM performance tests write their numbers into, so a
            // "before" and an "after" run don't overwrite each other:
            //   ./gradlew :androidApp:testDebugUnitTest -Pperf.label=baseline
            it.systemProperty(
                "perf.label",
                providers.gradleProperty("perf.label").getOrElse("adhoc")
            )
        }
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

// Emits per-composable skippability and per-class stability reports. These are the static half
// of the performance baseline (see perf/README.md): the "restartable but not skippable"
// composable count and the "unstable class" count are exact and deterministic, unlike frame
// timings, so they're what proves a stability fix actually landed.
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-metrics")

    // Declares the :shared and kotlinx-datetime types the compiler can't see as stable. See the
    // file itself for why this is needed and why it is accurate rather than a shortcut.
    stabilityConfigurationFiles.add(
        layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

dependencies {
    implementation(project(":shared"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(enforcedPlatform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.reorderable)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.coil.compose)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.concurrent.futures)
    implementation(libs.guava)

    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.image.labeling)

    implementation(libs.zxing.core)

    implementation(libs.play.services.location)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.glance.appwidget)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // Required by :macrobenchmark — the macrobenchmark library drives profile compilation
    // through ProfileInstaller in the *target* app process.
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
