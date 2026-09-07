import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Macrobenchmark harness for :androidApp. See perf/README.md for how to run it.
//
// Deliberately does NOT apply the `androidx.benchmark` Gradle plugin: that plugin only adds
// convenience (build-type propagation and environment checks) and its AGP 9 support is
// unproven. A plain `com.android.test` module plus the benchmark-macro-junit4 library is the
// supported, lower-risk configuration.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.foodtracker.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        // Macrobenchmark drives the app over shell + UiAutomator, which needs API 24+ anyway;
        // 29+ is where the frame-timing metrics are actually reliable.
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Benchmarks only ever run against :androidApp's `benchmark` build type (non-debuggable,
    // profileable). There is no point building a debug variant of this module.
    // This machine's debug keystore uses the alias `debug` rather than AGP's default
    // `AndroidDebugKey`, same as :androidApp already accounts for.
    signingConfigs {
        getByName("debug") {
            keyAlias = "debug"
        }
    }

    buildTypes {
        create("benchmark") {
            // The *test* APK stays debuggable — only the app under measurement must not be.
            isDebuggable = true
            // A custom build type inherits no signing config, and an unsigned APK fails to
            // install with INSTALL_PARSE_FAILED_NO_CERTIFICATES.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":androidApp"

    // Lets the benchmark process run separately from the app process so measurements aren't
    // polluted by the instrumentation itself.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
}
