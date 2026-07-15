plugins {
    id("shareguard.android.library")
}

android {
    namespace = "app.shareguard.block.ocr"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:pipeline"))
    implementation(project(":block:image"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.barcode)

    testImplementation(project(":test-corpus"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
}
