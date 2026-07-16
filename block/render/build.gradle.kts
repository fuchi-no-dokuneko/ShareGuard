plugins {
    id("shareguard.android.library")
}

android {
    namespace = "app.shareguard.block.render"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:pipeline"))
    implementation(project(":core:security"))
    implementation(project(":block:image"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.icu4j)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":test-corpus"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.truth)
}
