import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ch.cslavenc.otpforwarder"
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.cslavenc.otpforwarder"
        minSdk = 26
        // NOT 37: Android 17 withholds SMS_RECEIVED for OTP SMS for 3 h from apps targeting 37.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideload, no Play upload: the debug key is enough.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf(
            // targetSdk 36 is pinned on purpose (see defaultConfig).
            "OldTargetApi",
            "ExpiringTargetSdkVersion",
            // Version nags: upgrades are deliberate, not lint failures.
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            // androidx.lifecycle's bundled lint jar predates this lint version; not fixable in this app.
            "ObsoleteLintCustomCheck",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
