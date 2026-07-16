plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releasePrivacyEvidence = providers.gradleProperty("shareguardReleasePrivacyEvidence")
    .map(String::toBooleanStrict)
    .orElse(false)

android {
    namespace = "app.shareguard.canonical"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.shareguard.canonical"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = rootProject.version.toString()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notAnnotation"] = "androidx.test.filters.LargeTest"
        vectorDrawables.useSupportLibrary = true
        buildConfigField(
            "boolean",
            "RELEASE_PRIVACY_EVIDENCE",
            releasePrivacyEvidence.map(Boolean::toString).get(),
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = true
        // SDK/dependency freshness is controlled by the audited toolchain and dependency review.
        // Keep correctness/security lint strict without requiring unavailable preview SDKs.
        disable += setOf("GradleDependency", "OldTargetApi")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:pipeline"))
    implementation(project(":core:session"))
    implementation(project(":core:security"))
    implementation(project(":core:storage"))
    implementation(project(":core:ui"))
    implementation(project(":block:text"))
    implementation(project(":block:url"))
    implementation(project(":block:image"))
    implementation(project(":block:ocr"))
    implementation(project(":block:render"))
    implementation(project(":block:verify"))
    implementation(project(":feature:entry"))
    implementation(project(":feature:workflow"))
    implementation(project(":feature:review"))
    implementation(project(":feature:output"))
    implementation(project(":feature:saved"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
}
