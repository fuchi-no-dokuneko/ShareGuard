plugins {
    id("shareguard.android.library")
}

android {
    namespace = "app.shareguard.benchmark"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:pipeline"))
    implementation(project(":block:text"))
    implementation(project(":block:url"))
    implementation(project(":block:image"))
    implementation(project(":block:ocr"))
    implementation(project(":block:render"))
    implementation(project(":test-corpus"))

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
