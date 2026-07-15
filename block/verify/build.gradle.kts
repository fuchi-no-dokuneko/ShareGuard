plugins {
    id("shareguard.android.library")
}

android {
    namespace = "app.shareguard.block.verify"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:pipeline"))
    implementation(project(":block:text"))
    implementation(project(":block:url"))
    implementation(project(":block:image"))
    implementation(project(":block:ocr"))
    implementation(project(":block:render"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":test-corpus"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp)
    androidTestImplementation(libs.androidx.test.junit)
}
