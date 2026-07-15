plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:pipeline"))
    implementation(libs.icu4j)

    testImplementation(project(":test-corpus"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
