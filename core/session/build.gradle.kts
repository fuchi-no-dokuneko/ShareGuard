plugins {
    id("shareguard.android.library")
}

android {
    namespace = "app.shareguard.core.session"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:pipeline"))
    implementation(project(":core:security"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
