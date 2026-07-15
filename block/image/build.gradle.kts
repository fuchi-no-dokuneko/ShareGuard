plugins {
    id("shareguard.android.library")
}

android {
    namespace = "app.shareguard.block.image"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:pipeline"))
    implementation(project(":core:security"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.metadata.extractor)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":test-corpus"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
}
