plugins {
    id("shareguard.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.shareguard.feature.entry"
    buildFeatures.compose = true
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:session"))
    implementation(project(":core:ui"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
